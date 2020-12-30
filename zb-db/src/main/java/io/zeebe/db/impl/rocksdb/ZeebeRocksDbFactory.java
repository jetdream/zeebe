/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.db.impl.rocksdb;

import io.zeebe.db.DbKey;
import io.zeebe.db.ZeebeDbException;
import io.zeebe.db.ZeebeDbFactory;
import io.zeebe.db.impl.DbShort;
import io.zeebe.db.impl.rocksdb.transaction.ZeebeTransactionDb;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.agrona.CloseHelper;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.Cache;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompactionPriority;
import org.rocksdb.CompactionStyle;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.Filter;
import org.rocksdb.IndexType;
import org.rocksdb.LRUCache;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.Statistics;
import org.rocksdb.StatsLevel;
import org.rocksdb.TableFormatConfig;

public final class ZeebeRocksDbFactory<ColumnFamilyType extends Enum<ColumnFamilyType>>
    implements ZeebeDbFactory<ColumnFamilyType> {

  static {
    RocksDB.loadLibrary();
  }

  private final Class<ColumnFamilyType> columnFamilyTypeClass;
  private final Properties userProvidedColumnFamilyOptions;

  private ZeebeRocksDbFactory(
      final Class<ColumnFamilyType> columnFamilyTypeClass,
      final Properties userProvidedColumnFamilyOptions) {
    this.columnFamilyTypeClass = columnFamilyTypeClass;
    this.userProvidedColumnFamilyOptions = Objects.requireNonNull(userProvidedColumnFamilyOptions);
  }

  public static <ColumnFamilyType extends Enum<ColumnFamilyType>>
      ZeebeDbFactory<ColumnFamilyType> newFactory(
          final Class<ColumnFamilyType> columnFamilyTypeClass) {
    final var columnFamilyOptions = new Properties();
    return new ZeebeRocksDbFactory<>(columnFamilyTypeClass, columnFamilyOptions);
  }

  public static <ColumnFamilyType extends Enum<ColumnFamilyType>>
      ZeebeDbFactory<ColumnFamilyType> newFactory(
          final Class<ColumnFamilyType> columnFamilyTypeClass,
          final Properties userProvidedColumnFamilyOptions) {
    return new ZeebeRocksDbFactory<>(columnFamilyTypeClass, userProvidedColumnFamilyOptions);
  }

  @Override
  public ZeebeTransactionDb<ColumnFamilyType> createDb(final File pathName) {
    return open(pathName, columnFamilyTypeClass.getEnumConstants());
  }

  private ZeebeTransactionDb<ColumnFamilyType> open(
      final File dbDirectory, final ColumnFamilyType[] columnFamilies) {
    final List<AutoCloseable> closeables = new ArrayList<>();

    try {
      // column family options have to be closed as last
      final ColumnFamilyOptions columnFamilyOptions = createColumnFamilyOptions(closeables);
      closeables.add(columnFamilyOptions);

      // can be omitted when not profiling, or at least lower the stats level
      final Statistics statistics = new Statistics();
      closeables.add(statistics);
      statistics.setStatsLevel(StatsLevel.ALL);

      final DBOptions dbOptions =
          new DBOptions()
              .setErrorIfExists(false)
              .setCreateIfMissing(true)
              .setParanoidChecks(true)
              // we only use the default CF
              .setCreateMissingColumnFamilies(false)
              // may not be necessary when WAL is disabled, but nevertheless recommended to avoid
              // many small SST files
              .setAvoidFlushDuringRecovery(true)
              // for use cases making heavy use of iterations and buffered I/O (i.e. OS page cache),
              // use the read-ahead option and turn off the random access hint, which may help on
              // hard drives and/or remote storage (but not on local SSDs)
              .setAdviseRandomOnOpen(false)
              // fsync is called asynchronously once we have at least 1Mb
              .setBytesPerSync(1048576L)
              // limit the size of the manifest (logs all operations), otherwise it will grow
              // unbounded
              .setMaxManifestFileSize(256 * 1024 * 1024L)
              // can be disabled when not profiling
              .setStatsDumpPeriodSec(20)
              .setStatistics(statistics);
      closeables.add(dbOptions);

      final Options options = new Options(dbOptions, columnFamilyOptions);
      closeables.add(options);

      // TODO: enforce the key type here to be the same as the instance we pass
      final DbKey prefixKeyInstance = new DbShort();
      final Map<ColumnFamilyType, DbKey> columnPrefixMap = computeColumnPrefixMap(columnFamilies);

      return ZeebeTransactionDb.openTransactionalDb(
          options, dbDirectory.getAbsolutePath(), columnPrefixMap, prefixKeyInstance, closeables);
    } catch (final RocksDBException e) {
      CloseHelper.quietCloseAll(closeables);
      throw new ZeebeDbException("Unexpected error occurred trying to open the database", e);
    }
  }

  private EnumMap<ColumnFamilyType, DbKey> computeColumnPrefixMap(
      final ColumnFamilyType[] columnFamilies) {
    return Arrays.stream(columnFamilies)
        .reduce(
            new EnumMap<>(columnFamilyTypeClass),
            (acc, cfType) -> {
              acc.put(cfType, new DbShort((short) cfType.ordinal()));
              return acc;
            },
            (left, right) -> {
              left.putAll(right);
              return left;
            });
  }

  /** @return Options which are used on all column families */
  public ColumnFamilyOptions createColumnFamilyOptions(final List<AutoCloseable> closeables) {
    // given
    final long totalMemoryBudget = 512 * 1024 * 1024L; // make this configurable
    // recommended by RocksDB, but we could tweak it; keep in mind we're also caching the indexes
    // and filters into the block cache, so we don't need to account for more memory there
    final long blockCacheMemory = totalMemoryBudget / 3;
    // flushing the memtables is done asynchronously, so there may be multiple memtables in memory,
    // although only a single one is writable. once we have too many memtables, writes will stop.
    // since prefix iteration is our bread n butter, we will build an additional filter for each
    // memtable which takes a bit of memory which must be accounted for from the memtable's memory
    final int maxConcurrentMemtableCount = 10;
    final double memtablePrefixFilterMemory = 0.25;
    final long memtableMemory =
        Math.round(
            ((totalMemoryBudget - blockCacheMemory) / (double) maxConcurrentMemtableCount)
                * (1 - memtablePrefixFilterMemory));

    // start with some defaults
    final var columnFamilyOptionProps = new Properties();
    // look for cf_options.h to find available keys
    // look for options_helper.cc to find available values
    columnFamilyOptionProps.put("compaction_pri", "kOldestSmallestSeqFirst");

    // apply custom options
    columnFamilyOptionProps.putAll(userProvidedColumnFamilyOptions);

    final var columnFamilyOptions =
        ColumnFamilyOptions.getColumnFamilyOptionsFromProps(columnFamilyOptionProps);
    if (columnFamilyOptions == null) {
      throw new IllegalStateException(
          String.format(
              "Expected to create column family options for RocksDB, "
                  + "but one or many values are undefined in the context of RocksDB "
                  + "[Compiled ColumnFamilyOptions: %s; User-provided ColumnFamilyOptions: %s]. "
                  + "See RocksDB's cf_options.h and options_helper.cc for available keys and values.",
              columnFamilyOptionProps, userProvidedColumnFamilyOptions));
    }

    final Cache cache = new LRUCache(blockCacheMemory);
    closeables.add(cache);

    final Filter filter = new BloomFilter(10, false);
    closeables.add(filter);

    final TableFormatConfig tableConfig =
        new BlockBasedTableConfig()
            .setBlockCache(cache)
            .setFilterPolicy(filter)
            // increasing block size means reducing memory usage, but increasing read iops
            .setBlockSize(32 * 1024L)
            // caching and pinning indexes and filters is important to keep reads/seeks fast when we
            // have many memtables, and pinning them ensures they are never evicted from the block
            // cache
            .setCacheIndexAndFilterBlocks(true)
            .setPinL0FilterAndIndexBlocksInCache(true)
            // default is binary search, but all of our scans are prefix based which is a good use
            // case for efficient hashing
            .setIndexType(IndexType.kHashSearch);

    // TODO: allow settings to be overwritable
    return columnFamilyOptions
        // prefix seek must be fast, so allocate an extra 10% of a single memtable budget to create
        // a filter for each memtable, allowing us to skip them if possible
        .useFixedLengthPrefixExtractor(Short.BYTES)
        .setMemtablePrefixBloomSizeRatio(memtablePrefixFilterMemory)
        // memtable
        // merge at least 2 memtables per L0 file, otherwise all memtables are flushed as individual
        // files
        .setMinWriteBufferNumberToMerge(Math.min(2, maxConcurrentMemtableCount))
        .setMaxWriteBufferNumberToMaintain(maxConcurrentMemtableCount)
        .setMaxWriteBufferNumber(maxConcurrentMemtableCount)
        .setWriteBufferSize(memtableMemory)
        // compaction
        .setLevelCompactionDynamicLevelBytes(true)
        .setCompactionPriority(CompactionPriority.OldestSmallestSeqFirst)
        .setCompactionStyle(CompactionStyle.LEVEL)
        // aim for ~8mb SST files
        .setTargetFileSizeBase(8 * 1024 * 1024L)
        // L-0 means immediately flushed memtables
        .setLevel0FileNumCompactionTrigger(maxConcurrentMemtableCount)
        .setLevel0SlowdownWritesTrigger(
            maxConcurrentMemtableCount + (maxConcurrentMemtableCount / 2))
        .setLevel0StopWritesTrigger(maxConcurrentMemtableCount * 2)
        // configure 4 levels: L1 = 32mb, L2 = 320mb, L3 = 3.2Gb, L4 >= 3.2Gb
        // level 1 and 2 are uncompressed, level 3 and above are compressed using a CPU-cheap
        // compression algo. compressed blocks are stored in the OS page cache, and uncompressed in
        // the LRUCache created above
        .setNumLevels(4)
        .setMaxBytesForLevelBase(32 * 1024 * 1024L)
        .setMaxBytesForLevelMultiplier(10)
        .setCompressionPerLevel(
            List.of(
                CompressionType.NO_COMPRESSION,
                CompressionType.NO_COMPRESSION,
                CompressionType.LZ4_COMPRESSION,
                CompressionType.LZ4_COMPRESSION))
        // misc
        .setTableFormatConfig(tableConfig);
  }
}

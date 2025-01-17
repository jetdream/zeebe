/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.exporter.records;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.camunda.zeebe.exporter.AbstractElasticsearchExporterIntegrationTestCase;
import io.camunda.zeebe.exporter.ElasticsearchExporter;
import io.camunda.zeebe.protocol.record.intent.DeploymentIntent;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;

public final class ElasticsearchExporterDmnRecordIT
    extends AbstractElasticsearchExporterIntegrationTestCase {

  private static final String DMN_RESOURCE = "dmn/decision-table.dmn";

  @Before
  public void init() {
    elastic.start();

    configuration = getDefaultConfiguration();
    configuration.index.deployment = true;

    esClient = createElasticsearchClient(configuration);

    exporterBrokerRule.configure("es", ElasticsearchExporter.class, configuration);
    exporterBrokerRule.start();
  }

  @Test
  public void shouldExportDeploymentRecord() {
    // when
    exporterBrokerRule.deployResourceFromClasspath(DMN_RESOURCE);

    // then
    await("index templates need to be created")
        .atMost(Duration.ofMinutes(1))
        .untilAsserted(this::assertIndexSettings);

    final var deploymentRecord =
        RecordingExporter.deploymentRecords().withIntent(DeploymentIntent.CREATED).getFirst();

    assertThat(deploymentRecord.getValue().getDecisionRequirementsMetadata()).isNotEmpty();
    assertThat(deploymentRecord.getValue().getDecisionsMetadata()).isNotEmpty();

    assertRecordExported(deploymentRecord);
  }

  @Test
  public void shouldExportDecisionRecord() {
    // when
    exporterBrokerRule.deployResourceFromClasspath(DMN_RESOURCE);

    // then
    await("index templates need to be created")
        .atMost(Duration.ofMinutes(1))
        .untilAsserted(this::assertIndexSettings);

    final var decisionRecord = RecordingExporter.decisionRecords().getFirst();

    assertRecordExported(decisionRecord);
  }

  @Test
  public void shouldExportDecisionRequirementsRecord() {
    // when
    exporterBrokerRule.deployResourceFromClasspath(DMN_RESOURCE);

    // then
    await("index templates need to be created")
        .atMost(Duration.ofMinutes(1))
        .untilAsserted(this::assertIndexSettings);

    final var decisionRequirementsRecord =
        RecordingExporter.decisionRequirementsRecords().getFirst();

    assertRecordExported(decisionRequirementsRecord);
  }
}

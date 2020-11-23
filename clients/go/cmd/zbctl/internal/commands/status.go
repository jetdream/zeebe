// Copyright © 2018 Camunda Services GmbH (info@camunda.com)
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package commands

import (
	"context"
	"fmt"
	"github.com/spf13/cobra"
	"github.com/zeebe-io/zeebe/clients/go/pkg/pb"
	"sort"
)

type ByNodeID []*pb.BrokerInfo

func (a ByNodeID) Len() int           { return len(a) }
func (a ByNodeID) Swap(i, j int)      { a[i], a[j] = a[j], a[i] }
func (a ByNodeID) Less(i, j int) bool { return a[i].NodeId < a[j].NodeId }

type ByPartitionID []*pb.Partition

func (a ByPartitionID) Len() int           { return len(a) }
func (a ByPartitionID) Swap(i, j int)      { a[i], a[j] = a[j], a[i] }
func (a ByPartitionID) Less(i, j int) bool { return a[i].PartitionId < a[j].PartitionId }

var (
	jsonFlag bool
)

var statusCmd = &cobra.Command{
	Use:     "status",
	Short:   "Checks the current status of the cluster",
	Args:    cobra.NoArgs,
	PreRunE: initClient,
	RunE: func(cmd *cobra.Command, args []string) error {
		var err error

		ctx, cancel := context.WithTimeout(context.Background(), defaultTimeout)
		defer cancel()

		resp, err := client.NewTopologyCommand().Send(ctx)
		if err != nil {
			return err
		}

		if jsonFlag {
			printJSON(resp)
		} else {
			printStatus(resp)
		}
		return nil
	},
}

func printStatus(resp *pb.TopologyResponse) {
	gatewayVersion := "unavailable"
	if resp.GatewayVersion != "" {
		gatewayVersion = resp.GatewayVersion
	}

	fmt.Println("Cluster size:", resp.ClusterSize)
	fmt.Println("Partitions count:", resp.PartitionsCount)
	fmt.Println("Replication factor:", resp.ReplicationFactor)
	fmt.Println("Gateway version:", gatewayVersion)
	fmt.Println("Brokers:")

	sort.Sort(ByNodeID(resp.Brokers))

	for _, broker := range resp.Brokers {
		fmt.Printf("  Broker %d - %s:%d\n", broker.NodeId, broker.Host, broker.Port)

		version := "unavailable"
		if broker.Version != "" {
			version = broker.Version
		}

		fmt.Printf("    Version: %s\n", version)

		sort.Sort(ByPartitionID(broker.Partitions))
		for _, partition := range broker.Partitions {
			fmt.Printf(
				"    Partition %d : %s, %s\n",
				partition.PartitionId,
				roleToString(partition.Role),
				healthToString(partition.Health),
			)
		}
	}
}

func init() {
	rootCmd.AddCommand(statusCmd)

	statusCmd.Flags().BoolVar(&jsonFlag, "json", false, "Specify to format output as JSON")
}

const unknownState = "Unknown"

func roleToString(role pb.Partition_PartitionBrokerRole) string {
	switch role {
	case pb.Partition_LEADER:
		return "Leader"
	case pb.Partition_FOLLOWER:
		return "Follower"
	default:
		return unknownState
	}
}

func healthToString(health pb.Partition_PartitionBrokerHealth) string {
	switch health {
	case pb.Partition_HEALTHY:
		return "Healthy"
	case pb.Partition_UNHEALTHY:
		return "Unhealthy"
	default:
		return unknownState
	}
}

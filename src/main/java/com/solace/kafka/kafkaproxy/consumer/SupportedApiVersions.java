/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.solace.kafka.kafkaproxy.consumer;

import java.util.Map;
import java.util.TreeMap;

import org.apache.kafka.common.message.ApiVersionsResponseData.ApiVersion;
import org.apache.kafka.common.message.ApiVersionsResponseData.ApiVersionCollection;

import lombok.Getter;

public class SupportedApiVersions {

    @Getter
    private static final Map<Short, VersionEntry> apiVersions = new TreeMap<>();

    @Getter
    private static final ApiVersionCollection apiVersionCollection = new ApiVersionCollection();

    // The minVersion values are maximum supported by kafka-clients v2.0.0
    // The maxVersion values are maximum supported by kafka-clients v3.7.1
    // Should ensure compatibility for kafka-clients <= v3.9
    // TODO: Verify min/max versions between Kafka 2.0 --> 3.7
    static {
        apiVersions.put((short) 0, new VersionEntry((short) 0, (short) 6, (short) 10, "Produce"));              // Produce Channel
        apiVersions.put((short) 1, new VersionEntry((short) 1, (short) 8, (short) 16, "Fetch"));                // Fetch Channel
        apiVersions.put((short) 2, new VersionEntry((short) 2, (short) 4, (short) 8, "ListOffsets"));           // Fetch Channel
        apiVersions.put((short) 3, new VersionEntry((short) 3, (short) 7, (short) 12, "Metadata"));             // All Channels
        apiVersions.put((short) 8, new VersionEntry((short) 8, (short) 4, (short) 9, "OffsetCommit"));          // Group Channel
        apiVersions.put((short) 9, new VersionEntry((short) 9, (short) 5, (short) 9, "OffsetFetch"));           // Group Channel
        apiVersions.put((short) 10, new VersionEntry((short) 10, (short) 2, (short) 4, "FindCoordinator"));     // Metadata Channel
        apiVersions.put((short) 11, new VersionEntry((short) 11, (short) 4, (short) 9, "JoinGroup"));           // Group Channel
        apiVersions.put((short) 12, new VersionEntry((short) 12, (short) 2, (short) 4, "Heartbeat"));           // Group Channel
        apiVersions.put((short) 13, new VersionEntry((short) 13, (short) 2, (short) 5, "LeaveGroup"));          // Group Channel
        apiVersions.put((short) 14, new VersionEntry((short) 14, (short) 3, (short) 5, "SyncGroup"));           // Group Channel
        apiVersions.put((short) 15, new VersionEntry((short) 15, (short) 2, (short) 5, "DescribeGroups"));
        apiVersions.put((short) 16, new VersionEntry((short) 16, (short) 2, (short) 4, "ListGroups"));
        apiVersions.put((short) 17, new VersionEntry((short) 17, (short) 1, (short) 1, "SaslHandshake"));       // All Channels
        apiVersions.put((short) 18, new VersionEntry((short) 18, (short) 0, (short) 3, "ApiVersions"));         // All Channels
        apiVersions.put((short) 19, new VersionEntry((short) 19, (short) 4, (short) 7, "CreateTopics"));
        apiVersions.put((short) 20, new VersionEntry((short) 20, (short) 3, (short) 6, "DeleteTopics"));
        apiVersions.put((short) 21, new VersionEntry((short) 21, (short) 1, (short) 2, "DeleteRecords"));
        apiVersions.put((short) 22, new VersionEntry((short) 22, (short) 1, (short) 4, "InitProducerId"));      // Produce Channel
        apiVersions.put((short) 23, new VersionEntry((short) 23, (short) 1, (short) 4, "OffsetForLeaderEpoch"));    // Fetch Channel
        apiVersions.put((short) 24, new VersionEntry((short) 24, (short) 1, (short) 4, "AddPartitionsToTxn"));
        apiVersions.put((short) 25, new VersionEntry((short) 25, (short) 1, (short) 3, "AddOffsetsToTxn"));
        apiVersions.put((short) 26, new VersionEntry((short) 26, (short) 1, (short) 3, "EndTxn"));
        apiVersions.put((short) 27, new VersionEntry((short) 27, (short) 0, (short) 1, "WriteTxnMarkers"));
        apiVersions.put((short) 28, new VersionEntry((short) 28, (short) 1, (short) 3, "TxnOffsetCommit"));
        apiVersions.put((short) 29, new VersionEntry((short) 29, (short) 1, (short) 3, "DescribeAcls"));
        apiVersions.put((short) 30, new VersionEntry((short) 30, (short) 1, (short) 3, "CreateAcls"));
        apiVersions.put((short) 31, new VersionEntry((short) 31, (short) 1, (short) 3, "DeleteAcls"));
        apiVersions.put((short) 32, new VersionEntry((short) 32, (short) 1, (short) 4, "DescribeConfigs"));
        apiVersions.put((short) 33, new VersionEntry((short) 33, (short) 1, (short) 2, "AlterConfigs"));
        // Key 34-35 are broker-only APIs
        apiVersions.put((short) 36, new VersionEntry((short) 36, (short) 1, (short) 2, "SaslAuthenticate"));    // All Channels
        /**
         * ***** Nothing below this point is supported by the proxy *****
         */
        apiVersions.put((short) 37, new VersionEntry((short) 37, (short) 1, (short) 3, "CreatePartitions"));
        apiVersions.put((short) 38, new VersionEntry((short) 38, (short) 0, (short) 2, "CreateDelegationToken"));
        apiVersions.put((short) 39, new VersionEntry((short) 39, (short) 0, (short) 2, "RenewDelegationToken"));
        apiVersions.put((short) 40, new VersionEntry((short) 40, (short) 0, (short) 2, "ExpireDelegationToken"));
        apiVersions.put((short) 41, new VersionEntry((short) 41, (short) 0, (short) 2, "DescribeDelegationToken"));
        apiVersions.put((short) 42, new VersionEntry((short) 42, (short) 1, (short) 2, "DeleteGroups"));
        apiVersions.put((short) 43, new VersionEntry((short) 43, (short) 0, (short) 2, "ElectLeaders"));
        apiVersions.put((short) 44, new VersionEntry((short) 44, (short) 0, (short) 2, "IncrementalAlterConfigs"));
        apiVersions.put((short) 45, new VersionEntry((short) 45, (short) 0, (short) 0, "AlterPartitionReassignments"));
        apiVersions.put((short) 46, new VersionEntry((short) 46, (short) 0, (short) 0, "ListPartitionReassignments"));
        apiVersions.put((short) 47, new VersionEntry((short) 47, (short) 0, (short) 0, "OffsetDelete"));
        apiVersions.put((short) 48, new VersionEntry((short) 48, (short) 0, (short) 1, "DescribeClientQuotas"));
        apiVersions.put((short) 49, new VersionEntry((short) 49, (short) 0, (short) 1, "AlterClientQuotas"));
        apiVersions.put((short) 50, new VersionEntry((short) 50, (short) 0, (short) 0, "DescribeUserScramCredentials"));
        apiVersions.put((short) 51, new VersionEntry((short) 51, (short) 0, (short) 0, "AlterUserScramCredentials"));
        // Keys 52-55 are KRaft controller internal APIs
        // Key 56 is broker-to-controller
        apiVersions.put((short) 57, new VersionEntry((short) 57, (short) 0, (short) 1, "UpdateFeatures"));
        // Key 58-59 are broker/controller internal
        apiVersions.put((short) 60, new VersionEntry((short) 60, (short) 0, (short) 0, "DescribeCluster"));
        apiVersions.put((short) 61, new VersionEntry((short) 61, (short) 0, (short) 0, "DescribeProducers"));
        // Keys 62-64 are broker-to-controller
        apiVersions.put((short) 65, new VersionEntry((short) 65, (short) 0, (short) 0, "DescribeTransactions"));
        apiVersions.put((short) 66, new VersionEntry((short) 66, (short) 0, (short) 0, "ListTransactions"));
        apiVersions.put((short) 67, new VersionEntry((short) 67, (short) 0, (short) 0, "AllocateProducerIds"));

        for (SupportedApiVersions.VersionEntry entry : SupportedApiVersions.getApiVersions().values()) {
            apiVersionCollection.add(entry.toApiVersion());
        }
    }

    public static VersionEntry getApiVersion(short apiKey) {
        return apiVersions.get(apiKey);
    }

    public static short getApiMinVersion(short apiKey) {
        return getApiVersion(apiKey).getMinVersion();
    }

    public static short getApiMaxVersion(short apiKey) {
        return getApiVersion(apiKey).getMaxVersion();
    }

    public static class VersionEntry {

        @Getter
        short apiKey;

        @Getter
        short minVersion;

        @Getter
        short maxVersion;

        @Getter
        String apiName;

        public VersionEntry(short apiKey, short minVersion, short maxVersion, String apiName) {
            this.apiKey = apiKey;
            this.minVersion = minVersion;
            this.maxVersion = maxVersion;
            this.apiName = apiName;
        }

        public ApiVersion toApiVersion() {
            return new ApiVersion().setApiKey(apiKey).setMinVersion(minVersion).setMaxVersion(maxVersion);
        }
    }
}

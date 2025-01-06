/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.node.config.types.StreamMode;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Max;
import com.swirlds.config.api.validation.annotation.Min;
import java.util.List;

/**
 * Configuration for the block stream.
 * @param streamMode Value of RECORDS disables the block stream; BOTH enables it
 * @param writerMode if we are writing to a file or gRPC stream
 * @param blockFileDir directory to store block files
 * @param compressFilesOnCreation whether to compress files on creation
 * @param grpcAddress the address of the gRPC server
 * @param grpcPort the port of the gRPC server
 * @param uploadRetryAttempts the number of retries to attempt if needed
 * @param localRetentionHours the time we will retain the block files locally
 * @param credentialsPath the path to the bucket credentials
 * @param buckets the buckets configuration
 */
@ConfigData("blockStream")
public record BlockStreamConfig(
        @ConfigProperty(defaultValue = "BOTH") @NetworkProperty StreamMode streamMode,
        @ConfigProperty(defaultValue = "FILE") @NodeProperty BlockStreamWriterMode writerMode,
        @ConfigProperty(defaultValue = "/opt/hgcapp/blockStreams") @NodeProperty String blockFileDir,
        @ConfigProperty(defaultValue = "true") @NetworkProperty boolean compressFilesOnCreation,
        @ConfigProperty(defaultValue = "32") @NetworkProperty int serializationBatchSize,
        @ConfigProperty(defaultValue = "32") @NetworkProperty int hashCombineBatchSize,
        @ConfigProperty(defaultValue = "1") @NetworkProperty int roundsPerBlock,
        @ConfigProperty(defaultValue = "localhost") String grpcAddress,
        @ConfigProperty(defaultValue = "8080") @Min(0) @Max(65535) int grpcPort,
        @ConfigProperty(defaultValue = "3") @NetworkProperty int uploadRetryAttempts,
        @ConfigProperty(defaultValue = "168") @NetworkProperty int localRetentionHours,
        @ConfigProperty(defaultValue = "data/config/bucket-credentials.json") @NetworkProperty String credentialsPath,

        // Bucket configurations with default AWS and GCP public buckets
        @ConfigProperty(
                        defaultValue =
                                """
        [
            {
                "name": "default-aws-bucket",
                "provider": "AWS",
                "endpoint": "https://s3.amazonaws.com",
                "region": "us-east-1",
                "bucketName": "hedera-mainnet-blocks",
                "enabled": "true"
            },
            {
                "name": "default-gcp-bucket",
                "provider": "GCP",
                "endpoint": "https://storage.googleapis.com",
                "region": "",
                "bucketName": "hedera-mainnet-blocks",
                "enabled": "true"
            }
        ]
        """)
                @NetworkProperty
                List<CloudBucketConfig> buckets) {}

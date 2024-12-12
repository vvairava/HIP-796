/*
 * Copyright (C) 2024 Hedera Hashgraph, LLC
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

package com.hedera.node.app.uploader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.hedera.node.app.blocks.impl.BucketUploadManager;
import com.hedera.node.app.uploader.credentials.CompleteBucketConfig;
import com.hedera.node.app.uploader.credentials.OnDiskBucketConfig;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.CloudBucketConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class BucketConfigurationManagerTest {
    @Mock
    private ConfigProvider configProvider;

    @Mock
    private BucketUploadManager bucketUploadManager;

    private BucketConfigurationManager subject;
    private static final String DEFAULT_BUCKETS_CONFIG =
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
        """;

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testHappyCompletionOfBucketConfigs() {
        final var config = HederaTestConfigBuilder.create().getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1L));
        subject = new BucketConfigurationManager(configProvider, bucketUploadManager);

        List<CloudBucketConfig> cloudBucketConfigs;
        try {
            cloudBucketConfigs = mapper.readValue(DEFAULT_BUCKETS_CONFIG, new TypeReference<>() {});
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        final var credentialsPath = BucketConfigurationManagerTest.class
                .getClassLoader()
                .getResourceAsStream("uploader/bucket-credentials.json");
        OnDiskBucketConfig credentials;
        try {
            credentials = mapper.readValue(credentialsPath, OnDiskBucketConfig.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        final List<CompleteBucketConfig> completeBucketConfigs =
                generateCompleteBucketConfigs(cloudBucketConfigs, credentials);
        assertThat(subject.getCompleteBucketConfigs()).isEqualTo(completeBucketConfigs);
    }

    @Test
    void failIfWrongPathOrNonExistentCredentialFile() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.credentialsPath", "non/existent/path")
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1L));

        Throwable throwable = catchThrowable(() -> {
            subject = new BucketConfigurationManager(configProvider, bucketUploadManager);
        });
        assertThat(throwable)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to load bucket credentials");
    }

    @Test
    void failIfRegionIsEmptyWhenWeUseAWSProvider() {
        final String AWS_PROVIDER_WITH_EMPTY_REGION =
                """
        [
            {
                "name": "default-aws-bucket",
                "provider": "AWS",
                "endpoint": "https://s3.amazonaws.com",
                "region": "",
                "bucketName": "hedera-mainnet-blocks",
                "enabled": "true"
            }
        ]
        """;
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.buckets", AWS_PROVIDER_WITH_EMPTY_REGION)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1L));

        subject = new BucketConfigurationManager(configProvider, bucketUploadManager);
        assertThrows(
                ValueInstantiationException.class,
                () -> mapper.readValue(AWS_PROVIDER_WITH_EMPTY_REGION, new TypeReference<List<CloudBucketConfig>>() {}),
                "region cannot be null if the provider is AWS");
    }

    private List<CompleteBucketConfig> generateCompleteBucketConfigs(
            @NonNull final List<CloudBucketConfig> cloudBucketConfigs,
            @NonNull final OnDiskBucketConfig onDiskBucketConfig) {
        return cloudBucketConfigs.stream()
                .map(bucket -> {
                    var bucketCredentials = onDiskBucketConfig.credentials().get(bucket.name());
                    return new CompleteBucketConfig(
                            bucket.name(),
                            bucket.provider(),
                            bucket.endpoint(),
                            bucket.region(),
                            bucket.bucketName(),
                            bucket.enabled(),
                            bucketCredentials);
                })
                .toList();
    }
}

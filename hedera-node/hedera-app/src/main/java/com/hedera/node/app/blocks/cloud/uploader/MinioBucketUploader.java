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

package com.hedera.node.app.blocks.cloud.uploader;

import com.hedera.node.app.uploader.credentials.CompleteBucketConfig;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.types.BucketProvider;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.UploadObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MinioBucketUploader implements CloudBucketUploader {
    private static final Logger logger = LogManager.getLogger(MinioBucketUploader.class);
    private final MinioClient minioClient;
    private final String bucketName;
    private final BucketProvider provider;
    private final ExecutorService uploadExecutor;
    private final int maxRetryAttempts;

    public MinioBucketUploader(
            ExecutorService executor, ConfigProvider configProvider, CompleteBucketConfig completeBucketConfig) {
        this.uploadExecutor = executor;
        this.bucketName = completeBucketConfig.bucketName();
        this.provider = completeBucketConfig.provider();
        this.maxRetryAttempts = configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .uploadRetryAttempts();
        this.minioClient = MinioClientFactory.createClient(completeBucketConfig);
    }

    private String calculateMD5Hash(Path filePath) throws IOException, NoSuchAlgorithmException {
        // Calculate MD5 hash
        byte[] fileBytes = Files.readAllBytes(filePath); // Read the file content as bytes
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] md5Bytes = md.digest(fileBytes);
        return Base64.getEncoder().encodeToString(md5Bytes);
    }

    @Override
    public CompletableFuture<Void> uploadBlock(Path blockPath) {
        return CompletableFuture.runAsync(
                () -> {
                    if (!Files.exists(blockPath)) {
                        throw new IllegalArgumentException("Block path does not exist: " + blockPath);
                    }
                    String fileName = blockPath.getFileName().toString();
                    String objectKey = fileName.endsWith(".blk")
                            ? fileName.replaceAll("[^\\d]", "") // Extract numeric part
                            : "";
                    try {
                        // First check if object already exists
                        if (blockExistsOnCloud(objectKey)) {
                            String existingMd5 = getBlockMd5Internal(objectKey);
                            if (existingMd5.equals(calculateMD5Hash(blockPath))) {
                                logger.debug("Block {} already exists with matching MD5", objectKey);
                                return;
                            }
                            throw new HashMismatchException(objectKey, provider.toString());
                        }
                        // Upload with retry logic
                        RetryUtils.withRetry(
                                () -> {
                                    minioClient.uploadObject(UploadObjectArgs.builder()
                                            .bucket(bucketName)
                                            .object(objectKey)
                                            .filename(blockPath.toString())
                                            .contentType("application/octet-stream")
                                            .build());
                                    return null;
                                },
                                maxRetryAttempts);
                    } catch (Exception e) {
                        throw new CompletionException("Failed to upload block " + objectKey, e);
                    }
                },
                uploadExecutor);
    }

    @Override
    public CompletableFuture<Boolean> blockExists(String objectKey) {
        return CompletableFuture.supplyAsync(() -> blockExistsOnCloud(objectKey), uploadExecutor);
    }

    @Override
    public CompletableFuture<String> getBlockMd5(String objectKey) {
        return CompletableFuture.supplyAsync(() -> getBlockMd5Internal(objectKey), uploadExecutor);
    }

    @Override
    public BucketProvider getProvider() {
        return provider;
    }

    public boolean blockExistsOnCloud(String objectKey) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            if (e.errorResponse().code().equals("NoSuchKey")) {
                return false;
            }
            throw new CompletionException(e);
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    private String getBlockMd5Internal(String objectKey) {
        try {
            var stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .build());
            return stat.etag();
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    /**
     * Utility method to zero the secret key char array by filling it with null characters after use
     * @param array the char array to clear
     */
    public void clearCharArray(char[] array) {
        if (array != null) {
            Arrays.fill(array, '\0');
        }
    }
}

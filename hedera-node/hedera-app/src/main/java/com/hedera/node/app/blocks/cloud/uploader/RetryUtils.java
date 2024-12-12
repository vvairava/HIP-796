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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RetryUtils {
    private static final Logger logger = LogManager.getLogger(RetryUtils.class);

    public static <T> T withRetry(SupplierWithException<T> operation, int maxAttempts) throws Exception {

        int attempt = 0;
        Exception lastException = null;
        while (attempt < maxAttempts) {
            try {
                return operation.get();
            } catch (Exception e) {
                lastException = e;
                attempt++;

                if (attempt == maxAttempts) {
                    break;
                }
                long backoffMillis = calculateBackoff(attempt);
                logger.warn("Attempt {} failed, retrying in {} ms", attempt, backoffMillis, e);
                Thread.sleep(backoffMillis);
            }
        }

        throw new Exception("Failed after " + maxAttempts + " attempts", lastException);
    }

    private static long calculateBackoff(int attempt) {
        // Exponential backoff with jitter: 2^n * 100ms + random(50ms)
        return (long) (Math.pow(2, attempt) * 100 + Math.random() * 50);
    }

    @FunctionalInterface
    public interface SupplierWithException<T> {
        T get() throws Exception;
    }
}

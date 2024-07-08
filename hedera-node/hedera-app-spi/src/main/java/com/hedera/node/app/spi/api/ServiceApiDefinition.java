/*
 * Copyright (C) 2023-2024 Hedera Hashgraph, LLC
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

package com.hedera.node.app.spi.api;

import com.hedera.node.app.spi.metrics.StoreMetricsService;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Definition of a service API which is used to create instances of the service API.
 *
 * @param serviceApiInterface the class of the service API
 * @param provider creates a new instance of the service API
 * @param <T> the type of the readable store
 */
public record ServiceApiDefinition<T>(@NonNull Class<T> serviceApiInterface, @NonNull ServiceApiProvider<T> provider) {

    /**
     * A provider for creating a service API
     *
     * @param <T> the type of the service API
     */
    @FunctionalInterface
    public interface ServiceApiProvider<T> {
        /**
         * Creates a new instance of the service API.
         *
         * @param configuration the node configuration
         * @param storeMetricsService Service that provides utilization metrics.
         * @param writableStates the writable state of the service
         * @return the new service API
         */
        @NonNull
        T newInstance(
                @NonNull WritableStates writableStates,
                @NonNull Configuration configuration,
                @NonNull StoreMetricsService storeMetricsService);
    }
}

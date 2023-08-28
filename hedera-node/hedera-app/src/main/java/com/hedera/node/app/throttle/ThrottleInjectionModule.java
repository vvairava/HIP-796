/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
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

package com.hedera.node.app.throttle;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;

import com.hedera.node.app.fees.congestion.MonoMultiplierSources;
import com.hedera.node.app.service.mono.fees.congestion.ThrottleMultiplierSource;
import com.hedera.node.app.throttle.impl.NetworkUtilizationManagerImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.FeesConfig;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Module
public interface ThrottleInjectionModule {
    Logger log = LogManager.getLogger(ThrottleInjectionModule.class);

    @Binds
    @Singleton
    ThrottleAccumulator bindThrottleAccumulator(ThrottleAccumulatorImpl throttleAccumulator);

    /** Provides an implementation of the {@link com.hedera.node.app.throttle.NetworkUtilizationManager}. */
    @Provides
    @Singleton
    public static NetworkUtilizationManager provideNetworkUtilizationManager(
            @NonNull final HandleThrottleAccumulator handleThrottling, @NonNull ConfigProvider configProvider) {
        final var genericFeeMultiplier = new ThrottleMultiplierSource(
                "logical TPS",
                "TPS",
                "CryptoTransfer throughput",
                log,
                () -> configProvider
                        .getConfiguration()
                        .getConfigData(FeesConfig.class)
                        .minCongestionPeriod(),
                () -> configProvider
                        .getConfiguration()
                        .getConfigData(FeesConfig.class)
                        .percentCongestionMultipliers(),
                () -> handleThrottling.activeThrottlesFor(CRYPTO_TRANSFER));
        final var gasFeeMultiplier = new ThrottleMultiplierSource(
                "EVM gas/sec",
                "gas/sec",
                "EVM utilization",
                log,
                () -> configProvider
                        .getConfiguration()
                        .getConfigData(FeesConfig.class)
                        .minCongestionPeriod(),
                () -> configProvider
                        .getConfiguration()
                        .getConfigData(FeesConfig.class)
                        .percentCongestionMultipliers(),
                () -> List.of(handleThrottling.gasLimitThrottle()));

        final var monoMultiplierSources = new MonoMultiplierSources(genericFeeMultiplier, gasFeeMultiplier);
        return new NetworkUtilizationManagerImpl(handleThrottling, monoMultiplierSources);
    }
}

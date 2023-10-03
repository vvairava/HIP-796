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

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.congestion.CongestionLevelStarts;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.node.app.spi.Service;
import com.hedera.node.app.spi.state.MigrationContext;
import com.hedera.node.app.spi.state.Schema;
import com.hedera.node.app.spi.state.SchemaRegistry;
import com.hedera.node.app.spi.state.StateDefinition;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import javax.inject.Singleton;

@SuppressWarnings("rawtypes")
@Singleton
public class CongestionThrottleService implements Service {
    public static final String NAME = "CongestionThrottleService";
    public static final String THROTTLE_USAGE_SNAPSHOTS_STATE_KEY = "THROTTLE_USAGE_SNAPSHOTS";
    public static final String CONGESTION_LEVEL_STARTS_STATE_KEY = "CONGESTION_LEVEL_STARTS";
    private static final SemanticVersion GENESIS_VERSION = SemanticVersion.DEFAULT;

    @NonNull
    @Override
    public String getServiceName() {
        return NAME;
    }

    @Override
    public void registerSchemas(@NonNull SchemaRegistry registry) {
        registry.register(new Schema(GENESIS_VERSION) {
            /** {@inheritDoc} */
            @NonNull
            @Override
            public Set<StateDefinition> statesToCreate() {
                return Set.of(
                        StateDefinition.singleton(THROTTLE_USAGE_SNAPSHOTS_STATE_KEY, ThrottleUsageSnapshots.PROTOBUF),
                        StateDefinition.singleton(CONGESTION_LEVEL_STARTS_STATE_KEY, CongestionLevelStarts.PROTOBUF));
            }

            /** {@inheritDoc} */
            @Override
            public void migrate(@NonNull final MigrationContext ctx) {
                final var throttleSnapshotsState = ctx.newStates().getSingleton(THROTTLE_USAGE_SNAPSHOTS_STATE_KEY);
                throttleSnapshotsState.put(ThrottleUsageSnapshots.DEFAULT);

                final var congestionLevelStartsState = ctx.newStates().getSingleton(CONGESTION_LEVEL_STARTS_STATE_KEY);
                congestionLevelStartsState.put(CongestionLevelStarts.DEFAULT);
            }
        });
    }
}

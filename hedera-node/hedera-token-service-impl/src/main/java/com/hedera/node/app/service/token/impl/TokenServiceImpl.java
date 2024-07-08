/*
 * Copyright (C) 2020-2024 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.token.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNetworkStakingRewardsStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableStakingInfoStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.service.token.impl.api.TokenServiceApiProvider;
import com.hedera.node.app.service.token.impl.schemas.SyntheticAccountCreator;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.service.token.impl.schemas.V0500TokenSchema;
import com.hedera.node.app.spi.api.ServiceApiDefinition;
import com.hedera.node.app.spi.store.ReadableStoreDefinition;
import com.hedera.node.app.spi.store.WritableStoreDefinition;
import com.swirlds.state.spi.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.ZoneId;
import java.util.Set;

/** An implementation of the {@link TokenService} interface. */
public class TokenServiceImpl implements TokenService {
    public static final long THREE_MONTHS_IN_SECONDS = 7776000L;
    public static final long MAX_SERIAL_NO_ALLOWED = 0xFFFFFFFFL;
    public static final long HBARS_TO_TINYBARS = 100_000_000L;
    public static final String AUTO_MEMO = "auto-created account";
    public static final String LAZY_MEMO = "lazy-created account";
    public static final ZoneId ZONE_UTC = ZoneId.of("UTC");

    public TokenServiceImpl() {
        // No-op
    }

    @Override
    public Set<ServiceApiDefinition<?>> serviceApiDefinitions() {
        return Set.of(
                new ServiceApiDefinition<>(TokenServiceApi.class, TokenServiceApiProvider.TOKEN_SERVICE_API_PROVIDER));
    }

    @Override
    public Set<ReadableStoreDefinition<?>> readableStoreDefinitions() {
        return Set.of(
                new ReadableStoreDefinition<>(ReadableAccountStore.class, ReadableAccountStoreImpl::new),
                new ReadableStoreDefinition<>(ReadableNftStore.class, ReadableNftStoreImpl::new),
                new ReadableStoreDefinition<>(ReadableStakingInfoStore.class, ReadableStakingInfoStoreImpl::new),
                new ReadableStoreDefinition<>(ReadableTokenStore.class, ReadableTokenStoreImpl::new),
                new ReadableStoreDefinition<>(ReadableTokenRelationStore.class, ReadableTokenRelationStoreImpl::new),
                new ReadableStoreDefinition<>(
                        ReadableNetworkStakingRewardsStore.class, ReadableNetworkStakingRewardsStoreImpl::new));
    }

    @Override
    public Set<WritableStoreDefinition<?>> writableStoreDefinitions() {
        return Set.of(
                new WritableStoreDefinition<>(WritableAccountStore.class, WritableAccountStore::new),
                new WritableStoreDefinition<>(WritableNftStore.class, WritableNftStore::new),
                new WritableStoreDefinition<>(
                        WritableStakingInfoStore.class,
                        (states, config, metrics) -> new WritableStakingInfoStore(states)),
                new WritableStoreDefinition<>(WritableTokenStore.class, WritableTokenStore::new),
                new WritableStoreDefinition<>(WritableTokenRelationStore.class, WritableTokenRelationStore::new),
                new WritableStoreDefinition<>(
                        WritableNetworkStakingRewardsStore.class,
                        (states, config, metrics) -> new WritableNetworkStakingRewardsStore(states)));
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        requireNonNull(registry);
        registry.register(new V0490TokenSchema(new SyntheticAccountCreator()));
        registry.register(new V0500TokenSchema());
    }
}

/*
 * Copyright (C) 2020-2023 Hedera Hashgraph, LLC
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

package com.hedera.node.app.service.network.impl;

import com.hedera.node.app.service.mono.state.merkle.MerkleStakingInfo;
import com.hedera.node.app.service.mono.utils.EntityNum;
import com.hedera.node.app.service.network.NetworkService;
import com.hedera.node.app.service.network.impl.handlers.NetworkGetAccountDetailsHandler;
import com.hedera.node.app.service.network.impl.handlers.NetworkGetByKeyHandler;
import com.hedera.node.app.service.network.impl.handlers.NetworkGetExecutionTimeHandler;
import com.hedera.node.app.service.network.impl.handlers.NetworkGetVersionInfoHandler;
import com.hedera.node.app.service.network.impl.handlers.NetworkTransactionGetReceiptHandler;
import com.hedera.node.app.service.network.impl.handlers.NetworkTransactionGetRecordHandler;
import com.hedera.node.app.service.network.impl.handlers.NetworkUncheckedSubmitHandler;
import com.hedera.node.app.service.network.impl.serdes.EntityNumSerdes;
import com.hedera.node.app.service.network.impl.serdes.MonoContextAdapterSerdes;
import com.hedera.node.app.service.network.impl.serdes.MonoRunningHashesAdapterSerdes;
import com.hedera.node.app.service.network.impl.serdes.MonoSpecialFilesAdapterSerdes;
import com.hedera.node.app.spi.service.Service;
import com.hedera.node.app.spi.state.Schema;
import com.hedera.node.app.spi.state.SchemaRegistry;
import com.hedera.node.app.spi.state.StateDefinition;
import com.hedera.node.app.spi.state.serdes.MonoMapSerdesAdapter;
import com.hedera.node.app.spi.workflows.QueryHandler;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hederahashgraph.api.proto.java.SemanticVersion;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Standard implementation of the {@link NetworkService} {@link Service}.
 */
public final class NetworkServiceImpl implements NetworkService {

    public static final String CONTEXT_KEY = "CONTEXT";
    public static final String STAKING_KEY = "STAKING";
    public static final String SPECIAL_FILES_KEY = "SPECIAL_FILES";
    public static final String RUNNING_HASHES_KEY = "RUNNING_HASHES";
    private static final SemanticVersion CURRENT_VERSION =
            SemanticVersion.newBuilder().setMinor(34).build();

    private final NetworkGetAccountDetailsHandler networkGetAccountDetailsHandler;

    private final NetworkGetByKeyHandler networkGetByKeyHandler;

    private final NetworkGetExecutionTimeHandler networkGetExecutionTimeHandler;

    private final NetworkGetVersionInfoHandler networkGetVersionInfoHandler;

    private final NetworkTransactionGetReceiptHandler networkTransactionGetReceiptHandler;

    private final NetworkTransactionGetRecordHandler networkTransactionGetRecordHandler;

    private final NetworkUncheckedSubmitHandler networkUncheckedSubmitHandler;

    /**
     * Creates a new {@link NetworkServiceImpl} instance.
     */
    public NetworkServiceImpl() {
        this.networkGetAccountDetailsHandler = new NetworkGetAccountDetailsHandler();
        this.networkGetByKeyHandler = new NetworkGetByKeyHandler();
        this.networkGetExecutionTimeHandler = new NetworkGetExecutionTimeHandler();
        this.networkGetVersionInfoHandler = new NetworkGetVersionInfoHandler();
        this.networkTransactionGetReceiptHandler = new NetworkTransactionGetReceiptHandler();
        this.networkTransactionGetRecordHandler = new NetworkTransactionGetRecordHandler();
        this.networkUncheckedSubmitHandler = new NetworkUncheckedSubmitHandler();
    }

    /**
     * Returns the {@link NetworkGetAccountDetailsHandler} instance.
     *
     * @return the {@link NetworkGetAccountDetailsHandler} instance.
     */
    @NonNull
    public NetworkGetAccountDetailsHandler getNetworkGetAccountDetailsHandler() {
        return networkGetAccountDetailsHandler;
    }

    /**
     * Returns the {@link NetworkGetByKeyHandler} instance.
     *
     * @return the {@link NetworkGetByKeyHandler} instance.
     */
    @NonNull
    public NetworkGetByKeyHandler getNetworkGetByKeyHandler() {
        return networkGetByKeyHandler;
    }

    /**
     * Returns the {@link NetworkGetExecutionTimeHandler} instance.
     *
     * @return the {@link NetworkGetExecutionTimeHandler} instance.
     */
    @NonNull
    public NetworkGetExecutionTimeHandler getNetworkGetExecutionTimeHandler() {
        return networkGetExecutionTimeHandler;
    }

    /**
     * Returns the {@link NetworkGetVersionInfoHandler} instance.
     *
     * @return the {@link NetworkGetVersionInfoHandler} instance.
     */
    @NonNull
    public NetworkGetVersionInfoHandler getNetworkGetVersionInfoHandler() {
        return networkGetVersionInfoHandler;
    }

    /**
     * Returns the {@link NetworkTransactionGetReceiptHandler} instance.
     *
     * @return the {@link NetworkTransactionGetReceiptHandler} instance.
     */
    @NonNull
    public NetworkTransactionGetReceiptHandler getNetworkTransactionGetReceiptHandler() {
        return networkTransactionGetReceiptHandler;
    }

    /**
     * Returns the {@link NetworkTransactionGetRecordHandler} instance.
     *
     * @return the {@link NetworkTransactionGetRecordHandler} instance.
     */
    @NonNull
    public NetworkTransactionGetRecordHandler getNetworkTransactionGetRecordHandler() {
        return networkTransactionGetRecordHandler;
    }

    /**
     * Returns the {@link NetworkUncheckedSubmitHandler} instance.
     *
     * @return the {@link NetworkUncheckedSubmitHandler} instance.
     */
    @NonNull
    public NetworkUncheckedSubmitHandler getNetworkUncheckedSubmitHandler() {
        return networkUncheckedSubmitHandler;
    }

    @NonNull
    @Override
    public Set<TransactionHandler> getTransactionHandler() {
        return Set.of(networkUncheckedSubmitHandler);
    }

    @NonNull
    @Override
    public Set<QueryHandler> getQueryHandler() {
        return Set.of(
                networkGetAccountDetailsHandler,
                networkGetByKeyHandler,
                networkGetExecutionTimeHandler,
                networkGetVersionInfoHandler,
                networkTransactionGetReceiptHandler,
                networkTransactionGetRecordHandler);
    }

    @Override
    public void registerSchemas(final @NonNull SchemaRegistry registry) {
        registry.register(networkSchema());
    }

    private Schema networkSchema() {
        return new Schema(CURRENT_VERSION) {
            @NonNull
            @Override
            public Set<StateDefinition> statesToCreate() {
                return Set.of(
                        stakingDef(),
                        StateDefinition.singleton(CONTEXT_KEY, new MonoContextAdapterSerdes()),
                        StateDefinition.singleton(SPECIAL_FILES_KEY, new MonoSpecialFilesAdapterSerdes()),
                        StateDefinition.singleton(RUNNING_HASHES_KEY, new MonoRunningHashesAdapterSerdes()));
            }
        };
    }

    private StateDefinition<EntityNum, MerkleStakingInfo> stakingDef() {
        final var keySerdes = new EntityNumSerdes();
        final var valueSerdes = MonoMapSerdesAdapter.serdesForSelfSerializable(
                MerkleStakingInfo.CURRENT_VERSION, MerkleStakingInfo::new);
        return StateDefinition.inMemory(STAKING_KEY, keySerdes, valueSerdes);
    }
}

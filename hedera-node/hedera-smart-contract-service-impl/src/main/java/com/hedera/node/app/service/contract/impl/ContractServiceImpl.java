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

package com.hedera.node.app.service.contract.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.DefaultVerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategies;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.handlers.ContractHandlers;
import com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema;
import com.hedera.node.app.service.contract.impl.schemas.V0500ContractSchema;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.hyperledger.besu.evm.tracing.OperationTracer;

/**
 * Implementation of the {@link ContractService}.
 */
public class ContractServiceImpl implements ContractService {

    /**
     * Minimum gas required for contract operations.
     */
    public static final long INTRINSIC_GAS_LOWER_BOUND = 21_000L;

    private final ContractServiceComponent component;

    /**
     * @param appContext the current application context
     */
    public ContractServiceImpl(@NonNull final AppContext appContext) {
        this(appContext, null, null);
    }

    /**
     * @param appContext the current application context
     * @param verificationStrategies the current verification strategy used
     * @param addOnTracers all operation tracer callbacks
     */
    public ContractServiceImpl(
            @NonNull final AppContext appContext,
            @Nullable final VerificationStrategies verificationStrategies,
            @Nullable final Supplier<List<OperationTracer>> addOnTracers) {
        requireNonNull(appContext);
        final var metricsSupplier = requireNonNull(appContext.metricsSupplier());
        final Supplier<ContractsConfig> contractsConfigSupplier =
                () -> appContext.configSupplier().get().getConfigData(ContractsConfig.class);
        final var systemContractMethodRegistry = new SystemContractMethodRegistry();
        final var contractMetrics =
                new ContractMetrics(metricsSupplier, contractsConfigSupplier, systemContractMethodRegistry);

        this.component = DaggerContractServiceComponent.factory()
                .create(
                        appContext.instantSource(),
                        // (FUTURE) Inject the signature verifier instance into the IsAuthorizedSystemContract
                        // C.f. https://github.com/hashgraph/hedera-services/issues/14248
                        appContext.signatureVerifier(),
                        Optional.ofNullable(verificationStrategies).orElseGet(DefaultVerificationStrategies::new),
                        addOnTracers,
                        contractMetrics,
                        systemContractMethodRegistry);
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        registry.register(new V0490ContractSchema());
        registry.register(new V0500ContractSchema());
    }

    @Override
    public void onStateInitializedForGenesis() {
        // Force call translators to be instantiated now, so that all the system contract methods
        // will be registered, so the secondary metrics can be created.  (Left to its own devices
        // Dagger would delay instantiating them until transactions started flowing.)
        final var allTranslators = allCallTranslators();

        component.contractMetrics().createContractPrimaryMetrics();
        component.contractMetrics().createContractSecondaryMetrics();
    }

    /**
     * @return all contract transaction handlers
     */
    public ContractHandlers handlers() {
        return component.handlers();
    }

    private @NonNull List<CallTranslator<? extends AbstractCallAttempt<?>>> allCallTranslators() {
        final var allCallTranslators = new ArrayList<CallTranslator<? extends AbstractCallAttempt<?>>>();
        allCallTranslators.addAll(component.hasCallTranslators().get());
        allCallTranslators.addAll(component.hssCallTranslators().get());
        allCallTranslators.addAll(component.htsCallTranslators().get());
        return allCallTranslators;
    }
}

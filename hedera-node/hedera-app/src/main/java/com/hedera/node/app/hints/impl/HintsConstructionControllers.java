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

package com.hedera.node.app.hints.impl;

import static com.hedera.node.app.hints.HintsService.partySizeForRosterNodeCount;
import static com.hedera.node.app.hints.impl.HintsConstructionController.Urgency.HIGH;
import static com.hedera.node.app.hints.impl.HintsConstructionController.Urgency.LOW;
import static com.hedera.node.app.hints.impl.HintsModule.weightsFrom;
import static java.util.Objects.requireNonNull;

import com.hedera.cryptography.bls.BlsPublicKey;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.node.app.hints.HintsOperations;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.HintsSubmissions;
import com.hedera.node.app.hints.ReadableHintsStore;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.service.ReadableRosterStore;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Logic to get or create a {@link HintsConstructionController} for source/target roster hashes
 * relative to the current {@link HintsService} state and consensus time. We only support one
 * controller at a time, so if this class detects that a new controller is needed, it will cancel
 * any existing controller via {@link HintsConstructionController#cancelPendingWork()} and release its
 * reference to the cancelled controller.
 */
@Singleton
public class HintsConstructionControllers {
    private static final long NO_CONSTRUCTION_ID = -1L;

    private final Executor executor;
    private final HintsKeyAccessor keyLoader;
    private final HintsOperations operations;
    private final HintsSubmissions submissions;
    private final HintsSigningContext signingContext;
    private final Supplier<NodeInfo> selfNodeInfoSupplier;
    private final Supplier<Configuration> configSupplier;
    private final Function<Bytes, BlsPublicKey> keyParser;

    /**
     * May be null if the node has just started, or if the network has completed the most up-to-date
     * construction implied by its roster store.
     */
    @Nullable
    private HintsConstructionController controller;

    @Inject
    public HintsConstructionControllers(
            @NonNull final Executor executor,
            @NonNull final HintsKeyAccessor keyLoader,
            @NonNull final HintsOperations operations,
            @NonNull final HintsSubmissions submissions,
            @NonNull final HintsSigningContext signingContext,
            @NonNull final Supplier<NodeInfo> selfNodeInfoSupplier,
            @NonNull final Supplier<Configuration> configSupplier,
            @NonNull final Function<Bytes, BlsPublicKey> keyParser) {
        this.executor = requireNonNull(executor);
        this.keyLoader = requireNonNull(keyLoader);
        this.signingContext = signingContext;
        this.keyParser = requireNonNull(keyParser);
        this.operations = requireNonNull(operations);
        this.submissions = requireNonNull(submissions);
        this.configSupplier = requireNonNull(configSupplier);
        this.selfNodeInfoSupplier = requireNonNull(selfNodeInfoSupplier);
    }

    /**
     * Creates a new controller for the given hinTS construction, sourcing its rosters from the given store.
     *
     * @param construction the hinTS construction
     * @param rosterStore the store to source rosters from
     * @return the result of the operation
     */
    public @NonNull HintsConstructionController getOrCreateControllerFor(
            @NonNull final HintsConstruction construction,
            @NonNull final ReadableHintsStore hintsStore,
            @NonNull final ReadableRosterStore rosterStore) {
        requireNonNull(construction);
        requireNonNull(rosterStore);
        if (currentConstructionId() != construction.constructionId()) {
            if (controller != null) {
                controller.cancelPendingWork();
            }
            controller = newControllerFor(construction, hintsStore, rosterStore);
        }
        return requireNonNull(controller);
    }

    /**
     * Returns the in-progress controller for the hinTS construction with the given ID, if it exists.
     *
     * @param constructionId the ID of the hinTS construction
     * @return the controller, if it exists
     */
    public Optional<HintsConstructionController> getInProgressById(final long constructionId) {
        return currentConstructionId() == constructionId
                ? Optional.ofNullable(controller).filter(HintsConstructionController::isStillInProgress)
                : Optional.empty();
    }

    /**
     * Returns the in-progress controller for the hinTS construction with the given universe size, if it exists.
     *
     * @param k the log2 of the universe size
     * @return the controller, if it exists
     */
    public Optional<HintsConstructionController> getInProgressByUniverseSizeLog2(final int k) {
        return Optional.ofNullable(controller).filter(c -> c.hasLog2UniverseSize(k));
    }

    private HintsConstructionController newControllerFor(
            @NonNull final HintsConstruction construction,
            @NonNull final ReadableHintsStore hintsStore,
            @NonNull final ReadableRosterStore rosterStore) {
        final var isGenesisController =
                Objects.equals(construction.sourceRosterHash(), construction.targetRosterHash());
        final var urgency = isGenesisController ? HIGH : LOW;
        final var networkAdminConfig = configSupplier.get().getConfigData(NetworkAdminConfig.class);
        final var hintKeysWaitTime =
                switch (urgency) {
                    case HIGH -> networkAdminConfig.urgentHintsKeysWaitPeriod();
                    case LOW -> networkAdminConfig.relaxedHintsKeysWaitPeriod();
                };
        final var sourceNodeWeights = weightsFrom(requireNonNull(rosterStore.get(construction.sourceRosterHash())));
        final var targetNodeWeights = isGenesisController
                ? sourceNodeWeights
                : weightsFrom(requireNonNull(rosterStore.get(construction.targetRosterHash())));
        final int k = Integer.numberOfTrailingZeros(partySizeForRosterNodeCount(targetNodeWeights.size()));
        final var blsKeyPair = keyLoader.getOrCreateBlsKeyPair(construction.constructionId());
        final var publications = hintsStore.publicationsForMaxSizeLog2(k, targetNodeWeights.keySet());
        final var votes = hintsStore.votesFor(construction.constructionId(), sourceNodeWeights.keySet());
        return new HintsConstructionController(
                selfNodeInfoSupplier.get().nodeId(),
                urgency,
                executor,
                blsKeyPair,
                hintKeysWaitTime,
                operations,
                sourceNodeWeights,
                targetNodeWeights,
                construction,
                publications,
                votes,
                submissions,
                signingContext,
                keyParser);
    }

    private long currentConstructionId() {
        return controller != null ? controller.constructionId() : NO_CONSTRUCTION_ID;
    }
}

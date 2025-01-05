/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.hints;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.node.app.hints.handlers.HintsHandlers;
import com.hedera.node.app.hints.schemas.V059HintsSchema;
import com.hedera.node.app.roster.ActiveRosters;
import com.hedera.node.app.spi.AppContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Default implementation of the {@link HintsService}.
 */
public class HintsServiceImpl implements HintsService {
    private static final Logger logger = LogManager.getLogger(HintsServiceImpl.class);

    private final HintsServiceComponent component;

    public HintsServiceImpl(
            @NonNull final Metrics metrics,
            @NonNull final Executor executor,
            @NonNull final AppContext appContext,
            @NonNull final HintsLibrary operations) {
        this.component = DaggerHintsServiceComponent.factory().create(operations, appContext, executor, metrics);
    }

    @VisibleForTesting
    public HintsServiceImpl(@NonNull final HintsServiceComponent component) {
        this.component = requireNonNull(component);
    }

    @Override
    public CompletableFuture<Bytes> signFuture(@NonNull final Bytes blockHash) {
        if (!isReady()) {
            throw new IllegalStateException("hinTS service not ready to sign block hash " + blockHash);
        }
        final var signing = component.signings().computeIfAbsent(blockHash, component.signingContext()::newSigning);
        component.submissions().submitPartialSignature(blockHash).exceptionally(t -> {
            logger.warn("Failed to submit partial signature for block hash {}", blockHash, t);
            return null;
        });
        return signing.future();
    }

    @Override
    public boolean isReady() {
        return component.signingContext().isReady();
    }

    @Override
    public @NonNull Bytes activeVerificationKeyOrThrow() {
        return component.signingContext().activeVerificationKeyOrThrow();
    }

    @Override
    public HintsHandlers handlers() {
        return component.handlers();
    }

    @Override
    public void stop() {
        // TODO
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        requireNonNull(registry);
        registry.register(new V059HintsSchema(component.signingContext()));
    }

    @Override
    public void reconcile(
            @NonNull final ActiveRosters activeRosters,
            @NonNull final WritableHintsStore hintsStore,
            @NonNull final Instant now) {
        requireNonNull(now);
        requireNonNull(activeRosters);
        requireNonNull(hintsStore);
        switch (activeRosters.phase()) {
            case BOOTSTRAP, TRANSITION -> {
                final var construction = hintsStore.getOrCreateConstructionFor(activeRosters, now);
                if (!construction.hasPreprocessedKeys()) {
                    final var controller =
                            component.controllers().getOrCreateFor(activeRosters, construction, hintsStore);
                    controller.advanceConstruction(now, hintsStore);
                }
            }
            case HANDOFF -> {
                if (hintsStore.purgeStateAfterHandoff(activeRosters)) {
                    component
                            .signingContext()
                            .setConstruction(requireNonNull(hintsStore.getConstructionFor(activeRosters)));
                }
            }
        }
    }
}

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

package com.hedera.node.app.tss;

import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.node.app.tss.TssBaseService.Status.PENDING_LEDGER_ID;
import static com.hedera.node.app.tss.handlers.TssUtils.computeTssParticipantDirectory;
import static com.hedera.node.app.tss.handlers.TssUtils.getTssMessages;
import static com.hedera.node.app.tss.handlers.TssUtils.validateTssMessages;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.services.auxiliary.tss.TssMessageTransactionBody;
import com.hedera.node.app.roster.ReadableRosterStore;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.tss.api.TssLibrary;
import com.hedera.node.app.tss.handlers.TssHandlers;
import com.hedera.node.app.tss.handlers.TssSubmissions;
import com.hedera.node.app.tss.schemas.V0560TssBaseSchema;
import com.hedera.node.app.tss.stores.ReadableTssBaseStore;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.CommonUtils;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.state.spi.SchemaRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Default implementation of the {@link TssBaseService}.
 */
public class TssBaseServiceImpl implements TssBaseService {
    private static final Logger log = LogManager.getLogger(TssBaseServiceImpl.class);

    /**
     * Copy-on-write list to avoid concurrent modification exceptions if a consumer unregisters
     * itself in its callback.
     */
    private final List<BiConsumer<byte[], byte[]>> consumers = new CopyOnWriteArrayList<>();

    private final TssHandlers tssHandlers;
    private final TssSubmissions tssSubmissions;
    private final ExecutorService signingExecutor;
    private final TssLibrary tssLibrary;

    /**
     * The hash of the active roster being used to sign with the ledger private key.
     */
    @Nullable
    private Bytes activeRosterHash;

    public TssBaseServiceImpl(
            @NonNull final AppContext appContext,
            @NonNull final ExecutorService signingExecutor,
            @NonNull final Executor submissionExecutor,
            @NonNull final TssLibrary tssLibrary) {
        requireNonNull(appContext);
        this.signingExecutor = requireNonNull(signingExecutor);
        final var component = DaggerTssBaseServiceComponent.factory().create(appContext.gossip(), submissionExecutor);
        tssHandlers = new TssHandlers(component.tssMessageHandler(), component.tssVoteHandler());
        tssSubmissions = component.tssSubmissions();
        this.tssLibrary = requireNonNull(tssLibrary);
    }

    @Override
    public void registerSchemas(@NonNull final SchemaRegistry registry) {
        requireNonNull(registry);
        registry.register(new V0560TssBaseSchema());
    }

    @Override
    public Status getStatus(
            @NonNull final Roster roster,
            @NonNull final Bytes ledgerId,
            @NonNull final ReadableTssBaseStore tssBaseStore) {
        requireNonNull(roster);
        requireNonNull(ledgerId);
        requireNonNull(tssBaseStore);
        // (TSS-FUTURE) Determine if the given ledger id can be recovered from the key material for the given roster
        return PENDING_LEDGER_ID;
    }

    @Override
    public void adopt(@NonNull final Roster roster) {
        requireNonNull(roster);
        activeRosterHash = RosterUtils.hash(roster).getBytes();
    }

    @Override
    public void bootstrapLedgerId(
            @NonNull final Roster roster,
            @NonNull final HandleContext context,
            @NonNull final Consumer<Bytes> ledgerIdConsumer) {
        requireNonNull(roster);
        requireNonNull(context);
        requireNonNull(ledgerIdConsumer);
        // (TSS-FUTURE) Create a real ledger id
        ledgerIdConsumer.accept(Bytes.EMPTY);
    }

    @Override
    public void setCandidateRoster(@NonNull final Roster roster, @NonNull final HandleContext context) {
        requireNonNull(roster);
        // (TSS-FUTURE) https://github.com/hashgraph/hedera-services/issues/14748

        final var sourceRoster =
                context.storeFactory().readableStore(ReadableRosterStore.class).getActiveRoster();
        final var tssStore = context.storeFactory().writableStore(ReadableTssBaseStore.class);
        final var maxSharesPerNode =
                context.configuration().getConfigData(TssConfig.class).maxSharesPerNode();
        final var sourceRosterHash = RosterUtils.hash(sourceRoster).getBytes();
        final var targetRosterHash = RosterUtils.hash(roster).getBytes();

        final var tssParticipantDirectory = computeTssParticipantDirectory(roster, maxSharesPerNode);
        final var validTssOps =
                validateTssMessages(tssStore.getTssMessages(targetRosterHash), tssParticipantDirectory, tssLibrary);
        final var validTssMessages = getTssMessages(validTssOps);
        final var tssPrivateShares = tssLibrary.decryptPrivateShares(tssParticipantDirectory, validTssMessages);

        int shareIndex = 0;
        for (final var tssPrivateShare : tssPrivateShares) {
            final var tssMsg = tssLibrary.generateTssMessage(tssParticipantDirectory, tssPrivateShare);
            final var tssMessage = TssMessageTransactionBody.newBuilder()
                    .sourceRosterHash(sourceRosterHash)
                    .targetRosterHash(targetRosterHash)
                    .shareIndex(shareIndex++)
                    .tssMessage(Bytes.wrap(tssMsg.bytes()))
                    .build();
            tssSubmissions.submitTssMessage(tssMessage, context);
        }
    }

    @Override
    public void requestLedgerSignature(@NonNull final byte[] messageHash) {
        requireNonNull(messageHash);
        // (TSS-FUTURE) Initiate asynchronous process of creating a ledger signature
        final var mockSignature = noThrowSha384HashOf(messageHash);
        CompletableFuture.runAsync(
                () -> consumers.forEach(consumer -> {
                    try {
                        consumer.accept(messageHash, mockSignature);
                    } catch (Exception e) {
                        log.error(
                                "Failed to provide signature {} on message {} to consumer {}",
                                CommonUtils.hex(mockSignature),
                                CommonUtils.hex(messageHash),
                                consumer,
                                e);
                    }
                }),
                signingExecutor);
    }

    @Override
    public void registerLedgerSignatureConsumer(@NonNull final BiConsumer<byte[], byte[]> consumer) {
        requireNonNull(consumer);
        consumers.add(consumer);
    }

    @Override
    public void unregisterLedgerSignatureConsumer(@NonNull final BiConsumer<byte[], byte[]> consumer) {
        requireNonNull(consumer);
        consumers.remove(consumer);
    }

    @Override
    public TssHandlers tssHandlers() {
        return tssHandlers;
    }
}

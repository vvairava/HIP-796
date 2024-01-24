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

package com.hedera.services.cli.signedstate;

import static com.hedera.node.app.service.token.impl.TokenServiceImpl.TOKENS_KEY;
import static com.hedera.services.cli.utils.ThingsToStrings.getMaybeStringifyByteString;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;

import com.google.common.collect.ComparisonChain;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.node.app.service.mono.state.merkle.MerkleUniqueToken;
import com.hedera.node.app.service.mono.state.migration.UniqueTokensMigrator;
import com.hedera.node.app.service.mono.state.submerkle.EntityId;
import com.hedera.node.app.service.mono.state.submerkle.RichInstant;
import com.hedera.node.app.service.mono.state.virtual.UniqueTokenKey;
import com.hedera.node.app.service.mono.state.virtual.UniqueTokenValue;
import com.hedera.node.app.service.mono.utils.EntityNumPair;
import com.hedera.node.app.service.mono.utils.NftNumPair;
import com.hedera.node.app.service.mono.utils.NonAtomicReference;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.schemas.InitialModServiceTokenSchema;
import com.hedera.node.app.spi.state.WritableKVState;
import com.hedera.node.app.state.merkle.StateMetadata;
import com.hedera.node.app.state.merkle.memory.InMemoryKey;
import com.hedera.node.app.state.merkle.memory.InMemoryValue;
import com.hedera.node.app.state.merkle.memory.InMemoryWritableKVState;
import com.hedera.services.cli.signedstate.DumpStateCommand.EmitSummary;
import com.hedera.services.cli.signedstate.SignedStateCommand.Verbosity;
import com.hedera.services.cli.utils.ThingsToStrings;
import com.hedera.services.cli.utils.Writer;
import com.swirlds.base.utility.Pair;
import com.swirlds.merkle.map.MerkleMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Dump all unique (serial-numbered) tokens, from a signed state file, to a text file, in deterministic order.
 */
@SuppressWarnings({"java:S106"})
// S106: "use of system.out/system.err instead of logger" - not needed/desirable for CLI tool
public class DumpUniqueTokensSubcommand {
    private SemanticVersion CURRENT_VERSION = new SemanticVersion(0, 47, 0, "SNAPSHOT", "");

    static void doit(
            @NonNull final SignedStateHolder state,
            @NonNull final Path uniquesPath,
            @NonNull final EmitSummary emitSummary,
            @NonNull final DumpStateCommand.WithMigration withMigration,
            @NonNull final DumpStateCommand.WithValidation withValidation,
            @NonNull final Verbosity verbosity) {
        new DumpUniqueTokensSubcommand(state, uniquesPath, emitSummary, withMigration, withValidation, verbosity)
                .doit();
    }

    @NonNull
    final SignedStateHolder state;

    @NonNull
    final Path uniquesPath;

    @NonNull
    final Verbosity verbosity;

    @NonNull
    final EmitSummary emitSummary;

    @NonNull
    final DumpStateCommand.WithMigration withMigration;

    @NonNull
    final DumpStateCommand.WithValidation withValidation;

    DumpUniqueTokensSubcommand(
            @NonNull final SignedStateHolder state,
            @NonNull final Path uniquesPath,
            @NonNull final EmitSummary emitSummary,
            @NonNull final DumpStateCommand.WithMigration withMigration,
            @NonNull final DumpStateCommand.WithValidation withValidation,
            @NonNull final Verbosity verbosity) {
        this.state = state;
        this.uniquesPath = uniquesPath;
        this.emitSummary = emitSummary;
        this.withMigration = withMigration;
        this.withValidation = withValidation;
        this.verbosity = verbosity;
    }

    void doit() {
        final var uniquesStore = state.getUniqueNFTTokens();
        System.out.printf(
                "=== %d unique tokens (%s) ===%n",
                uniquesStore.size(), uniquesStore.isVirtual() ? "virtual" : "merkle");
        final var uniques =
                (withMigration == DumpStateCommand.WithMigration.NO) ? gatherUniques() : gatherMigratedUniques();
        System.out.printf("    %d unique tokens gathered%n", uniques.size());

        int reportSize;
        try (@NonNull final var writer = new Writer(uniquesPath)) {
            if (emitSummary == EmitSummary.YES) reportSummary(writer, uniques);
            reportOnUniques(writer, uniques);
            reportSize = writer.getSize();
        }

        System.out.printf("=== uniques report is %d bytes%n", reportSize);
    }

    record UniqueNFTId(long id, long serial) implements Comparable<UniqueNFTId> {

        static UniqueNFTId from(@NonNull final UniqueTokenKey ukey) {
            return new UniqueNFTId(ukey.getNum(), ukey.getTokenSerial());
        }

        static UniqueNFTId from(@NonNull final EntityNumPair enp) {
            return new UniqueNFTId(enp.getHiOrderAsLong(), enp.getLowOrderAsLong());
        }

        static UniqueNFTId from(@NonNull final NftID id) {
            return new UniqueNFTId(id.tokenId().tokenNum(), id.serialNumber());
        }

        @Override
        public String toString() {
            return "%d%s%d".formatted(id, FIELD_SEPARATOR, serial);
        }

        @Override
        public int compareTo(UniqueNFTId o) {
            return ComparisonChain.start()
                    .compare(this.id, o.id)
                    .compare(this.serial, o.serial)
                    .result();
        }
    }

    @SuppressWarnings(
            "java:S6218") // "Equals/hashcode method should be overridden in records containing array fields" - this
    // record will never be compared or used as a key
    record UniqueNFT(
            EntityId owner,
            EntityId spender,
            @NonNull RichInstant creationTime,
            @NonNull byte[] metadata,
            @NonNull NftNumPair previous,
            @NonNull NftNumPair next) {

        static final byte[] EMPTY_BYTES = new byte[0];

        static UniqueNFT from(@NonNull final UniqueTokenValue utv) {
            return new UniqueNFT(
                    utv.getOwner(),
                    utv.getSpender(),
                    utv.getCreationTime(),
                    null != utv.getMetadata() ? utv.getMetadata() : EMPTY_BYTES,
                    utv.getPrev(),
                    utv.getNext());
        }

        static UniqueNFT from(@NonNull final MerkleUniqueToken mut) {
            return new UniqueNFT(
                    mut.getOwner(),
                    mut.getSpender(),
                    mut.getCreationTime(),
                    null != mut.getMetadata() ? mut.getMetadata() : EMPTY_BYTES,
                    mut.getPrev(),
                    mut.getNext());
        }

        static UniqueNFT from(@NonNull final Nft nft) {
            return new UniqueNFT(
                    EntityId.fromPbjAccountId(nft.ownerId()),
                    EntityId.fromPbjAccountId(nft.spenderId()),
                    new RichInstant(nft.mintTime().seconds(), nft.mintTime().nanos()),
                    null != nft.metadata() ? nft.metadata().toByteArray() : EMPTY_BYTES,
                    NftNumPair.fromLongs(
                            nft.ownerPreviousNftId().tokenId().tokenNum(),
                            nft.ownerPreviousNftId().serialNumber()),
                    NftNumPair.fromLongs(
                            nft.ownerNextNftId().tokenId().tokenNum(),
                            nft.ownerNextNftId().serialNumber()));
        }
    }

    void reportSummary(@NonNull final Writer writer, @NonNull final Map<UniqueNFTId, UniqueNFT> uniques) {
        final var validationSummary = withValidation == DumpStateCommand.WithValidation.NO ? "" : "validated ";
        final var migrationSummary = withMigration == DumpStateCommand.WithMigration.NO
                ? ""
                : "(with %smigration)".formatted(validationSummary);

        final var relatedEntityCounts = RelatedEntities.countRelatedEntities(uniques);
        writer.writeln("=== %7d unique tokens (%d owned by treasury accounts) %s"
                .formatted(uniques.size(), relatedEntityCounts.ownedByTreasury(), migrationSummary));
        writer.writeln("    %7d null owners, %7d null or missing spenders"
                .formatted(
                        uniques.size()
                                - (relatedEntityCounts.ownersNotTreasury() + relatedEntityCounts.ownedByTreasury()),
                        uniques.size() - relatedEntityCounts.spenders()));
        writer.writeln("");
    }

    record RelatedEntities(long ownersNotTreasury, long ownedByTreasury, long spenders) {
        @NonNull
        static RelatedEntities countRelatedEntities(@NonNull final Map<UniqueNFTId, UniqueNFT> uniques) {
            final var cs = new long[3];
            uniques.values().forEach(unique -> {
                if (null != unique.owner && !unique.owner.equals(EntityId.MISSING_ENTITY_ID)) cs[0]++;
                if (null != unique.owner && unique.owner.equals(EntityId.MISSING_ENTITY_ID)) cs[1]++;
                if (null != unique.spender && !unique.spender.equals(EntityId.MISSING_ENTITY_ID)) cs[2]++;
            });
            return new RelatedEntities(cs[0], cs[1], cs[2]);
        }
    }

    /**
     * String that separates all fields in the CSV format
     */
    static final String FIELD_SEPARATOR = ";";

    // Need to move this to a common location (copied here from DumpTokensSubcommand)
    static class FieldBuilder {
        final StringBuilder sb;
        final String fieldSeparator;

        FieldBuilder(@NonNull final String fieldSeparator) {
            this.sb = new StringBuilder();
            this.fieldSeparator = fieldSeparator;
        }

        void append(@NonNull final String v) {
            sb.append(v);
            sb.append(fieldSeparator);
        }

        @Override
        @NonNull
        public String toString() {
            if (sb.length() > fieldSeparator.length()) sb.setLength(sb.length() - fieldSeparator.length());
            return sb.toString();
        }
    }

    void reportOnUniques(@NonNull final Writer writer, @NonNull final Map<UniqueNFTId, UniqueNFT> uniques) {
        writer.writeln(formatHeader());
        uniques.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> formatUnique(writer, e.getKey(), e.getValue()));
        writer.writeln("");
    }

    @NonNull
    String formatHeader() {
        return "nftId,nftSerial,"
                + fieldFormatters.stream().map(Pair::left).collect(Collectors.joining(FIELD_SEPARATOR));
    }

    // spotless:off
    @NonNull
    static List<Pair<String, BiConsumer<FieldBuilder, UniqueNFT>>> fieldFormatters = List.of(
            Pair.of("owner", getFieldFormatter(UniqueNFT::owner, ThingsToStrings::toStringOfEntityId)),
            Pair.of("spender", getFieldFormatter(UniqueNFT::spender, ThingsToStrings::toStringOfEntityId)),
            Pair.of("creationTime", getFieldFormatter(UniqueNFT::creationTime, ThingsToStrings::toStringOfRichInstant)),
            Pair.of("metadata", getFieldFormatter(UniqueNFT::metadata, getMaybeStringifyByteString(FIELD_SEPARATOR))),
            Pair.of("prev", getFieldFormatter(UniqueNFT::previous, Object::toString)),
            Pair.of("next", getFieldFormatter(UniqueNFT::next, Object::toString))
    );
    // spotless:on

    @NonNull
    static <T> BiConsumer<FieldBuilder, UniqueNFT> getFieldFormatter(
            @NonNull final Function<UniqueNFT, T> fun, @NonNull final Function<T, String> formatter) {
        return (fb, u) -> formatField(fb, u, fun, formatter);
    }

    static <T> void formatField(
            @NonNull final FieldBuilder fb,
            @NonNull final UniqueNFT unique,
            @NonNull final Function<UniqueNFT, T> fun,
            @NonNull final Function<T, String> formatter) {
        fb.append(formatter.apply(fun.apply(unique)));
    }

    void formatUnique(@NonNull final Writer writer, @NonNull final UniqueNFTId id, @NonNull final UniqueNFT unique) {
        final var fb = new FieldBuilder(FIELD_SEPARATOR);
        fb.append(id.toString());
        fieldFormatters.stream().map(Pair::right).forEach(ff -> ff.accept(fb, unique));
        writer.writeln(fb);
    }

    @NonNull
    Map<UniqueNFTId, UniqueNFT> gatherUniques() {
        final var uniquesStore = state.getUniqueNFTTokens();
        final var r = new HashMap<UniqueNFTId, UniqueNFT>();
        if (uniquesStore.isVirtual()) {
            // world of VirtualMapLike<UniqueTokenKey, UniqueTokenValue>
            final var threadCount = 8; // Good enough for my laptop, why not?
            final var keys = new ConcurrentLinkedQueue<UniqueNFTId>();
            final var values = new ConcurrentLinkedQueue<UniqueNFT>();
            try {
                uniquesStore
                        .virtualMap()
                        .extractVirtualMapData(
                                getStaticThreadManager(),
                                p -> {
                                    keys.add(UniqueNFTId.from(p.left()));
                                    values.add(UniqueNFT.from(p.right()));
                                },
                                threadCount);
            } catch (final InterruptedException ex) {
                System.err.println("*** Traversal of uniques virtual map interrupted!");
                Thread.currentThread().interrupt();
            }
            // Consider in the future: Use another thread to pull things off the queue as they're put on by the
            // virtual map traversal
            while (!keys.isEmpty()) {
                r.put(keys.poll(), values.poll());
            }
        } else {
            // world of MerkleMap<EntityNumPair, MerkleUniqueToken>
            uniquesStore
                    .merkleMap()
                    .getIndex()
                    .forEach((key, value) -> r.put(UniqueNFTId.from(key), UniqueNFT.from(value)));
        }
        return r;
    }

    Map<UniqueNFTId, UniqueNFT> gatherMigratedUniques() {
        final var uniquesStore = getMigratedUniques();
        final var r = new HashMap<UniqueNFTId, UniqueNFT>();

        uniquesStore.keys().forEachRemaining(key -> {
            final var id = UniqueNFTId.from(key);
            final var nft = UniqueNFT.from(uniquesStore.get(key));
            r.put(id, nft);
        });

        return r;
    }

    WritableKVState<NftID, Nft> getMigratedUniques() {
        final var uniquesStore = state.getUniqueNFTTokens();
        final var expectedSize = Math.toIntExact(uniquesStore.size());

        // TODO: do we need this???
        final var uniquesSchema = new InitialModServiceTokenSchema(null, null, null, null, null, CURRENT_VERSION);
        final var uniquesSchemas = uniquesSchema.statesToCreate();

        final var uniquesStateDefinition = uniquesSchemas.stream()
                .filter(sd -> sd.stateKey().equals(TOKENS_KEY))
                .findFirst()
                .orElseThrow();
        final var uniqueSchemaMetadata = new StateMetadata<>(TokenService.NAME, uniquesSchema, uniquesStateDefinition);
        final var uniquesMerkeMap = new NonAtomicReference<MerkleMap<InMemoryKey<NftID>, InMemoryValue<NftID, Nft>>>(
                new MerkleMap<>(expectedSize));

        final var toStore = new NonAtomicReference<WritableKVState<NftID, Nft>>(
                new InMemoryWritableKVState<>(uniqueSchemaMetadata, uniquesMerkeMap.get()));

        if (uniquesStore.isVirtual()) {
            UniqueTokensMigrator.migrateFromUniqueTokenVirtualMap(uniquesStore.virtualMap(), toStore.get());
        }

        return toStore.get();
    }
}

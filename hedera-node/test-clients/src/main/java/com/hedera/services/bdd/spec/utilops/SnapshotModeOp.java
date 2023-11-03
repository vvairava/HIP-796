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

package com.hedera.services.bdd.spec.utilops;

import static com.hedera.services.bdd.junit.RecordStreamAccess.RECORD_STREAM_ACCESS;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.SnapshotMatchMode.NONDETERMINISTIC_CONTRACT_CALL_RESULTS;
import static com.hedera.services.bdd.spec.utilops.SnapshotMatchMode.NONDETERMINISTIC_FUNCTION_PARAMETERS;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.GeneratedMessageV3;
import com.hedera.services.bdd.junit.HapiTestEnv;
import com.hedera.services.bdd.junit.RecordStreamAccess;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.domain.ParsedItem;
import com.hedera.services.bdd.spec.utilops.domain.RecordSnapshot;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TopicID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * A utility operation that either,
 * <ol>
 *     <li>Takes a snapshot of the record stream generated by running a {@link HapiSpec}; or,</li>
 *     <li>Fuzzy-matches the record stream generated by a {@link HapiSpec} against a prior snapshot.</li>
 * </ol>
 * The generated streams may come from either the <i>hedera-node/data/recordstreams/record0.0.3</i>
 * directory created by starting a local node; or the <i>hedera-node/test-clients/build/hapi-test/HAPI Tests/node0</i>
 * directory created by running a spec as a {@link com.hedera.services.bdd.junit.HapiTest}.
 *
 * <p>A "fuzzy-match" is a recursive comparison of two {@link com.google.protobuf.GeneratedMessageV3} messages that
 * ignores the natural variation that occurs in fields like timestamps and hashes when tests are re-rerun. The set
 * of field names to skip is given by {@link #FIELDS_TO_SKIP_IN_FUZZY_MATCH}; and for each snapshot we remember a
 * "placeholder" entity number that gives the number of entities that happened to be in state when the snapshot was
 * taken. This lets us "normalize" any entity ids in the stream (e.g., {@link AccountID}) and compare them against
 * the corresponding normalized ids in the snapshot.
 *
 * <p><b>IMPORTANT</b> - The initial set of fields to skip is almost certainly incomplete. As we encounter new
 * fields that vary between test runs, we should add them to the set. The goal is to make the fuzzy match as
 * deterministic as possible, so that we can be confident that the test is failing for the right reason.
 */
// too many parameters, repeated string literals
@SuppressWarnings({"java:S5960", "java:S1192"})
public class SnapshotModeOp extends UtilOp {
    private static final long MIN_GZIP_SIZE_IN_BYTES = 26;
    private static final Logger log = LogManager.getLogger(SnapshotModeOp.class);

    private static final Set<String> FIELDS_TO_SKIP_IN_FUZZY_MATCH = Set.of(
            // These time-dependent fields will necessarily vary each test execution
            "expiry",
            "expirationTime",
            "consensusTimestamp",
            "parent_consensus_timestamp",
            "transactionValidStart",
            // It would be technically possible but quite difficult to fuzzy-match variation here
            "alias",
            "evm_address",
            // And transaction hashes as well
            "transactionHash",
            // Keys are also regenerated every test execution
            "ed25519",
            "ECDSA_secp256k1",
            // Plus some other fields that we might prefer to make deterministic
            "symbol");

    private static final String PLACEHOLDER_MEMO = "<entity-num-placeholder-creation>";
    private static final String MONO_STREAMS_LOC = "hedera-node/data/recordstreams/record0.0.3";
    private static final String HAPI_TEST_STREAMS_LOC_TPL =
            "hedera-node/test-clients/build/hapi-test/HAPI Tests/node%d/data/recordStreams/record0.0.%d";
    private static final String TEST_CLIENTS_SNAPSHOT_RESOURCES_LOC = "record-snapshots";
    private static final String PROJECT_ROOT_SNAPSHOT_RESOURCES_LOC = "hedera-node/test-clients/record-snapshots";

    private final SnapshotMode mode;
    private final Set<SnapshotMatchMode> matchModes;

    /**
     * The placeholder account number that captures how many entities were in state when the snapshot was taken.
     */
    private long placeholderAccountNum;
    /**
     * The location(s) of the record stream to snapshot or fuzzy-match against. The first location containing
     * records will be used. This was added because the @HapiTest record streams were being written unpredictably,
     * with only some (or none!) of the nodes in the 4-node network flushing their record streams.
     */
    private List<String> recordLocs;
    /**
     * The location to read and save snapshots from.
     */
    private String snapshotLoc;
    /**
     * The full name of the spec that generated the record stream; file name for the JSON snapshot.
     */
    private String fullSpecName;
    /**
     * The memo to use in the {@link com.hederahashgraph.api.proto.java.HederaFunctionality#CryptoCreate} that
     * generates the placeholder number.
     */
    private String placeholderMemo;
    /**
     * If in a fuzzy-match mode, the snapshot to fuzzy-match against.
     */
    private RecordSnapshot snapshotToMatchAgainst;

    public static void main(String... args) throws IOException {
        // Helper to review the snapshot saved for a particular HapiSuite-HapiSpec combination
        final var snapshotToDump = "CryptoTransfer-okToRepeatSerialNumbersInBurnList";
        final var snapshot = loadSnapshotFor(PROJECT_ROOT_SNAPSHOT_RESOURCES_LOC, snapshotToDump);
        final var items = snapshot.parsedItems();
        for (int i = 0, n = items.size(); i < n; i++) {
            final var item = items.get(i);
            System.out.println("Item #" + i + " body: " + item.itemBody());
            System.out.println("Item #" + i + " record: " + item.itemRecord());
        }
    }

    /**
     * Constructs a snapshot operation with the given mode and a unique memo to be used in the
     * {@link com.hederahashgraph.api.proto.java.HederaFunctionality#CryptoCreate} that generates
     * the placeholder number.
     *
     * @param mode the snapshot mode
     */
    public SnapshotModeOp(@NonNull final SnapshotMode mode, @NonNull final SnapshotMatchMode... specialMatchModes) {
        this.mode = requireNonNull(mode);
        this.matchModes = EnumSet.copyOf(Arrays.asList(specialMatchModes));
        // Each snapshot should have a unique placeholder memo so that we can take multiple snapshots
        // without clearing the record streams directory in between
        placeholderMemo = PLACEHOLDER_MEMO + Instant.now();
    }

    /**
     * Initializes the operation by setting its mutable internal fields, most notably the "placeholder" entity
     * number that captures how many entities were in state when the snapshot was taken.
     *
     * @param spec the spec to run
     * @return {@code false} since this operation does not need blocking status resolution
     */
    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        if (mode.targetNetworkType() == spec.targetNetworkType()) {
            this.fullSpecName = spec.getSuitePrefix() + "-" + spec.getName();
            switch (mode) {
                case TAKE_FROM_MONO_STREAMS -> computePlaceholderNum(
                        monoStreamLocs(), PROJECT_ROOT_SNAPSHOT_RESOURCES_LOC, spec);
                case TAKE_FROM_HAPI_TEST_STREAMS -> computePlaceholderNum(
                        hapiTestStreamLocs(), TEST_CLIENTS_SNAPSHOT_RESOURCES_LOC, spec);
                case FUZZY_MATCH_AGAINST_MONO_STREAMS -> prepToFuzzyMatchAgainstLoc(
                        monoStreamLocs(), PROJECT_ROOT_SNAPSHOT_RESOURCES_LOC, spec);
                case FUZZY_MATCH_AGAINST_HAPI_TEST_STREAMS -> prepToFuzzyMatchAgainstLoc(
                        hapiTestStreamLocs(), TEST_CLIENTS_SNAPSHOT_RESOURCES_LOC, spec);
            }
        }
        return false;
    }

    /**
     * Returns whether this operation has work to do, i.e., whether it could run against the target network.
     *
     * @return if this operation can run against the target network
     */
    public boolean hasWorkToDo() {
        // We leave the spec name null in submitOp() if we are running against a target network that
        // doesn't match the SnapshotMode of this operation
        return fullSpecName != null;
    }

    /**
     * The special snapshot operation entrypoint, called by the {@link HapiSpec} when it is time to read all
     * generated record files and either snapshot or fuzzy-match their contents.
     */
    public void finishLifecycle() {
        if (!hasWorkToDo()) {
            return;
        }
        try {
            RecordStreamAccess.Data data = RecordStreamAccess.Data.EMPTY_DATA;
            for (final var recordLoc : recordLocs) {
                try {
                    data = RECORD_STREAM_ACCESS.readStreamDataFrom(
                            recordLoc, "sidecar", f -> new File(f).length() > MIN_GZIP_SIZE_IN_BYTES);
                } catch (Exception ignore) {
                    // We will try the next location, if any
                }
                if (!data.records().isEmpty()) {
                    break;
                }
            }
            final List<ParsedItem> postPlaceholderItems = new ArrayList<>();
            final var allItems = requireNonNull(data).records().stream()
                    .flatMap(recordWithSidecars -> recordWithSidecars.recordFile().getRecordStreamItemsList().stream())
                    .toList();
            // We only want to snapshot or fuzzy-match the records that come after the placeholder creation
            boolean placeholderFound = false;
            for (final var item : allItems) {
                final var parsedItem = ParsedItem.parse(item);
                final var body = parsedItem.itemBody();
                if (!placeholderFound) {
                    if (body.getMemo().equals(placeholderMemo)) {
                        final var streamPlaceholderNum = parsedItem
                                .itemRecord()
                                .getReceipt()
                                .getAccountID()
                                .getAccountNum();
                        Assertions.assertEquals(
                                placeholderAccountNum,
                                streamPlaceholderNum,
                                "Found placeholder account num 0.0." + streamPlaceholderNum + "(expected 0.0."
                                        + placeholderAccountNum + " from creation)");
                        placeholderFound = true;
                    }
                } else {
                    postPlaceholderItems.add(parsedItem);
                }
            }
            // Given just these records, either write a snapshot or fuzzy-match against the existing snapshot
            switch (mode) {
                case TAKE_FROM_MONO_STREAMS, TAKE_FROM_HAPI_TEST_STREAMS -> writeSnapshotOf(postPlaceholderItems);
                case FUZZY_MATCH_AGAINST_MONO_STREAMS,
                        FUZZY_MATCH_AGAINST_HAPI_TEST_STREAMS -> fuzzyMatchAgainstSnapshot(postPlaceholderItems);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Given a list of parsed items from the record stream, fuzzy-matches them against the snapshot.
     *
     * @param postPlaceholderItems the list of parsed items from the record stream
     */
    private void fuzzyMatchAgainstSnapshot(@NonNull final List<ParsedItem> postPlaceholderItems) {
        log.info("Now fuzzy-matching {} post-placeholder records against snapshot", postPlaceholderItems.size());
        final var itemsFromSnapshot = snapshotToMatchAgainst.parsedItems();
        final var minItems = Math.min(postPlaceholderItems.size(), itemsFromSnapshot.size());
        final var snapshotPlaceholderNum = snapshotToMatchAgainst.getPlaceholderNum();
        for (int i = 0; i < minItems; i++) {
            final var fromSnapshot = itemsFromSnapshot.get(i);
            final var fromStream = postPlaceholderItems.get(i);
            final var j = i;
            fuzzyMatch(
                    fromSnapshot.itemBody(),
                    snapshotPlaceholderNum,
                    fromStream.itemBody(),
                    placeholderAccountNum,
                    () -> "Item #" + j + " body mismatch (EXPECTED " + fromSnapshot.itemBody() + " ACTUAL "
                            + fromStream.itemBody() + ")");
            fuzzyMatch(
                    fromSnapshot.itemRecord(),
                    snapshotPlaceholderNum,
                    fromStream.itemRecord(),
                    placeholderAccountNum,
                    () -> "Item #" + j + " record mismatch (EXPECTED " + fromSnapshot.itemRecord() + " ACTUAL "
                            + fromStream.itemRecord() + ")");
        }
        if (postPlaceholderItems.size() != itemsFromSnapshot.size()) {
            Assertions.fail("Instead of " + itemsFromSnapshot.size() + " items, " + postPlaceholderItems.size()
                    + " were generated");
        }
    }

    /**
     * Given two messages, recursively asserts that they are equal up to certain "fuzziness" in values like timestamps,
     * hashes, and entity ids; since these quantities will vary based on the number of entities in the system and the
     * time at which the test is run.
     *
     * <p>Two {@link GeneratedMessageV3} messages are fuzzy-equal iff they have the same fields, where each un-skipped
     * primitive field matches exactly; each un-skipped {@link GeneratedMessageV3} field fuzzy-matches; and each
     * un-skipped list field consists of fuzzy-equal elements.
     *
     * @param expectedMessage the expected message
     * @param expectedPlaceholderNum the placeholder number for the expected message
     * @param actualMessage the actual message
     * @param actualPlaceholderNum the placeholder number for the actual message
     * @param mismatchContext a supplier of a string that describes the context of the mismatch
     */
    private void fuzzyMatch(
            @NonNull GeneratedMessageV3 expectedMessage,
            final long expectedPlaceholderNum,
            @NonNull GeneratedMessageV3 actualMessage,
            final long actualPlaceholderNum,
            @NonNull final Supplier<String> mismatchContext) {
        requireNonNull(expectedMessage);
        requireNonNull(actualMessage);
        requireNonNull(mismatchContext);
        final var expectedType = expectedMessage.getClass();
        final var actualType = actualMessage.getClass();
        if (!expectedType.equals(actualType)) {
            Assertions.fail("Mismatched types between expected " + expectedType + " and " + actualType + " - "
                    + mismatchContext.get());
        }
        expectedMessage = normalized(expectedMessage, expectedPlaceholderNum);
        actualMessage = normalized(actualMessage, actualPlaceholderNum);
        // getAllFields() returns a SortedMap so ordering is deterministic here
        final var expectedFields =
                new ArrayList<>(expectedMessage.getAllFields().entrySet());
        final var actualFields = new ArrayList<>(actualMessage.getAllFields().entrySet());
        if (expectedFields.size() != actualFields.size()) {
            Assertions.fail("Mismatched field counts between expected " + expectedMessage + " and " + actualMessage
                    + " - " + mismatchContext.get());
        }
        for (int i = 0, n = expectedFields.size(); i < n; i++) {
            final var expectedField = expectedFields.get(i);
            final var actualField = actualFields.get(i);
            final var expectedName = expectedField.getKey().getName();
            final var actualName = actualField.getKey().getName();
            if (!Objects.equals(expectedName, actualName)) {
                Assertions.fail(
                        "Mismatched field names ('" + expectedName + "' vs '" + actualName + "' between expected "
                                + expectedMessage + " and " + actualMessage + " - " + mismatchContext.get());
            }
            if (shouldSkip(expectedName)) {
                continue;
            }
            matchValues(
                    expectedName,
                    expectedField.getValue(),
                    expectedPlaceholderNum,
                    actualField.getValue(),
                    actualPlaceholderNum,
                    mismatchContext);
        }
    }

    /**
     * Given an expected value which may be a list, either fuzzy-matches all values in the list against the actual
     * value (which must of course also be a list in this case); or fuzzy-matches the expected single value with the
     * actual value.
     *
     * @param fieldName the name of the field being fuzzy-matched
     * @param expectedValue the expected value
     * @param expectedPlaceholderNum the placeholder number for the expected value
     * @param actualValue the actual value
     * @param actualPlaceholderNum the placeholder number for the actual value
     * @param mismatchContext a supplier of a string that describes the context of the mismatch
     */
    private void matchValues(
            @NonNull final String fieldName,
            @NonNull final Object expectedValue,
            final long expectedPlaceholderNum,
            @NonNull final Object actualValue,
            final long actualPlaceholderNum,
            @NonNull final Supplier<String> mismatchContext) {
        requireNonNull(fieldName);
        requireNonNull(expectedValue);
        requireNonNull(actualValue);
        requireNonNull(mismatchContext);
        if (expectedValue instanceof List<?> expectedList) {
            if (actualValue instanceof List<?> actualList) {
                if (expectedList.size() != actualList.size()) {
                    Assertions.fail("Mismatched list sizes between expected list " + expectedList + " and " + actualList
                            + " - " + mismatchContext.get());
                }
                for (int j = 0, m = expectedList.size(); j < m; j++) {
                    final var expectedElement = expectedList.get(j);
                    final var actualElement = actualList.get(j);
                    // There are no lists of lists in the record stream, so match single values
                    matchSingleValues(
                            expectedElement,
                            expectedPlaceholderNum,
                            actualElement,
                            actualPlaceholderNum,
                            mismatchContext,
                            fieldName);
                }
            } else {
                Assertions.fail("Mismatched types between expected list '" + expectedList + "' and "
                        + actualValue.getClass().getSimpleName() + " '" + actualValue + "' - "
                        + mismatchContext.get());
            }
        } else {
            matchSingleValues(
                    expectedValue,
                    expectedPlaceholderNum,
                    actualValue,
                    actualPlaceholderNum,
                    () -> "Matching field '" + fieldName + "' " + mismatchContext.get(),
                    fieldName);
        }
    }

    /**
     * Either recursively fuzzy-matches two given {@link GeneratedMessageV3}; or asserts object equality via
     * {@code Assertions#assertEquals()}; or fails immediately if the types are mismatched.
     *
     * @param expected the expected value
     * @param expectedPlaceholderNum the placeholder number for the expected value
     * @param actual the actual value
     * @param actualPlaceholderNum the placeholder number for the actual value
     * @param mismatchContext a supplier of a string that describes the context of the mismatch
     * @param fieldName the name of the field being fuzzy-matched
     */
    private void matchSingleValues(
            @NonNull final Object expected,
            final long expectedPlaceholderNum,
            @NonNull final Object actual,
            final long actualPlaceholderNum,
            @NonNull final Supplier<String> mismatchContext,
            @NonNull final String fieldName) {
        requireNonNull(expected);
        requireNonNull(actual);
        requireNonNull(mismatchContext);
        if (expected instanceof GeneratedMessageV3 expectedMessage) {
            if (actual instanceof GeneratedMessageV3 actualMessage) {
                fuzzyMatch(
                        expectedMessage, expectedPlaceholderNum, actualMessage, actualPlaceholderNum, mismatchContext);
            } else {
                Assertions.fail("Mismatched types between expected message '" + expectedMessage + "' and "
                        + actual.getClass().getSimpleName() + " '" + actual + "' - " + mismatchContext.get());
            }
        } else {
            if ("transactionFee".equals(fieldName)) {
                // Transaction fees can vary by tiny amounts based on the size of the sig map
                Assertions.assertTrue(
                        Math.abs((long) expected - (long) actual) <= 1,
                        "Transaction fees '" + expected + "' and '" + actual + "' varied by more than 1 tinybar - "
                                + mismatchContext.get());
            } else {
                Assertions.assertEquals(
                        expected,
                        actual,
                        "Mismatched values '" + expected + "' vs '" + actual + "' - " + mismatchContext.get());
            }
        }
    }

    /**
     * Given a message that possibly represents an entity id (e.g., {@link AccountID}, returns a normalized message
     * that replaces an entity id number above the placeholder number with its "normalized" value.
     *
     * @param message the message to possibly normalize (if it is an entity id)
     * @param placeholderNum the placeholder number to use in normalization
     * @return the original message if not an entity id; or a normalized message if it is
     */
    private static GeneratedMessageV3 normalized(@NonNull final GeneratedMessageV3 message, final long placeholderNum) {
        requireNonNull(message);
        if (message instanceof AccountID accountID) {
            final var normalizedNum = placeholderNum < accountID.getAccountNum()
                    ? accountID.getAccountNum() - placeholderNum
                    : accountID.getAccountNum();
            return accountID.toBuilder().setAccountNum(normalizedNum).build();
        } else if (message instanceof ContractID contractID) {
            final var normalizedNum = placeholderNum < contractID.getContractNum()
                    ? contractID.getContractNum() - placeholderNum
                    : contractID.getContractNum();
            return contractID.toBuilder().setContractNum(normalizedNum).build();
        } else if (message instanceof TopicID topicID) {
            final var normalizedNum = placeholderNum < topicID.getTopicNum()
                    ? topicID.getTopicNum() - placeholderNum
                    : topicID.getTopicNum();
            return topicID.toBuilder().setTopicNum(normalizedNum).build();
        } else if (message instanceof TokenID tokenID) {
            final var normalizedNum = placeholderNum < tokenID.getTokenNum()
                    ? tokenID.getTokenNum() - placeholderNum
                    : tokenID.getTokenNum();
            return tokenID.toBuilder().setTokenNum(normalizedNum).build();
        } else if (message instanceof FileID fileID) {
            final var normalizedNum =
                    placeholderNum < fileID.getFileNum() ? fileID.getFileNum() - placeholderNum : fileID.getFileNum();
            return fileID.toBuilder().setFileNum(normalizedNum).build();
        } else if (message instanceof ScheduleID scheduleID) {
            final var normalizedNum = placeholderNum < scheduleID.getScheduleNum()
                    ? scheduleID.getScheduleNum() - placeholderNum
                    : scheduleID.getScheduleNum();
            return scheduleID.toBuilder().setScheduleNum(normalizedNum).build();
        } else {
            return message;
        }
    }

    private void writeSnapshotOf(@NonNull final List<ParsedItem> postPlaceholderItems) throws IOException {
        final var recordSnapshot = RecordSnapshot.from(placeholderAccountNum, postPlaceholderItems);
        final var om = new ObjectMapper();
        final var outputLoc = resourceLocOf(snapshotLoc, fullSpecName);
        log.info("Writing snapshot of {} post-placeholder items to {}", postPlaceholderItems.size(), outputLoc);
        final var fout = Files.newOutputStream(outputLoc);
        om.writeValue(fout, recordSnapshot);
    }

    private static Path resourceLocOf(@NonNull final String snapshotLoc, @NonNull final String specName) {
        return Paths.get(snapshotLoc, specName + ".json");
    }

    private void prepToFuzzyMatchAgainstLoc(
            @NonNull final List<String> recordsLocs, @NonNull final String snapshotLoc, @NonNull final HapiSpec spec)
            throws IOException {
        computePlaceholderNum(recordsLocs, snapshotLoc, spec);
        snapshotToMatchAgainst = loadSnapshotFor(snapshotLoc, fullSpecName);
        log.info(
                "Read {} post-placeholder records from snapshot",
                snapshotToMatchAgainst.getEncodedItems().size());
    }

    private static RecordSnapshot loadSnapshotFor(@NonNull final String snapshotLoc, @NonNull final String specName)
            throws IOException {
        final var om = new ObjectMapper();
        final var inputLoc = resourceLocOf(snapshotLoc, specName);
        final var fin = Files.newInputStream(inputLoc);
        log.info("Loading snapshot of {} post-placeholder records from {}", specName, inputLoc);
        return om.reader().readValue(fin, RecordSnapshot.class);
    }

    private void computePlaceholderNum(
            @NonNull final List<String> recordLocs, @NonNull final String snapshotLoc, @NonNull final HapiSpec spec) {
        this.recordLocs = recordLocs;
        this.snapshotLoc = snapshotLoc;
        final var placeholderCreation = cryptoCreate("PLACEHOLDER")
                .memo(placeholderMemo)
                .exposingCreatedIdTo(id -> this.placeholderAccountNum = id.getAccountNum())
                .noLogging();
        allRunFor(spec, placeholderCreation);
    }

    private List<String> monoStreamLocs() {
        return List.of(MONO_STREAMS_LOC);
    }

    private List<String> hapiTestStreamLocs() {
        final List<String> locs = new ArrayList<>(HapiTestEnv.CLUSTER_SIZE);
        for (int i = 0; i < HapiTestEnv.CLUSTER_SIZE; i++) {
            locs.add(String.format(HAPI_TEST_STREAMS_LOC_TPL, i, i + 3));
        }
        return locs;
    }

    private boolean shouldSkip(@NonNull final String expectedName) {
        requireNonNull(expectedName);
        if ("contractCallResult".equals(expectedName)) {
            return matchModes.contains(NONDETERMINISTIC_CONTRACT_CALL_RESULTS);
        } else if ("functionParameters".equals(expectedName)) {
            return matchModes.contains(NONDETERMINISTIC_FUNCTION_PARAMETERS);
        } else {
            return FIELDS_TO_SKIP_IN_FUZZY_MATCH.contains(expectedName);
        }
    }
}

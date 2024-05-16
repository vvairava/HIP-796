/*
 * Copyright (C) 2022-2024 Hedera Hashgraph, LLC
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

package com.hedera.services.bdd.suites.block;

import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitUntilStartOfNextAdhocPeriod;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.records.SnapshotMatchMode.NONDETERMINISTIC_ETHEREUM_DATA;
import static com.hedera.services.bdd.spec.utilops.records.SnapshotMatchMode.NONDETERMINISTIC_LOG_DATA;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.primitives.Longs;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Timestamp;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.jupiter.api.DynamicTest;

public class BlockSuite {
    public static final String LOG_NOW = "logNow";
    public static final String AUTO_ACCOUNT = "autoAccount";

    @SuppressWarnings("java:S5960")
    @HapiTest
    final Stream<DynamicTest> blck003ReturnsTimestampOfTheBlock() {
        final var contract = "EmitBlockTimestamp";
        final var firstCall = "firstCall";
        final var secondCall = "secondCall";

        return defaultHapiSpec("returnsTimestampOfTheBlock", NONDETERMINISTIC_ETHEREUM_DATA, NONDETERMINISTIC_LOG_DATA)
                .given(
                        newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                        cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                        cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                                .via(AUTO_ACCOUNT),
                        getTxnRecord(AUTO_ACCOUNT).andAllChildRecords(),
                        uploadInitCode(contract),
                        contractCreate(contract))
                .when(
                        // Ensure we submit these two transactions in the same block
                        waitUntilStartOfNextAdhocPeriod(2_000),
                        ethereumCall(contract, LOG_NOW)
                                .type(EthTxData.EthTransactionType.EIP1559)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .nonce(0)
                                .maxFeePerGas(50L)
                                .gasLimit(1_000_000L)
                                .via(firstCall)
                                .deferStatusResolution()
                                .hasKnownStatus(ResponseCodeEnum.SUCCESS),
                        ethereumCall(contract, LOG_NOW)
                                .type(EthTxData.EthTransactionType.EIP1559)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .nonce(1)
                                .maxFeePerGas(50L)
                                .gasLimit(1_000_000L)
                                .via(secondCall)
                                .deferStatusResolution()
                                .hasKnownStatus(ResponseCodeEnum.SUCCESS))
                .then(withOpContext((spec, opLog) -> {
                    final var firstBlockOp = getTxnRecord(firstCall).hasRetryAnswerOnlyPrecheck(RECORD_NOT_FOUND);
                    final var recordOp = getTxnRecord(secondCall).hasRetryAnswerOnlyPrecheck(RECORD_NOT_FOUND);
                    allRunFor(spec, firstBlockOp, recordOp);

                    final var firstCallRecord = firstBlockOp.getResponseRecord();
                    final var firstCallLogs =
                            firstCallRecord.getContractCallResult().getLogInfoList();
                    final var firstCallTimeLogData =
                            firstCallLogs.get(0).getData().toByteArray();
                    final var firstCallTimestamp =
                            Longs.fromByteArray(Arrays.copyOfRange(firstCallTimeLogData, 24, 32));

                    final var secondCallRecord = recordOp.getResponseRecord();
                    final var secondCallLogs =
                            secondCallRecord.getContractCallResult().getLogInfoList();
                    final var secondCallTimeLogData =
                            secondCallLogs.get(0).getData().toByteArray();
                    final var secondCallTimestamp =
                            Longs.fromByteArray(Arrays.copyOfRange(secondCallTimeLogData, 24, 32));

                    final var firstBlockPeriod = canonicalBlockPeriod(firstCallRecord.getConsensusTimestamp());
                    final var secondBlockPeriod = canonicalBlockPeriod(secondCallRecord.getConsensusTimestamp());

                    // In general both calls will be handled in the same block period, and should hence have the
                    // same Ethereum block timestamp; but timing fluctuations in CI _can_ cause them to be handled
                    // in different block periods, so we allow for that here as well
                    if (firstBlockPeriod < secondBlockPeriod) {
                        assertTrue(
                                firstCallTimestamp < secondCallTimestamp,
                                "Block timestamps should change from period " + firstBlockPeriod + " to "
                                        + secondBlockPeriod);
                    } else {
                        assertEquals(firstCallTimestamp, secondCallTimestamp, "Block timestamps should be equal");
                    }
                }));
    }

    @HapiTest
    final Stream<DynamicTest> blck001And002And003And004ReturnsCorrectBlockProperties() {
        final var contract = "EmitBlockTimestamp";
        final var firstBlock = "firstBlock";
        final var secondBlock = "secondBlock";

        return defaultHapiSpec(
                        "returnsCorrectBlockProperties", NONDETERMINISTIC_ETHEREUM_DATA, NONDETERMINISTIC_LOG_DATA)
                .given(
                        newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                        cryptoCreate(RELAYER).balance(6 * ONE_MILLION_HBARS),
                        cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS))
                                .via(AUTO_ACCOUNT),
                        getTxnRecord(AUTO_ACCOUNT).andAllChildRecords(),
                        uploadInitCode(contract),
                        contractCreate(contract))
                .when(
                        waitUntilStartOfNextAdhocPeriod(2_000L),
                        ethereumCall(contract, LOG_NOW)
                                .type(EthTxData.EthTransactionType.EIP1559)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .nonce(0)
                                .maxFeePerGas(50L)
                                .gasLimit(1_000_000L)
                                .via(firstBlock)
                                .deferStatusResolution()
                                .hasKnownStatus(ResponseCodeEnum.SUCCESS),
                        // Make sure we submit the next transaction in the next block
                        waitUntilStartOfNextAdhocPeriod(2_000L),
                        ethereumCall(contract, LOG_NOW)
                                .type(EthTxData.EthTransactionType.EIP1559)
                                .signingWith(SECP_256K1_SOURCE_KEY)
                                .payingWith(RELAYER)
                                .nonce(1)
                                .maxFeePerGas(50L)
                                .gasLimit(1_000_000L)
                                .via(secondBlock)
                                .hasKnownStatus(ResponseCodeEnum.SUCCESS))
                .then(withOpContext((spec, opLog) -> {
                    final var firstBlockOp = getTxnRecord(firstBlock).hasRetryAnswerOnlyPrecheck(RECORD_NOT_FOUND);
                    final var recordOp = getTxnRecord(secondBlock).hasRetryAnswerOnlyPrecheck(RECORD_NOT_FOUND);
                    allRunFor(spec, firstBlockOp, recordOp);

                    // First block info
                    final var firstBlockRecord = firstBlockOp.getResponseRecord();
                    final var firstBlockLogs =
                            firstBlockRecord.getContractCallResult().getLogInfoList();
                    final var firstBlockTimeLogData =
                            firstBlockLogs.get(0).getData().toByteArray();
                    final var firstBlockTimestamp =
                            Longs.fromByteArray(Arrays.copyOfRange(firstBlockTimeLogData, 24, 32));
                    final var firstBlockHashLogData =
                            firstBlockLogs.get(1).getData().toByteArray();
                    final var firstBlockNumber = Longs.fromByteArray(Arrays.copyOfRange(firstBlockHashLogData, 24, 32));
                    final var firstBlockHash = Bytes32.wrap(Arrays.copyOfRange(firstBlockHashLogData, 32, 64));

                    assertEquals(Bytes32.ZERO, firstBlockHash);

                    // Second block info
                    final var secondBlockRecord = recordOp.getResponseRecord();
                    final var secondBlockLogs =
                            secondBlockRecord.getContractCallResult().getLogInfoList();
                    assertEquals(2, secondBlockLogs.size());
                    final var secondBlockTimeLogData =
                            secondBlockLogs.get(0).getData().toByteArray();
                    final var secondBlockTimestamp =
                            Longs.fromByteArray(Arrays.copyOfRange(secondBlockTimeLogData, 24, 32));

                    assertNotEquals(firstBlockTimestamp, secondBlockTimestamp, "Block timestamps should change");

                    final var secondBlockHashLogData =
                            secondBlockLogs.get(1).getData().toByteArray();
                    final var secondBlockNumber =
                            Longs.fromByteArray(Arrays.copyOfRange(secondBlockHashLogData, 24, 32));

                    assertEquals(firstBlockNumber + 1, secondBlockNumber, "Wrong previous block number");

                    final var secondBlockHash = Bytes32.wrap(Arrays.copyOfRange(secondBlockHashLogData, 32, 64));

                    assertEquals(Bytes32.ZERO, secondBlockHash);
                }));
    }

    /**
     * Returns the canonical block period for the given consensus timestamp.
     *
     * @param consensusTimestamp the consensus timestamp
     * @return the canonical block period
     */
    private long canonicalBlockPeriod(@NonNull final Timestamp consensusTimestamp) {
        return Objects.requireNonNull(consensusTimestamp).getSeconds()
                / Long.parseLong(HapiSpecSetup.getDefaultNodeProps().get("hedera.recordStream.logPeriod"));
    }
}

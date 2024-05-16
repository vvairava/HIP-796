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

package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountRecords;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.reduceFeeFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sendModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sendModifiedWithFixedPayer;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.verifyAddLiveHashNotSupported;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.verifyUserFreezeNotAuthorized;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedQueryIds;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class MiscCryptoSuite {
    @HapiTest
    final Stream<DynamicTest> unsupportedAndUnauthorizedTransactionsAreNotThrottled() {
        return defaultHapiSpec("unsupportedAndUnauthorizedTransactionsAreNotThrottled")
                .given()
                .when()
                .then(verifyAddLiveHashNotSupported(), verifyUserFreezeNotAuthorized());
    }

    @HapiTest
    final Stream<DynamicTest> sysAccountKeyUpdateBySpecialWontNeedNewKeyTxnSign() {
        String sysAccount = "0.0.977";
        String randomAccountA = "randomAccountA";
        String randomAccountB = "randomAccountB";
        String firstKey = "firstKey";
        String secondKey = "secondKey";

        return defaultHapiSpec("sysAccountKeyUpdateBySpecialWontNeedNewKeyTxnSign")
                .given(
                        withOpContext((spec, opLog) -> {
                            spec.registry().saveKey(sysAccount, spec.registry().getKey(GENESIS));
                        }),
                        newKeyNamed(firstKey).shape(SIMPLE),
                        newKeyNamed(secondKey).shape(SIMPLE))
                .when(
                        cryptoCreate(randomAccountA).key(firstKey),
                        cryptoCreate(randomAccountB).key(firstKey).balance(ONE_HUNDRED_HBARS))
                .then(
                        cryptoUpdate(sysAccount)
                                .key(secondKey)
                                .payingWith(GENESIS)
                                .hasKnownStatus(SUCCESS)
                                .logged(),
                        cryptoUpdate(randomAccountA)
                                .key(secondKey)
                                .signedBy(firstKey)
                                .payingWith(randomAccountB)
                                .hasKnownStatus(INVALID_SIGNATURE));
    }

    @HapiTest
    final Stream<DynamicTest> reduceTransferFee() {
        final long REDUCED_NODE_FEE = 2L;
        final long REDUCED_NETWORK_FEE = 3L;
        final long REDUCED_SERVICE_FEE = 3L;
        final long REDUCED_TOTAL_FEE = REDUCED_NODE_FEE + REDUCED_NETWORK_FEE + REDUCED_SERVICE_FEE;
        return defaultHapiSpec("ReduceTransferFee")
                .given(
                        cryptoCreate("sender").balance(ONE_HUNDRED_HBARS),
                        cryptoCreate("receiver").balance(0L),
                        cryptoTransfer(tinyBarsFromTo("sender", "receiver", ONE_HBAR))
                                .payingWith("sender")
                                .fee(REDUCED_TOTAL_FEE)
                                .hasPrecheck(INSUFFICIENT_TX_FEE))
                .when(reduceFeeFor(CryptoTransfer, REDUCED_NODE_FEE, REDUCED_NETWORK_FEE, REDUCED_SERVICE_FEE))
                .then(
                        cryptoTransfer(tinyBarsFromTo("sender", "receiver", ONE_HBAR))
                                .payingWith("sender")
                                .fee(ONE_HBAR)
                                .hasPrecheck(OK),
                        getAccountBalance("sender")
                                .hasTinyBars(ONE_HUNDRED_HBARS - ONE_HBAR - REDUCED_TOTAL_FEE)
                                .logged());
    }

    @HapiTest
    final Stream<DynamicTest> getsGenesisBalance() {
        return defaultHapiSpec("GetsGenesisBalance")
                .given()
                .when()
                .then(getAccountBalance(GENESIS).logged());
    }

    @HapiTest
    final Stream<DynamicTest> getBalanceIdVariantsTreatedAsExpected() {
        return defaultHapiSpec("getBalanceIdVariantsTreatedAsExpected")
                .given()
                .when()
                .then(sendModified(withSuccessivelyVariedQueryIds(), () -> getAccountBalance(DEFAULT_PAYER)));
    }

    @HapiTest
    final Stream<DynamicTest> getDetailsIdVariantsTreatedAsExpected() {
        return defaultHapiSpec("getDetailsIdVariantsTreatedAsExpected")
                .given()
                .when()
                .then(sendModifiedWithFixedPayer(
                        withSuccessivelyVariedQueryIds(),
                        () -> getAccountDetails(DEFAULT_PAYER).payingWith(GENESIS)));
    }

    @HapiTest
    final Stream<DynamicTest> getRecordsIdVariantsTreatedAsExpected() {
        return defaultHapiSpec("getRecordsIdVariantsTreatedAsExpected")
                .given()
                .when()
                .then(sendModified(withSuccessivelyVariedQueryIds(), () -> getAccountRecords(DEFAULT_PAYER)));
    }

    @HapiTest
    final Stream<DynamicTest> getInfoIdVariantsTreatedAsExpected() {
        return defaultHapiSpec("getInfoIdVariantsTreatedAsExpected")
                .given()
                .when()
                .then(sendModified(withSuccessivelyVariedQueryIds(), () -> getAccountInfo(DEFAULT_PAYER)));
    }

    @HapiTest
    final Stream<DynamicTest> getRecordAndReceiptIdVariantsTreatedAsExpected() {
        return defaultHapiSpec("getRecordIdVariantsTreatedAsExpected")
                .given(cryptoTransfer(tinyBarsFromTo(DEFAULT_PAYER, FUNDING, 1)).via("spot"))
                .when()
                .then(
                        sendModified(withSuccessivelyVariedQueryIds(), () -> getTxnRecord("spot")),
                        sendModified(withSuccessivelyVariedQueryIds(), () -> getReceipt("spot")));
    }

    @HapiTest
    final Stream<DynamicTest> transferChangesBalance() {
        return defaultHapiSpec("TransferChangesBalance")
                .given(cryptoCreate("newPayee").balance(0L))
                .when(cryptoTransfer(tinyBarsFromTo(GENESIS, "newPayee", 1_000_000_000L)))
                .then(getAccountBalance("newPayee").hasTinyBars(1_000_000_000L).logged());
    }

    @HapiTest
    final Stream<DynamicTest> updateWithOutOfDateKeyFails() {
        return defaultHapiSpec("UpdateWithOutOfDateKeyFails")
                .given(
                        newKeyNamed("originalKey"),
                        newKeyNamed("updateKey"),
                        cryptoCreate("targetAccount").key("originalKey"))
                .when(cryptoUpdate("targetAccount").key("updateKey").deferStatusResolution())
                .then(cryptoUpdate("targetAccount")
                        .receiverSigRequired(true)
                        .signedBy(GENESIS, "originalKey")
                        .via("invalidKeyUpdateTxn")
                        .deferStatusResolution()
                        .hasKnownStatus(INVALID_SIGNATURE));
    }
}

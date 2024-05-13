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

package com.hedera.services.bdd.suites.issues;

import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestSuite;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.util.List;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

@HapiTestSuite
public class Issue1744Suite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(Issue1744Suite.class);
    private static final String PAYER = "payer";

    public static void main(String... args) {
        new Issue1744Suite().runSuiteSync();
    }

    @Override
    public List<Stream<DynamicTest>> getSpecsInSuite() {
        return List.of(keepsRecordOfPayerIBE());
    }

    @HapiTest
    final Stream<DynamicTest> keepsRecordOfPayerIBE() {
        return defaultHapiSpec("KeepsRecordOfPayerIBE")
                .given(
                        cryptoCreate(CIVILIAN_PAYER),
                        cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L))
                                .payingWith(CIVILIAN_PAYER)
                                .via("referenceTxn"),
                        UtilVerbs.withOpContext((spec, ctxLog) -> {
                            HapiGetTxnRecord subOp = getTxnRecord("referenceTxn");
                            allRunFor(spec, subOp);
                            TransactionRecord record = subOp.getResponseRecord();
                            long fee = record.getTransactionFee();
                            spec.registry().saveAmount("fee", fee);
                            spec.registry().saveAmount("balance", fee * 2);
                        }))
                .when(cryptoCreate(PAYER).balance(spec -> spec.registry().getAmount("balance")))
                .then(
                        UtilVerbs.inParallel(
                                cryptoTransfer(tinyBarsFromTo(PAYER, FUNDING, spec -> spec.registry()
                                                .getAmount("fee")))
                                        .payingWith(PAYER)
                                        .via("txnA")
                                        .hasAnyKnownStatus(),
                                cryptoTransfer(tinyBarsFromTo(PAYER, FUNDING, spec -> spec.registry()
                                                .getAmount("fee")))
                                        .payingWith(PAYER)
                                        .via("txnB")
                                        .hasAnyKnownStatus()),
                        getTxnRecord("txnA").logged(),
                        getTxnRecord("txnB").logged());
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}

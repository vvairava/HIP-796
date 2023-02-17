/*
 * Copyright (C) 2022-2023 Hedera Hashgraph, LLC
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

package com.hedera.services.bdd.suites.contract.precompile;

import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hedera.services.bdd.suites.contract.Utils.asToken;
import static com.hedera.services.bdd.suites.token.TokenAssociationSpecs.VANILLA_TOKEN;
import static com.hedera.services.bdd.suites.utils.contracts.precompile.HTSPrecompileResult.htsPrecompileResult;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;

import com.hedera.node.app.hapi.utils.contracts.ParsingConstants;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hederahashgraph.api.proto.java.TokenID;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TokenAndTypeCheckSuite extends HapiSuite {
    private static final Logger log = LogManager.getLogger(TokenAndTypeCheckSuite.class);
    private static final String TOKEN_AND_TYPE_CHECK_CONTRACT = "TokenAndTypeCheck";
    private static final String ACCOUNT = "anybody";
    private static final int GAS_TO_OFFER = 1_000_000;
    private static final String GET_TOKEN_TYPE = "getType";
    private static final String IS_TOKEN = "isAToken";

    public static void main(String... args) {
        new TokenAndTypeCheckSuite().runSuiteAsync();
    }

    @Override
    public boolean canRunConcurrent() {
        return true;
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    @Override
    public List<HapiSpec> getSpecsInSuite() {
        return List.of(checkTokenAndTypeStandardCases(), checkTokenAndTypeNegativeCases());
    }

    private HapiSpec checkTokenAndTypeStandardCases() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();

        return defaultHapiSpec("checkTokenAndTypeStandardCases")
                .given(
                        cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(VANILLA_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TOKEN_TREASURY)
                                .initialSupply(1_000)
                                .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                        tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                        uploadInitCode(TOKEN_AND_TYPE_CHECK_CONTRACT),
                        contractCreate(TOKEN_AND_TYPE_CHECK_CONTRACT))
                .when(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCallLocal(
                                        TOKEN_AND_TYPE_CHECK_CONTRACT,
                                        IS_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .logged()
                                .has(resultWith()
                                        .resultViaFunctionName(
                                                IS_TOKEN,
                                                TOKEN_AND_TYPE_CHECK_CONTRACT,
                                                isLiteralResult(new Object[] {Boolean.TRUE}))),
                        contractCallLocal(
                                        TOKEN_AND_TYPE_CHECK_CONTRACT,
                                        GET_TOKEN_TYPE,
                                        HapiParserUtil.asHeadlongAddress(asAddress(vanillaTokenID.get())))
                                .logged()
                                .has(resultWith()
                                        .resultViaFunctionName(
                                                GET_TOKEN_TYPE,
                                                TOKEN_AND_TYPE_CHECK_CONTRACT,
                                                isLiteralResult(new Object[] {BigInteger.valueOf(0)}))))))
                .then();
    }

    private HapiSpec checkTokenAndTypeNegativeCases() {
        final AtomicReference<TokenID> vanillaTokenID = new AtomicReference<>();
        final var notAnAddress = new byte[20];

        return defaultHapiSpec("checkTokenAndTypeNegativeCases")
                .given(
                        cryptoCreate(ACCOUNT).balance(100 * ONE_HUNDRED_HBARS),
                        cryptoCreate(TOKEN_TREASURY),
                        tokenCreate(VANILLA_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .treasury(TOKEN_TREASURY)
                                .initialSupply(1_000)
                                .exposingCreatedIdTo(id -> vanillaTokenID.set(asToken(id))),
                        tokenAssociate(ACCOUNT, VANILLA_TOKEN),
                        uploadInitCode(TOKEN_AND_TYPE_CHECK_CONTRACT),
                        contractCreate(TOKEN_AND_TYPE_CHECK_CONTRACT))
                .when(withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(
                                        TOKEN_AND_TYPE_CHECK_CONTRACT,
                                        IS_TOKEN,
                                        HapiParserUtil.asHeadlongAddress(notAnAddress))
                                .via("FakeAddressTokenCheckTx")
                                .payingWith(ACCOUNT)
                                .gas(GAS_TO_OFFER),
                        contractCall(
                                        TOKEN_AND_TYPE_CHECK_CONTRACT,
                                        GET_TOKEN_TYPE,
                                        HapiParserUtil.asHeadlongAddress(notAnAddress))
                                .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                .via("FakeAddressTokenTypeCheckTx")
                                .payingWith(ACCOUNT)
                                .gas(GAS_TO_OFFER)
                                .logged())))
                .then(
                        childRecordsCheck(
                                "FakeAddressTokenCheckTx",
                                SUCCESS,
                                recordWith()
                                        .status(SUCCESS)
                                        .contractCallResult(resultWith()
                                                .contractCallResult(htsPrecompileResult()
                                                        .forFunction(ParsingConstants.FunctionType.HAPI_IS_TOKEN)
                                                        .withStatus(SUCCESS)
                                                        .withIsToken(false)))),
                        childRecordsCheck(
                                "FakeAddressTokenTypeCheckTx",
                                CONTRACT_REVERT_EXECUTED,
                                recordWith().status(INVALID_TOKEN_ID)));
    }
}

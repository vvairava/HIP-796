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

package com.hedera.services.bdd.spec.utilops.mod;

import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withClearedField;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_ID_DOES_NOT_EXIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSFER_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OBTAINER_REQUIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PAYER_ACCOUNT_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT;
import static java.util.Map.entry;
import static java.util.Objects.requireNonNull;

import com.google.protobuf.Descriptors;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;

/**
 * A {@link ModificationStrategy} that clears entity ids from the original
 * transaction body.
 */
public class BodyIdClearingStrategy extends IdClearingStrategy<TxnModification> implements TxnModificationStrategy {
    private static final Map<String, ExpectedResponse> CLEARED_ID_RESPONSES = Map.ofEntries(
            entry("proto.TransactionID.accountID", ExpectedResponse.atIngest(PAYER_ACCOUNT_NOT_FOUND)),
            entry("proto.TransactionBody.nodeAccountID", ExpectedResponse.atIngest(INVALID_NODE_ACCOUNT)),
            // (FUTURE) Switch to expecting any "atIngest()" response below to atConsensus()
            entry("proto.TokenAssociateTransactionBody.account", ExpectedResponse.atIngest(INVALID_ACCOUNT_ID)),
            entry(
                    "proto.TokenAssociateTransactionBody.tokens",
                    ExpectedResponse.atConsensusOneOf(SUCCESS, TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT)),
            entry(
                    "proto.AccountAmount.accountID",
                    ExpectedResponse.atIngestOneOf(INVALID_ACCOUNT_ID, INVALID_TRANSFER_ACCOUNT_ID)),
            entry("proto.NftTransfer.senderAccountID", ExpectedResponse.atIngest(INVALID_TRANSFER_ACCOUNT_ID)),
            entry("proto.NftTransfer.receiverAccountID", ExpectedResponse.atIngest(INVALID_TRANSFER_ACCOUNT_ID)),
            entry("proto.TokenTransferList.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry(
                    "proto.CryptoUpdateTransactionBody.accountIDToUpdate",
                    ExpectedResponse.atIngest(ACCOUNT_ID_DOES_NOT_EXIST)),
            entry("proto.CryptoUpdateTransactionBody.staked_account_id", ExpectedResponse.atConsensus(SUCCESS)),
            entry("proto.ContractCallTransactionBody.contractID", ExpectedResponse.atIngest(INVALID_CONTRACT_ID)),
            entry(
                    "proto.CryptoDeleteTransactionBody.transferAccountID",
                    ExpectedResponse.atIngest(ACCOUNT_ID_DOES_NOT_EXIST)),
            entry(
                    "proto.CryptoDeleteTransactionBody.deleteAccountID",
                    ExpectedResponse.atIngest(ACCOUNT_ID_DOES_NOT_EXIST)),
            entry("proto.ContractCreateTransactionBody.fileID", ExpectedResponse.atConsensus(INVALID_FILE_ID)),
            entry("proto.ContractCreateTransactionBody.auto_renew_account_id", ExpectedResponse.atConsensus(SUCCESS)),
            entry("proto.ContractUpdateTransactionBody.contractID", ExpectedResponse.atConsensus(INVALID_CONTRACT_ID)),
            entry("proto.ContractUpdateTransactionBody.auto_renew_account_id", ExpectedResponse.atConsensus(SUCCESS)),
            entry("proto.ContractUpdateTransactionBody.staked_account_id", ExpectedResponse.atConsensus(SUCCESS)),
            entry("proto.FileAppendTransactionBody.fileID", ExpectedResponse.atIngest(INVALID_FILE_ID)),
            entry("proto.FileUpdateTransactionBody.fileID", ExpectedResponse.atIngest(INVALID_FILE_ID)),
            entry("proto.FileDeleteTransactionBody.fileID", ExpectedResponse.atIngest(INVALID_FILE_ID)),
            entry("proto.CryptoCreateTransactionBody.staked_account_id", ExpectedResponse.atConsensus(SUCCESS)),
            entry("proto.SystemDeleteTransactionBody.fileID", ExpectedResponse.atIngest(INVALID_TRANSACTION_BODY)),
            entry("proto.SystemUndeleteTransactionBody.fileID", ExpectedResponse.atIngest(INVALID_TRANSACTION_BODY)),
            entry("proto.ContractDeleteTransactionBody.contractID", ExpectedResponse.atIngest(INVALID_CONTRACT_ID)),
            entry(
                    "proto.ContractDeleteTransactionBody.transferAccountID",
                    ExpectedResponse.atConsensus(OBTAINER_REQUIRED)),
            entry(
                    "proto.ContractDeleteTransactionBody.transferContractID",
                    ExpectedResponse.atConsensus(OBTAINER_REQUIRED)),
            entry("proto.ConsensusCreateTopicTransactionBody.autoRenewAccount", ExpectedResponse.atConsensus(SUCCESS)),
            entry("proto.ConsensusUpdateTopicTransactionBody.topicID", ExpectedResponse.atConsensus(INVALID_TOPIC_ID)),
            entry("proto.ConsensusUpdateTopicTransactionBody.autoRenewAccount", ExpectedResponse.atConsensus(SUCCESS)),
            entry("proto.ConsensusDeleteTopicTransactionBody.topicID", ExpectedResponse.atConsensus(INVALID_TOPIC_ID)),
            entry(
                    "proto.ConsensusSubmitMessageTransactionBody.topicID",
                    ExpectedResponse.atConsensus(INVALID_TOPIC_ID)),
            entry(
                    "proto.TokenCreateTransactionBody.treasury",
                    ExpectedResponse.atIngest(INVALID_TREASURY_ACCOUNT_FOR_TOKEN)),
            entry("proto.TokenCreateTransactionBody.autoRenewAccount", ExpectedResponse.atConsensus(SUCCESS)),
            entry(
                    "proto.CustomFee.fee_collector_account_id",
                    ExpectedResponse.atConsensus(INVALID_CUSTOM_FEE_COLLECTOR)),
            entry("proto.FixedFee.denominating_token_id", ExpectedResponse.atConsensus(SUCCESS)),
            entry("proto.TokenFreezeAccountTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenFreezeAccountTransactionBody.account", ExpectedResponse.atIngest(INVALID_ACCOUNT_ID)),
            entry("proto.TokenUnfreezeAccountTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenUnfreezeAccountTransactionBody.account", ExpectedResponse.atIngest(INVALID_ACCOUNT_ID)),
            entry("proto.TokenGrantKycTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenGrantKycTransactionBody.account", ExpectedResponse.atIngest(INVALID_ACCOUNT_ID)),
            entry("proto.TokenRevokeKycTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenRevokeKycTransactionBody.account", ExpectedResponse.atIngest(INVALID_ACCOUNT_ID)),
            entry("proto.TokenDeleteTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenUpdateTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenUpdateTransactionBody.autoRenewAccount", ExpectedResponse.atConsensus(SUCCESS)),
            entry("proto.TokenMintTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenBurnTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenWipeAccountTransactionBody.token", ExpectedResponse.atIngest(INVALID_TOKEN_ID)),
            entry("proto.TokenWipeAccountTransactionBody.account", ExpectedResponse.atIngest(INVALID_ACCOUNT_ID)));

    @NonNull
    @Override
    public TxnModification modificationForTarget(@NonNull Descriptors.FieldDescriptor descriptor, int encounterIndex) {
        final var expectedResponse = CLEARED_ID_RESPONSES.get(descriptor.getFullName());
        requireNonNull(expectedResponse, "No expected response for field " + descriptor.getFullName());
        return new TxnModification(
                "Clearing field " + descriptor.getFullName() + " (#" + encounterIndex + ")",
                BodyMutation.withTransform(b -> withClearedField(b, descriptor, encounterIndex)),
                expectedResponse);
    }
}

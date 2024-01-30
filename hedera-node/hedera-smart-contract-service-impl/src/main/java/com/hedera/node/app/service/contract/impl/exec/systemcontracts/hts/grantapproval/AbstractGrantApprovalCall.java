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

package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.grantapproval;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenType;
import com.hedera.hapi.node.token.CryptoApproveAllowanceTransactionBody;
import com.hedera.hapi.node.token.CryptoDeleteAllowanceTransactionBody;
import com.hedera.hapi.node.token.NftAllowance;
import com.hedera.hapi.node.token.NftRemoveAllowance;
import com.hedera.hapi.node.token.TokenAllowance;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AbstractHtsCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater.Enhancement;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.math.BigInteger;

public abstract class AbstractGrantApprovalCall extends AbstractHtsCall {
    protected final VerificationStrategy verificationStrategy;
    protected final AccountID senderId;
    protected final TokenID tokenId;
    protected final AccountID spenderId;
    protected final BigInteger amount;
    protected final TokenType tokenType;

    // too many parameters
    @SuppressWarnings("java:S107")
    protected AbstractGrantApprovalCall(
            @NonNull final SystemContractGasCalculator gasCalculator,
            @NonNull final Enhancement enhancement,
            @NonNull final VerificationStrategy verificationStrategy,
            @NonNull final AccountID senderId,
            @NonNull final TokenID tokenId,
            @NonNull final AccountID spenderId,
            @NonNull final BigInteger amount,
            @NonNull final TokenType tokenType,
            final boolean isViewCall) {
        super(gasCalculator, enhancement, isViewCall);
        this.verificationStrategy = verificationStrategy;
        this.senderId = senderId;
        this.tokenId = tokenId;
        this.spenderId = spenderId;
        this.amount = amount;
        this.tokenType = tokenType;
    }

    protected TransactionBody synthApprovalBody() {
        if (tokenType == TokenType.NON_FUNGIBLE_UNIQUE) {
            var ownerId = getMaybeOwnerId();

            if (ownerId != null && !isNftApprovalRevocation()) {
                if (!ownerId.equals(senderId)) {
                    return buildCryptoApproveAllowance(approveDelegate(ownerId, senderId));
                }
            }

            return isNftApprovalRevocation()
                    ? buildCryptoDeleteAllowance(remove(ownerId))
                    : buildCryptoApproveAllowance(approve(ownerId));
        } else {
            return buildCryptoApproveAllowance(approve(senderId));
        }
    }

    private CryptoDeleteAllowanceTransactionBody remove(AccountID ownerId) {
        return CryptoDeleteAllowanceTransactionBody.newBuilder()
                .nftAllowances(NftRemoveAllowance.newBuilder()
                        .tokenId(tokenId)
                        .owner(ownerId)
                        .serialNumbers(amount.longValue())
                        .build())
                .build();
    }

    private CryptoApproveAllowanceTransactionBody approveDelegate(AccountID ownerId, AccountID delegateSpenderId) {
        return CryptoApproveAllowanceTransactionBody.newBuilder()
                .nftAllowances(NftAllowance.newBuilder()
                        .tokenId(tokenId)
                        .spender(spenderId)
                        .delegatingSpender(delegateSpenderId)
                        .owner(ownerId)
                        .serialNumbers(amount.longValue())
                        .build())
                .build();
    }

    private CryptoApproveAllowanceTransactionBody approve(AccountID ownerId) {
        return tokenType.equals(TokenType.FUNGIBLE_COMMON)
                ? CryptoApproveAllowanceTransactionBody.newBuilder()
                        .tokenAllowances(TokenAllowance.newBuilder()
                                .tokenId(tokenId)
                                .spender(spenderId)
                                .owner(ownerId)
                                .amount(amount.longValue())
                                .build())
                        .build()
                : CryptoApproveAllowanceTransactionBody.newBuilder()
                        .nftAllowances(NftAllowance.newBuilder()
                                .tokenId(tokenId)
                                .spender(spenderId)
                                .owner(ownerId)
                                .serialNumbers(amount.longValue())
                                .build())
                        .build();
    }

    private TransactionBody buildCryptoDeleteAllowance(CryptoDeleteAllowanceTransactionBody body) {
        return TransactionBody.newBuilder().cryptoDeleteAllowance(body).build();
    }

    private TransactionBody buildCryptoApproveAllowance(CryptoApproveAllowanceTransactionBody body) {
        return TransactionBody.newBuilder().cryptoApproveAllowance(body).build();
    }

    protected @Nullable AccountID getMaybeOwnerId() {
        final var nft = enhancement.nativeOperations().getNft(tokenId.tokenNum(), amount.longValue());
        if (nft == null) {
            return null;
        }
        if (nft.hasOwnerId()) {
            return nft.ownerId();
        } else {
            final var token = nativeOperations().getToken(tokenId.tokenNum());
            return token == null ? null : token.treasuryAccountIdOrThrow();
        }
    }

    private boolean isNftApprovalRevocation() {
        return spenderId.accountNumOrElse(0L) == 0;
    }
}

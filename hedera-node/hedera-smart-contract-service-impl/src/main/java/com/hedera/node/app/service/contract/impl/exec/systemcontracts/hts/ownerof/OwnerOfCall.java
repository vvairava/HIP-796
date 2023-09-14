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

package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ownerof;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HederaSystemContract.FullResult.revertResult;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.headlongAddressOf;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HederaSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.AbstractNftViewCall;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Implements the token redirect {@code ownerOf()} call of the HTS system contract.
 */
public class OwnerOfCall extends AbstractNftViewCall {
    private static final long TREASURY_OWNER_NUM = 0L;

    public OwnerOfCall(
            @NonNull HederaWorldUpdater.Enhancement enhancement, @Nullable final Token token, final long serialNo) {
        super(enhancement, token, serialNo);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected @NonNull HederaSystemContract.FullResult resultOfViewingNft(
            @NonNull final Token token, @NonNull final Nft nft) {
        requireNonNull(token);
        requireNonNull(nft);
        // TODO - gas calculation
        final var explicitId = nft.ownerIdOrElse(AccountID.DEFAULT);
        final long ownerNum;
        if (explicitId.accountNumOrElse(TREASURY_OWNER_NUM) == TREASURY_OWNER_NUM) {
            ownerNum = token.treasuryAccountIdOrThrow().accountNumOrThrow();
        } else {
            ownerNum = explicitId.accountNumOrThrow();
        }
        final var owner = nativeOperations().getAccount(ownerNum);
        if (owner == null) {
            return revertResult(INVALID_ACCOUNT_ID, 0L);
        } else {
            final var output = OwnerOfTranslator.OWNER_OF.getOutputs().encodeElements(headlongAddressOf(owner));
            return HederaSystemContract.FullResult.successResult(output, 0L);
        }
    }
}

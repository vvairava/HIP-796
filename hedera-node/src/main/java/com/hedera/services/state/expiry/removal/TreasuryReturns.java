/*
 * Copyright (C) 2020-2022 Hedera Hashgraph, LLC
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
package com.hedera.services.state.expiry.removal;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.services.state.enums.TokenType;
import com.hedera.services.state.expiry.TokenRelsListMutation;
import com.hedera.services.state.expiry.classification.EntityLookup;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.state.submerkle.CurrencyAdjustments;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.throttling.ExpiryThrottle;
import com.hedera.services.throttling.MapAccessType;
import com.hedera.services.utils.EntityNum;
import com.hedera.services.utils.EntityNumPair;
import com.hedera.services.utils.MapValueListUtils;
import com.swirlds.merkle.map.MerkleMap;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static com.hedera.services.state.expiry.removal.ContractGC.ROOT_KEY_UPDATE_WORK;
import static com.hedera.services.state.expiry.removal.FungibleTreasuryReturns.FINISHED_NOOP_FUNGIBLE_RETURNS;
import static com.hedera.services.state.expiry.removal.FungibleTreasuryReturns.UNFINISHED_NOOP_FUNGIBLE_RETURNS;
import static com.hedera.services.state.expiry.removal.NonFungibleTreasuryReturns.FINISHED_NOOP_NON_FUNGIBLE_RETURNS;
import static com.hedera.services.state.expiry.removal.NonFungibleTreasuryReturns.UNFINISHED_NOOP_NON_FUNGIBLE_RETURNS;
import static com.hedera.services.throttling.MapAccessType.*;

@Singleton
public class TreasuryReturns {
    static final List<MapAccessType> TOKEN_TYPE_CHECK = List.of(TOKENS_GET);
    static final List<MapAccessType> ONLY_REL_REMOVAL_WORK = List.of(TOKEN_ASSOCIATIONS_REMOVE);
    static final List<MapAccessType> NEXT_REL_REMOVAL_WORK = List.of(TOKEN_ASSOCIATIONS_REMOVE, TOKEN_ASSOCIATIONS_GET_FOR_MODIFY);
    static final List<MapAccessType> ROOT_REL_UPDATE_WORK = List.of(ACCOUNTS_GET_FOR_MODIFY);
    static final List<MapAccessType> TREASURY_BALANCE_INCREMENT = List.of(ACCOUNTS_GET, TOKEN_ASSOCIATIONS_GET_FOR_MODIFY);

    private final Supplier<MerkleMap<EntityNum, MerkleToken>> tokens;
    private final Supplier<MerkleMap<EntityNumPair, MerkleUniqueToken>> nfts;
    private final Supplier<MerkleMap<EntityNumPair, MerkleTokenRelStatus>> tokenRels;

    private final EntityLookup entityLookup;
    private final ExpiryThrottle expiryThrottle;
    private final TreasuryReturnHelper returnHelper;

    private RemovalFacilitation relRemovalFacilitation =
            MapValueListUtils::removeInPlaceFromMapValueList;

    @Inject
    public TreasuryReturns(
            final EntityLookup entityLookup,
            final Supplier<MerkleMap<EntityNum, MerkleToken>> tokens,
            final Supplier<MerkleMap<EntityNumPair, MerkleUniqueToken>> nfts,
            final Supplier<MerkleMap<EntityNumPair, MerkleTokenRelStatus>> tokenRels,
            final ExpiryThrottle expiryThrottle, final TreasuryReturnHelper returnHelper) {
        this.nfts = nfts;
        this.tokens = tokens;
        this.tokenRels = tokenRels;
        this.expiryThrottle = expiryThrottle;
        this.returnHelper = returnHelper;
        this.entityLookup = entityLookup;
    }

    @Nullable
    public FungibleTreasuryReturns returnFungibleUnitsFrom(final MerkleAccount expired) {
        final var expiredNum = expired.getKey();
        final var numRels = expired.getNumAssociations();
        if (numRels == 0) {
           return FINISHED_NOOP_FUNGIBLE_RETURNS;
        } else if (!expiryThrottle.allow(ROOT_KEY_UPDATE_WORK)) {
            return UNFINISHED_NOOP_FUNGIBLE_RETURNS;
        } else {
            final var outcome = tryFungibleReturns(expiredNum, expired);
            final var mutableExpired = entityLookup.getMutableAccount(expiredNum);
            mutableExpired.setNumAssociations(outcome.remainingAssociations());
            final var newLatestAssociation = outcome.newRoot();
            if (newLatestAssociation != null) {
                mutableExpired.setHeadTokenId(newLatestAssociation.getLowOrderAsLong());
            }
            return outcome.fungibleReturns();
        }
    }


    @Nullable
    public NonFungibleTreasuryReturns returnNftsFrom(final MerkleAccount expired) {
        final var expiredNum = expired.getKey();
        final var numNfts = expired.getNftsOwned();
        if (numNfts == 0) {
            return FINISHED_NOOP_NON_FUNGIBLE_RETURNS;
        } else if (!expiryThrottle.allow(ROOT_KEY_UPDATE_WORK)) {
            return UNFINISHED_NOOP_NON_FUNGIBLE_RETURNS;
        } else {
            final var outcome = tryNftReturns(expiredNum, expired);
            final var mutableExpired = entityLookup.getMutableAccount(expiredNum);
            mutableExpired.setNftsOwned(outcome.remainingNfts());
            final var newLatestNft = outcome.newRoot();
            if (newLatestNft != null) {
//                mutableExpired.setHeadNftId();
//                mutableExpired.setHeadNftSerialNum();
                throw new AssertionError("Not implemented");
            }
            return outcome.nftReturns();
        }
    }
    private NftReturnOutcome tryNftReturns(final EntityNum expiredNum, final MerkleAccount expired) {
        throw new AssertionError("Not implemented");
    }

    @SuppressWarnings("java:S135")
    private FungibleReturnOutcome tryFungibleReturns(final EntityNum expiredNum, final MerkleAccount expired) {
        final var curRels = tokenRels.get();
        final var listRemoval = new TokenRelsListMutation(expiredNum.longValue(), curRels);
        final var expectedRels = expired.getNumAssociations();

        var i = expectedRels;
        var relKey = expired.getLatestAssociation();
        final List<EntityId> tokenTypes = new ArrayList<>();
        final List<CurrencyAdjustments> returnTransfers = new ArrayList<>();
        while (relKey != null && hasCapacityForRemovalAt(i) && i-- > 0) {
            if (!expiryThrottle.allow(TOKEN_TYPE_CHECK)) {
                i++;
                break;
            }

            final var tokenNum = relKey.getLowOrderAsNum();
            final var token = tokens.get().get(tokenNum);
            if (token != null && token.tokenType() == TokenType.FUNGIBLE_COMMON) {
                final var rel = curRels.get(relKey);
                final var tokenBalance = rel.getBalance();
                if (tokenBalance > 0) {
                    if (!expiryThrottle.allow(TREASURY_BALANCE_INCREMENT)) {
                        i++;
                        break;
                    }
                    tokenTypes.add(tokenNum.toEntityId());
                    returnHelper.updateFungibleReturns(
                            expiredNum, tokenNum, token, tokenBalance, returnTransfers);
                }
            }

            // We are always removing the root, hence receiving the new root
            relKey = relRemovalFacilitation.removeNext(relKey, relKey, listRemoval);
        }
        final var numLeft = (relKey == null) ? 0 : (expectedRels - i);
        return new FungibleReturnOutcome(
                new FungibleTreasuryReturns(tokenTypes, returnTransfers, numLeft == 0),
                relKey, numLeft);
    }

    private boolean hasCapacityForRemovalAt(final int n) {
        return expiryThrottle.allow(n == 1 ? ONLY_REL_REMOVAL_WORK : NEXT_REL_REMOVAL_WORK);
    }

    @FunctionalInterface
    interface RemovalFacilitation {
        EntityNumPair removeNext(
                EntityNumPair key, EntityNumPair root, TokenRelsListMutation listRemoval);
    }

    @VisibleForTesting
    void setRelRemovalFacilitation(final RemovalFacilitation relRemovalFacilitation) {
        this.relRemovalFacilitation = relRemovalFacilitation;
    }
}

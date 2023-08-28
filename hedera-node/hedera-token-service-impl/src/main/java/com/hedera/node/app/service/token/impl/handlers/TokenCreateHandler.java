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

package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static java.util.Collections.emptyList;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.CustomFee;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableTokenRelationStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.util.TokenHandlerHelper;
import com.hedera.node.app.service.token.impl.validators.CustomFeesValidator;
import com.hedera.node.app.service.token.impl.validators.TokenCreateValidator;
import com.hedera.node.app.service.token.records.TokenCreateRecordBuilder;
import com.hedera.node.app.spi.validation.ExpiryMeta;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.EntitiesConfig;
import com.hedera.node.config.data.TokensConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#TOKEN_CREATE}.
 */
@Singleton
public class TokenCreateHandler extends BaseTokenHandler implements TransactionHandler {
    private final CustomFeesValidator customFeesValidator;
    private final TokenCreateValidator tokenCreateValidator;

    @Inject
    public TokenCreateHandler(
            @NonNull final CustomFeesValidator customFeesValidator,
            @NonNull final TokenCreateValidator tokenCreateValidator) {
        requireNonNull(customFeesValidator);
        requireNonNull(tokenCreateValidator);

        this.customFeesValidator = customFeesValidator;
        this.tokenCreateValidator = tokenCreateValidator;
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var txn = context.body();
        pureChecks(txn);

        final var tokenCreateTxnBody = txn.tokenCreationOrThrow();
        if (tokenCreateTxnBody.hasTreasury()) {
            final var treasuryId = tokenCreateTxnBody.treasuryOrThrow();
            context.requireKeyOrThrow(treasuryId, INVALID_TREASURY_ACCOUNT_FOR_TOKEN);
        }
        if (tokenCreateTxnBody.hasAutoRenewAccount()) {
            final var autoRenewalAccountId = tokenCreateTxnBody.autoRenewAccountOrThrow();
            context.requireKeyOrThrow(autoRenewalAccountId, INVALID_AUTORENEW_ACCOUNT);
        }
        if (tokenCreateTxnBody.hasAdminKey()) {
            context.requireKey(tokenCreateTxnBody.adminKeyOrThrow());
        }
        final var customFees = tokenCreateTxnBody.customFeesOrElse(emptyList());
        addCustomFeeCollectorKeys(context, customFees);
    }

    @Override
    public void pureChecks(@NonNull final TransactionBody txn) throws PreCheckException {
        tokenCreateValidator.pureChecks(txn.tokenCreationOrThrow());
    }

    @Override
    public void handle(@NonNull final HandleContext context) {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.tokenCreationOrThrow();
        // Create or get needed config and stores
        final var tokensConfig = context.configuration().getConfigData(TokensConfig.class);
        final var accountStore = context.writableStore(WritableAccountStore.class);
        final var tokenStore = context.writableStore(WritableTokenStore.class);
        final var tokenRelationStore = context.writableStore(WritableTokenRelationStore.class);

        /* Validate if the current token can be created */
        validateTrue(
                tokenStore.sizeOfState() + 1 <= tokensConfig.maxNumber(),
                MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED);

        // validate fields in the transaction body that involves checking with
        // dynamic properties or state.
        final var resolvedExpiryMeta = validateSemantics(context, accountStore, op, tokensConfig);

        // build a new token
        final var newTokenNum = context.newEntityNum();
        final var newTokenId = TokenID.newBuilder().tokenNum(newTokenNum).build();
        final var newToken = buildToken(newTokenNum, op, resolvedExpiryMeta);

        // validate custom fees and get back list of fees with created token denomination
        final var feesSetNeedingCollectorAutoAssociation = customFeesValidator.validateForCreation(
                newToken, accountStore, tokenRelationStore, tokenStore, op.customFeesOrElse(emptyList()));

        // Put token into modifications map
        tokenStore.put(newToken);
        // associate token with treasury and collector ids of custom fees whose token denomination
        // is set to sentinel value
        associateAccounts(context, newToken, accountStore, tokenRelationStore, feesSetNeedingCollectorAutoAssociation);

        if (op.initialSupply() > 0) {
            // Since we have associated treasury and needed fee collector accounts in the previous step,
            // this relation should exist. Mint the provided initial supply of tokens
            final var treasuryRel = tokenRelationStore.get(op.treasuryOrThrow(), newTokenId);
            // This keeps modified token with minted balance into modifications in token store
            mintFungible(newToken, treasuryRel, op.initialSupply(), true, accountStore, tokenStore, tokenRelationStore);
        }
        // Update record with newly created token id
        final var recordBuilder = context.recordBuilder(TokenCreateRecordBuilder.class);
        recordBuilder.tokenID(newTokenId);
    }

    /**
     * Associate treasury account and the collector accounts of custom fees whose token denomination
     * is set to sentinel value, to use denomination as newly created token.
     * @param newToken newly created token
     * @param accountStore account store
     * @param tokenRelStore token relation store
     * @param requireCollectorAutoAssociation set of custom fees whose token denomination is set to sentinel value
     */
    private void associateAccounts(
            final HandleContext context,
            final Token newToken,
            final WritableAccountStore accountStore,
            @NonNull final WritableTokenRelationStore tokenRelStore,
            final Set<CustomFee> requireCollectorAutoAssociation) {
        final var tokensConfig = context.configuration().getConfigData(TokensConfig.class);
        final var entitiesConfig = context.configuration().getConfigData(EntitiesConfig.class);

        // This should exist as it is validated in validateSemantics
        final var treasury = accountStore.get(newToken.treasuryAccountId());
        // Validate if token relation can be created between treasury and new token
        // If this succeeds, create and link token relation.
        tokenCreateValidator.validateAssociation(entitiesConfig, tokensConfig, treasury, newToken, tokenRelStore);
        createAndLinkTokenRels(treasury, List.of(newToken), accountStore, tokenRelStore);

        for (final var customFee : requireCollectorAutoAssociation) {
            // This should exist as it is validated in validateSemantics
            final var collector = accountStore.get(customFee.feeCollectorAccountIdOrThrow());
            // Validate if token relation can be created between collector and new token
            // If this succeeds, create and link token relation.
            tokenCreateValidator.validateAssociation(entitiesConfig, tokensConfig, collector, newToken, tokenRelStore);
            createAndLinkTokenRels(collector, List.of(newToken), accountStore, tokenRelStore);
        }
    }

    /**
     * Create a new token with the given parameters.
     * @param newTokenNum new token number
     * @param op token creation transaction body
     * @param resolvedExpiryMeta resolved expiry meta
     * @return newly created token
     */
    private Token buildToken(
            final long newTokenNum, final TokenCreateTransactionBody op, final ExpiryMeta resolvedExpiryMeta) {
        return new Token(
                asToken(newTokenNum),
                op.name(),
                op.symbol(),
                op.decimals(),
                0, // is this correct ?
                op.treasury(),
                op.adminKey(),
                op.kycKey(),
                op.freezeKey(),
                op.wipeKey(),
                op.supplyKey(),
                op.feeScheduleKey(),
                op.pauseKey(),
                0,
                false,
                op.tokenType(),
                op.supplyType(),
                resolvedExpiryMeta.autoRenewAccountId(),
                resolvedExpiryMeta.autoRenewPeriod(),
                resolvedExpiryMeta.expiry(),
                op.memo(),
                op.maxSupply(),
                false,
                op.freezeDefault(),
                false,
                op.customFees());
    }

    /**
     * Get the expiry metadata for the token to be created from the transaction body.
     * @param consensusTime consensus time
     * @param op token creation transaction body
     * @return given expiry metadata
     */
    private ExpiryMeta getExpiryMeta(final long consensusTime, @NonNull final TokenCreateTransactionBody op) {
        final var impliedExpiry =
                consensusTime + op.autoRenewPeriodOrElse(Duration.DEFAULT).seconds();

        return new ExpiryMeta(
                impliedExpiry,
                op.autoRenewPeriodOrElse(Duration.DEFAULT).seconds(),
                // Shard and realm will be ignored if num is NA
                op.autoRenewAccount());
    }

    /**
     * Validate the semantics of the token creation transaction body, that involves checking with
     * dynamic properties or state.
     * @param context  handle context
     * @param accountStore account store
     * @param op token creation transaction body
     * @param config tokens configuration
     * @return resolved expiry metadata
     */
    private ExpiryMeta validateSemantics(
            @NonNull final HandleContext context,
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final TokenCreateTransactionBody op,
            @NonNull final TokensConfig config) {
        requireNonNull(context);
        requireNonNull(accountStore);
        requireNonNull(op);
        requireNonNull(config);

        // validate different token create fields
        tokenCreateValidator.validate(context, accountStore, op, config);

        // validate expiration and auto-renew account if present
        final var givenExpiryMeta = getExpiryMeta(context.consensusNow().getEpochSecond(), op);
        final var resolvedExpiryMeta = context.expiryValidator().resolveCreationAttempt(false, givenExpiryMeta);

        // validate auto-renew account exists
        if (resolvedExpiryMeta.hasAutoRenewAccountId()) {
            TokenHandlerHelper.getIfUsable(
                    resolvedExpiryMeta.autoRenewAccountId(),
                    accountStore,
                    context.expiryValidator(),
                    INVALID_AUTORENEW_ACCOUNT);
        }
        return resolvedExpiryMeta;
    }

    /* --------------- Helper methods --------------- */

    /**
     * Validates the collector key from the custom fees.
     *
     * @param context given context
     * @param customFeesList list with the custom fees
     */
    private void addCustomFeeCollectorKeys(
            @NonNull final PreHandleContext context, @NonNull final List<CustomFee> customFeesList)
            throws PreCheckException {

        for (final var customFee : customFeesList) {
            final var collector = customFee.feeCollectorAccountIdOrElse(AccountID.DEFAULT);

            /* A fractional fee collector and a collector for a fixed fee denominated
            in the units of the newly created token both must always sign a TokenCreate,
            since these are automatically associated to the newly created token. */
            if (customFee.hasFixedFee()) {
                final var fixedFee = customFee.fixedFeeOrThrow();
                final var alwaysAdd = fixedFee.hasDenominatingTokenId()
                        && fixedFee.denominatingTokenIdOrThrow().tokenNum() == 0L;
                addAccount(context, collector, alwaysAdd);
            } else if (customFee.hasFractionalFee()) {
                context.requireKeyOrThrow(collector, INVALID_CUSTOM_FEE_COLLECTOR);
            } else {
                // TODO: Need to validate if this is actually needed
                final var royaltyFee = customFee.royaltyFeeOrThrow();
                var alwaysAdd = false;
                if (royaltyFee.hasFallbackFee()) {
                    final var fFee = royaltyFee.fallbackFeeOrThrow();
                    alwaysAdd = fFee.hasDenominatingTokenId()
                            && fFee.denominatingTokenIdOrThrow().tokenNum() == 0;
                }
                addAccount(context, collector, alwaysAdd);
            }
        }
    }

    /**
     * Signs the metadata or adds failure status.
     *
     * @param context given context
     * @param collector the ID of the collector
     * @param alwaysAdd if true, will always add the key
     */
    private void addAccount(
            @NonNull final PreHandleContext context, @NonNull final AccountID collector, final boolean alwaysAdd)
            throws PreCheckException {
        if (alwaysAdd) {
            context.requireKeyOrThrow(collector, INVALID_CUSTOM_FEE_COLLECTOR);
        } else {
            context.requireKeyIfReceiverSigRequired(collector, INVALID_CUSTOM_FEE_COLLECTOR);
        }
    }
}

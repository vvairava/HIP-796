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

package com.hedera.node.app.service.token.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSFER_ACCOUNT_ID;
import static com.hedera.hapi.util.HapiUtils.isHollow;
import static com.hedera.node.app.service.token.AliasUtils.isAlias;
import static com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler.isStakingAccount;
import static com.hedera.node.app.service.token.impl.handlers.transfer.CryptoTransferExecutor.executeTransfer;
import static com.hedera.node.app.service.token.impl.handlers.transfer.CryptoTransferExecutor.transferPureChecks;
import static com.hedera.node.app.service.token.impl.util.CryptoTransferValidationHelper.checkReceiver;
import static com.hedera.node.app.service.token.impl.util.CryptoTransferValidationHelper.checkSender;
import static com.hedera.node.app.spi.validation.Validations.validateAccountID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.NftTransfer;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TokenTransferList;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.ReadableTokenStore.TokenMetadata;
import com.hedera.node.app.service.token.impl.handlers.transfer.TransferContextImpl;
import com.hedera.node.app.service.token.impl.util.CryptoTransferFeeCalculator;
import com.hedera.node.app.service.token.impl.validators.CryptoTransferValidator;
import com.hedera.node.app.service.token.records.CryptoTransferRecordBuilder;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.app.spi.workflows.WarmupContext;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.TokensConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link
 * HederaFunctionality#CRYPTO_TRANSFER}.
 */
@Singleton
public class CryptoTransferHandler implements TransactionHandler {
    private final CryptoTransferValidator validator;
    private final boolean enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments;

    /**
     * Default constructor for injection.
     * @param validator the validator to use to validate the transaction
     */
    @Inject
    public CryptoTransferHandler(@NonNull final CryptoTransferValidator validator) {
        this(validator, true);
    }

    /**
     * Constructor for injection with the option to enforce mono-service restrictions on auto-creation custom fee
     * @param validator the validator to use to validate the transaction
     * @param enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments whether to enforce mono-service restrictions
     */
    public CryptoTransferHandler(
            @NonNull final CryptoTransferValidator validator,
            final boolean enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments) {
        this.validator = validator;
        this.enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments =
                enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments;
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        pureChecks(context.body());

        final var op = context.body().cryptoTransferOrThrow();
        final var accountStore = context.createStore(ReadableAccountStore.class);
        final var tokenStore = context.createStore(ReadableTokenStore.class);
        for (final var transfers : op.tokenTransfers()) {
            final var tokenMeta = tokenStore.getTokenMeta(transfers.tokenOrElse(TokenID.DEFAULT));
            if (tokenMeta == null) throw new PreCheckException(INVALID_TOKEN_ID);
            checkFungibleTokenTransfers(transfers.transfers(), context, accountStore, false);
            checkNftTransfers(transfers.nftTransfers(), context, tokenMeta, op, accountStore);
        }

        final var hbarTransfers = op.transfersOrElse(TransferList.DEFAULT).accountAmounts();
        checkFungibleTokenTransfers(hbarTransfers, context, accountStore, true);
    }

    @Override
    public void pureChecks(@NonNull final TransactionBody txn) throws PreCheckException {
        transferPureChecks(validator, txn);
    }

    @Override
    public void warm(@NonNull final WarmupContext context) {
        requireNonNull(context);

        final ReadableAccountStore accountStore = context.createStore(ReadableAccountStore.class);
        final ReadableTokenStore tokenStore = context.createStore(ReadableTokenStore.class);
        final ReadableNftStore nftStore = context.createStore(ReadableNftStore.class);
        final ReadableTokenRelationStore tokenRelationStore = context.createStore(ReadableTokenRelationStore.class);
        final CryptoTransferTransactionBody op = context.body().cryptoTransferOrThrow();

        // warm all accounts from the transfer list
        final TransferList transferList = op.transfersOrElse(TransferList.DEFAULT);
        transferList.accountAmounts().stream()
                .map(AccountAmount::accountID)
                .filter(Objects::nonNull)
                .forEach(accountStore::warm);

        // warm all token-data from the token transfer list
        final List<TokenTransferList> tokenTransfers = op.tokenTransfers();
        tokenTransfers.stream().filter(TokenTransferList::hasToken).forEach(tokenTransferList -> {
            final TokenID tokenID = tokenTransferList.tokenOrThrow();
            final Token token = tokenStore.get(tokenID);
            final AccountID treasuryID = token == null ? null : token.treasuryAccountId();
            if (treasuryID != null) {
                accountStore.warm(treasuryID);
            }
            for (final AccountAmount amount : tokenTransferList.transfers()) {
                amount.ifAccountID(accountID -> tokenRelationStore.warm(accountID, tokenID));
            }
            for (final NftTransfer nftTransfer : tokenTransferList.nftTransfers()) {
                warmNftTransfer(accountStore, tokenStore, nftStore, tokenRelationStore, tokenID, nftTransfer);
            }
        });
    }

    private void warmNftTransfer(
            @NonNull final ReadableAccountStore accountStore,
            @NonNull final ReadableTokenStore tokenStore,
            @NonNull final ReadableNftStore nftStore,
            @NonNull final ReadableTokenRelationStore tokenRelationStore,
            @NonNull final TokenID tokenID,
            @NonNull final NftTransfer nftTransfer) {
        // warm sender
        nftTransfer.ifSenderAccountID(senderAccountID -> {
            final Account sender = accountStore.getAliasedAccountById(senderAccountID);
            if (sender != null) {
                sender.ifHeadNftId(nftStore::warm);
            }
            tokenRelationStore.warm(senderAccountID, tokenID);
        });

        // warm receiver
        nftTransfer.ifReceiverAccountID(receiverAccountID -> {
            final Account receiver = accountStore.getAliasedAccountById(receiverAccountID);
            if (receiver != null) {
                receiver.ifHeadTokenId(headTokenID -> {
                    tokenRelationStore.warm(receiverAccountID, headTokenID);
                    tokenStore.warm(headTokenID);
                });
                receiver.ifHeadNftId(nftStore::warm);
            }
            tokenRelationStore.warm(receiverAccountID, tokenID);
        });

        // warm neighboring NFTs
        final Nft nft = nftStore.get(tokenID, nftTransfer.serialNumber());
        if (nft != null) {
            nft.ifOwnerPreviousNftId(nftStore::warm);
            nft.ifOwnerNextNftId(nftStore::warm);
        }
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var txn = context.body();
        final var op = txn.cryptoTransferOrThrow();

        final var ledgerConfig = context.configuration().getConfigData(LedgerConfig.class);
        final var hederaConfig = context.configuration().getConfigData(HederaConfig.class);
        final var tokensConfig = context.configuration().getConfigData(TokensConfig.class);

        validator.validateSemantics(op, ledgerConfig, hederaConfig, tokensConfig);

        // create a new transfer context that is specific only for this transaction
        final var transferContext =
                new TransferContextImpl(context, enforceMonoServiceRestrictionsOnAutoCreationCustomFeePayments);
        final var recordBuilder = context.recordBuilders().getOrCreate(CryptoTransferRecordBuilder.class);

        executeTransfer(txn, transferContext, context, validator, recordBuilder);
    }

    /**
     * As part of pre-handle, checks that HBAR or fungible token transfers in the transfer list are plausible.
     *
     * @param transfers The transfers to check
     * @param ctx The context we gather signing keys into
     * @param accountStore The account store to use to look up accounts
     * @param hbarTransfer Whether this is a hbar transfer. When HIP-583 is implemented, we can remove this argument.
     * @throws PreCheckException If the transaction is invalid
     */
    private void checkFungibleTokenTransfers(
            @NonNull final List<AccountAmount> transfers,
            @NonNull final PreHandleContext ctx,
            @NonNull final ReadableAccountStore accountStore,
            final boolean hbarTransfer)
            throws PreCheckException {
        // We're going to iterate over all the transfers in the transfer list. Each transfer is known as an
        // "account amount". Each of these represents the transfer of hbar INTO a single account or OUT of a
        // single account.
        for (final var accountAmount : transfers) {
            // Given an accountId, we need to look up the associated account.
            final var accountId = validateAccountID(accountAmount.accountIDOrElse(AccountID.DEFAULT), null);
            final var account = accountStore.getAliasedAccountById(accountId);
            final var isCredit = accountAmount.amount() > 0;
            final var isDebit = accountAmount.amount() < 0;
            if (account != null) {
                // This next code is not right, but we have it for compatibility until after we migrate
                // off the mono-service. Then we can fix this. In this logic, if the receiver account (the
                // one with the credit) doesn't have a key AND the value being sent is non-hbar fungible tokens,
                // then we fail with ACCOUNT_IS_IMMUTABLE. And if the account is being debited and has no key,
                // then we also fail with the same error. It should be that being credited value DOES NOT require
                // a key, unless `receiverSigRequired` is true.
                if (isStakingAccount(ctx.configuration(), account.accountId())
                        && (isDebit || (isCredit && !hbarTransfer))) {
                    // NOTE: should change to ACCOUNT_IS_IMMUTABLE after modularization
                    throw new PreCheckException(INVALID_ACCOUNT_ID);
                }

                // We only need signing keys for accounts that are being debited OR those being credited
                // but with receiverSigRequired set to true. If the account is being debited but "isApproval"
                // is set on the transaction, then we defer to the token transfer logic to determine if all
                // signing requirements were met ("isApproval" is a way for the client to say "I don't need a key
                // because I'm approved which you will see when you handle this transaction").
                if (isDebit && !accountAmount.isApproval()) {
                    // If the account is a hollow account, then we require a signature for it.
                    // It is possible that the hollow account has signed this transaction, in which case
                    // we need to finalize the hollow account by setting its key.
                    if (isHollow(account)) {
                        ctx.requireSignatureForHollowAccount(account);
                    } else {
                        ctx.requireKeyOrThrow(account.key(), INVALID_ACCOUNT_ID);
                    }

                } else if (isCredit && account.receiverSigRequired()) {
                    ctx.requireKeyOrThrow(account.key(), INVALID_TRANSFER_ACCOUNT_ID);
                }
            } else if (hbarTransfer) {
                // It is possible for the transfer to be valid even if the account is not found. For example, we
                // allow auto-creation of "hollow accounts" if you transfer value into an account *by alias* that
                // didn't previously exist. If that is not the case, then we fail because we couldn't find the
                // destination account.
                if (!isCredit || !isAlias(accountId)) {
                    // Interestingly, this means that if the transfer amount is exactly 0 and the account has a
                    // non-existent alias, then we fail.
                    throw new PreCheckException(INVALID_ACCOUNT_ID);
                }
            } else if (isDebit) {
                // All debited accounts must be valid
                throw new PreCheckException(INVALID_ACCOUNT_ID);
            }
        }
    }

    private void checkNftTransfers(
            final List<NftTransfer> nftTransfersList,
            final PreHandleContext meta,
            final TokenMetadata tokenMeta,
            final CryptoTransferTransactionBody op,
            final ReadableAccountStore accountStore)
            throws PreCheckException {
        for (final var nftTransfer : nftTransfersList) {
            final var senderId = nftTransfer.senderAccountIDOrElse(AccountID.DEFAULT);
            validateAccountID(senderId, null);
            checkSender(senderId, nftTransfer, meta, accountStore);

            final var receiverId = nftTransfer.receiverAccountIDOrElse(AccountID.DEFAULT);
            validateAccountID(receiverId, null);
            checkReceiver(receiverId, senderId, nftTransfer, meta, tokenMeta, op, accountStore);
        }
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        final var body = feeContext.body();
        final var op = body.cryptoTransferOrThrow();
        return CryptoTransferFeeCalculator.calculate(feeContext, op.transfers(), op.tokenTransfers());
    }
}

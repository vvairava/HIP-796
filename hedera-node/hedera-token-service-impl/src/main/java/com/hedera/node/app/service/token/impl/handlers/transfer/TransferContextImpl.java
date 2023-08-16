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

package com.hedera.node.app.service.token.impl.handlers.transfer;

import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.node.app.service.mono.utils.EntityIdUtils.EVM_ADDRESS_SIZE;
import static com.hedera.node.app.service.token.impl.handlers.transfer.AliasUtils.isSerializedProtoKey;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.config.data.AutoCreationConfig;
import com.hedera.node.config.data.LazyCreationConfig;
import com.hedera.node.config.data.TokensConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.HashMap;
import java.util.Map;

/**
 * The context of a token transfer. This This is used to pass information between the steps of the transfer.
 */
public class TransferContextImpl implements TransferContext {
    private final WritableAccountStore accountStore;
    private final AutoAccountCreator autoAccountCreator;
    private final HandleContext context;
    private int numAutoCreations;
    private int numLazyCreations;
    private final Map<Bytes, AccountID> resolutions = new HashMap<>();
    private final AutoCreationConfig autoCreationConfig;
    private final LazyCreationConfig lazyCreationConfig;
    private final TokensConfig tokensConfig;

    public TransferContextImpl(final HandleContext context) {
        this.context = context;
        this.accountStore = context.writableStore(WritableAccountStore.class);
        this.autoAccountCreator = new AutoAccountCreator(context);
        this.autoCreationConfig = context.configuration().getConfigData(AutoCreationConfig.class);
        this.lazyCreationConfig = context.configuration().getConfigData(LazyCreationConfig.class);
        this.tokensConfig = context.configuration().getConfigData(TokensConfig.class);
    }

    @Override
    public AccountID getFromAlias(final AccountID aliasedId) {
        final var account = accountStore.get(aliasedId);

        if (account != null) {
            final var id = account.accountId();
            resolutions.put(aliasedId.alias(), id);
            return id;
        }
        return null;
    }

    @Override
    public void createFromAlias(final Bytes alias, final boolean isFromTokenTransfer) {
        // if it is a serialized proto key, auto-create account
        if (isSerializedProtoKey(alias)) {
            validateTrue(autoCreationConfig.enabled(), NOT_SUPPORTED);
            numAutoCreations++;
        } else if (isOfEvmAddressSize(alias)) {
            // if it is an evm address create a hollow account
            validateTrue(lazyCreationConfig.enabled(), NOT_SUPPORTED);
            numLazyCreations++;
        }
        // if this auto creation is from a token transfer, check if auto creation from tokens is enabled
        if (isFromTokenTransfer) {
            validateTrue(tokensConfig.autoCreationsIsEnabled(), NOT_SUPPORTED);
        }
        // Keep the created account in the resolutions map
        final var createdAccount = autoAccountCreator.create(alias, isFromTokenTransfer);
        resolutions.put(alias, createdAccount);
    }

    @Override
    public HandleContext getHandleContext() {
        return context;
    }

    @Override
    public int numOfAutoCreations() {
        return numAutoCreations;
    }

    @Override
    public void chargeExtraFeeToHapiPayer(final long amount) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public Map<Bytes, AccountID> resolutions() {
        return resolutions;
    }

    @Override
    public int numOfLazyCreations() {
        return numLazyCreations;
    }

    public static boolean isOfEvmAddressSize(final Bytes alias) {
        return alias.length() == EVM_ADDRESS_SIZE;
    }
}

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

package com.hedera.services.bdd.spec.dsl.operations.transactions;

import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import com.hedera.services.bdd.spec.dsl.entities.SpecFungibleToken;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * An operation that does the simplest possible adjustment of fungible token balances; i.e. debits a sender and
 * credits a receiver.
 */
public class TransferTokensOperation extends AbstractSpecTransaction<TransferTokensOperation, HapiCryptoTransfer>
        implements SpecOperation {
    private final SpecAccount sender;
    private final SpecAccount receiver;
    private final SpecFungibleToken token;
    private final long units;

    public TransferTokensOperation(
            @NonNull final SpecAccount sender,
            @NonNull final SpecAccount receiver,
            @NonNull final SpecFungibleToken token,
            final long units) {
        super(List.of(sender, receiver, token));
        this.sender = requireNonNull(sender);
        this.receiver = requireNonNull(receiver);
        this.token = requireNonNull(token);
        this.units = units;
    }

    @NonNull
    @Override
    protected SpecOperation computeDelegate(@NonNull final HapiSpec spec) {
        return cryptoTransfer(moving(units, token.name()).between(sender.name(), receiver.name()));
    }

    @Override
    protected TransferTokensOperation self() {
        return this;
    }
}

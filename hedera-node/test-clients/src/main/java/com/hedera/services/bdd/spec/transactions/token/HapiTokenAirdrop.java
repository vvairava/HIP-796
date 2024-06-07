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

package com.hedera.services.bdd.spec.transactions.token;

import com.google.protobuf.ByteString;
import com.google.protobuf.UInt32Value;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.TokenAirdropTransactionBody;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import org.apache.commons.lang3.tuple.Pair;


import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

public class HapiTokenAirdrop extends HapiTxnOp<HapiTokenAirdrop> {

    private List<TokenMovement> tokenAwareProviders = Collections.emptyList();
    private static final Comparator<AccountID> ACCOUNT_NUM_COMPARATOR = Comparator.comparingLong(
                    AccountID::getAccountNum)
            .thenComparingLong(AccountID::getShardNum)
            .thenComparingLong(AccountID::getRealmNum);
    private static final Comparator<TokenID> TOKEN_ID_COMPARATOR = Comparator.comparingLong(TokenID::getTokenNum);
    private static final Comparator<TokenTransferList> TOKEN_TRANSFER_LIST_COMPARATOR =
            (o1, o2) -> Objects.compare(o1.getToken(), o2.getToken(), TOKEN_ID_COMPARATOR);
    private static final Comparator<AccountID> ACCOUNT_NUM_OR_ALIAS_COMPARATOR = (a, b) -> {
        if (!a.getAlias().isEmpty() || !b.getAlias().isEmpty()) {
            return ByteString.unsignedLexicographicalComparator().compare(a.getAlias(), b.getAlias());
        } else {
            return ACCOUNT_NUM_COMPARATOR.compare(a, b);
        }
    };
    private static final Comparator<AccountAmount> ACCOUNT_AMOUNT_COMPARATOR = Comparator.comparingLong(
                    AccountAmount::getAmount)
            .thenComparing(AccountAmount::getAccountID, ACCOUNT_NUM_OR_ALIAS_COMPARATOR);

    private static final Comparator<NftTransfer> NFT_TRANSFER_COMPARATOR = Comparator.comparing(
                    NftTransfer::getSenderAccountID, ACCOUNT_NUM_OR_ALIAS_COMPARATOR)
            .thenComparing(NftTransfer::getReceiverAccountID, ACCOUNT_NUM_OR_ALIAS_COMPARATOR)
            .thenComparingLong(NftTransfer::getSerialNumber);
    private boolean fullyAggregateTokenTransfers = true;
    public HapiTokenAirdrop(final TokenMovement... sources) {
        this.tokenAwareProviders = List.of(sources);
    }


    @Override
    protected long feeFor(HapiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
        return 0;
    }

    @Override
    public HederaFunctionality type() {
        return HederaFunctionality.TokenAirdrop;
    }

    @Override
    protected HapiTokenAirdrop self() {
        return this;
    }

    @Override
    protected Consumer<TransactionBody.Builder> opBodyDef(HapiSpec spec) throws Throwable {
        final TokenAirdropTransactionBody opBody = spec.txns()
                .<TokenAirdropTransactionBody, TokenAirdropTransactionBody.Builder>body(
                        TokenAirdropTransactionBody.class, b -> {
                            final var xfers = transfersAllFor(spec);
                            for (final TokenTransferList scopedXfers : xfers) {
                                b.addTokenTransfers(scopedXfers);
                            }
                        });
        return builder -> builder.setTokenAirdrop(opBody);
    }

    private List<TokenTransferList> transfersAllFor(final HapiSpec spec) {
        return Stream.concat(transfersFor(spec).stream(), transfersForNft(spec).stream())
                .sorted(TOKEN_TRANSFER_LIST_COMPARATOR)
                .toList();
    }

    private List<TokenTransferList> transfersFor(final HapiSpec spec) {
        final Map<TokenID, Pair<Integer, List<AccountAmount>>> aggregated;
        if (fullyAggregateTokenTransfers) {
            aggregated = fullyAggregateTokenTransfersList(spec);
        } else {
            aggregated = aggregateOnTokenIds(spec);
        }

        return aggregated.entrySet().stream()
                .map(entry -> {
                    final var builder = TokenTransferList.newBuilder()
                            .setToken(entry.getKey())
                            .addAllTransfers(entry.getValue().getRight());
                    if (entry.getValue().getLeft() > 0) {
                        builder.setExpectedDecimals(
                                UInt32Value.of(entry.getValue().getLeft().intValue()));
                    }
                    return builder.build();
                })
                .sorted(TOKEN_TRANSFER_LIST_COMPARATOR)
                .toList();
    }

    private Map<TokenID, Pair<Integer, List<AccountAmount>>> aggregateOnTokenIds(final HapiSpec spec) {
        final Map<TokenID, Pair<Integer, List<AccountAmount>>> map = new HashMap<>();
        for (final TokenMovement tm : tokenAwareProviders) {
            if (tm.isFungibleToken()) {
                final var list = tm.specializedFor(spec);

                if (map.containsKey(list.getToken())) {
                    final var existingVal = map.get(list.getToken());
                    final List<AccountAmount> newList = Stream.of(existingVal.getRight(), list.getTransfersList())
                            .flatMap(Collection::stream)
                            .sorted(ACCOUNT_AMOUNT_COMPARATOR)
                            .toList();

                    map.put(list.getToken(), Pair.of(existingVal.getLeft(), newList));
                } else {
                    map.put(list.getToken(), Pair.of(list.getExpectedDecimals().getValue(), list.getTransfersList()));
                }
            }
        }
        return map;
    }

    private Map<TokenID, Pair<Integer, List<AccountAmount>>> fullyAggregateTokenTransfersList(final HapiSpec spec) {
        final Map<TokenID, Pair<Integer, List<AccountAmount>>> map = new HashMap<>();
        for (final TokenMovement xfer : tokenAwareProviders) {
            if (xfer.isFungibleToken()) {
                final var list = xfer.specializedFor(spec);

                if (map.containsKey(list.getToken())) {
                    final var existingVal = map.get(list.getToken());
                    final List<AccountAmount> newList = Stream.of(existingVal.getRight(), list.getTransfersList())
                            .flatMap(Collection::stream)
                            .sorted(ACCOUNT_AMOUNT_COMPARATOR)
                            .toList();

                    map.put(list.getToken(), Pair.of(existingVal.getLeft(), aggregateTransfers(newList)));
                } else {
                    map.put(
                            list.getToken(),
                            Pair.of(
                                    list.getExpectedDecimals().getValue(),
                                    aggregateTransfers(list.getTransfersList())));
                }
            }
        }
        return map;
    }

    private List<AccountAmount> aggregateTransfers(final List<AccountAmount> list) {
        return list.stream()
                .collect(groupingBy(
                        AccountAmount::getAccountID,
                        groupingBy(AccountAmount::getIsApproval, mapping(AccountAmount::getAmount, toList()))))
                .entrySet()
                .stream()
                .flatMap(entry -> {
                    final List<AccountAmount> accountAmounts = new ArrayList<>();
                    for (final var entrySet : entry.getValue().entrySet()) {
                        final var aa = AccountAmount.newBuilder()
                                .setAccountID(entry.getKey())
                                .setIsApproval(entrySet.getKey())
                                .setAmount(entrySet.getValue().stream()
                                        .mapToLong(l -> l)
                                        .sum())
                                .build();
                        accountAmounts.add(aa);
                    }
                    return accountAmounts.stream();
                })
                .sorted(ACCOUNT_AMOUNT_COMPARATOR)
                .toList();
    }

    private List<TokenTransferList> transfersForNft(final HapiSpec spec) {
        final var uniqueCount = tokenAwareProviders.stream()
                .filter(Predicate.not(TokenMovement::isFungibleToken))
                .map(TokenMovement::getToken)
                .distinct()
                .count();
        final Map<TokenID, List<NftTransfer>> aggregated = tokenAwareProviders.stream()
                .filter(Predicate.not(TokenMovement::isFungibleToken))
                .map(p -> p.specializedForNft(spec))
                .collect(Collectors.toMap(
                        TokenTransferList::getToken,
                        TokenTransferList::getNftTransfersList,
                        (left, right) -> Stream.of(left, right)
                                .flatMap(Collection::stream)
                                .sorted(NFT_TRANSFER_COMPARATOR)
                                .toList(),
                        LinkedHashMap::new));
        if (aggregated.size() != 0 && uniqueCount != aggregated.size()) {
            throw new RuntimeException("Aggregation seems to have failed (expected "
                    + uniqueCount
                    + " distinct unique token types, got "
                    + aggregated.size()
                    + ")");
        }
        return aggregated.entrySet().stream()
                .map(entry -> TokenTransferList.newBuilder()
                        .setToken(entry.getKey())
                        .addAllNftTransfers(entry.getValue())
                        .build())
                .sorted(TOKEN_TRANSFER_LIST_COMPARATOR)
                .toList();
    }
}

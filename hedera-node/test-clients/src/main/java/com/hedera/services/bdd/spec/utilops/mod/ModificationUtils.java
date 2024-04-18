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

import static com.hedera.node.app.hapi.utils.CommonUtils.extractTransactionBody;

import com.google.protobuf.Descriptors;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Helpers for creating and using {@link TxnModificationStrategy} and
 * {@link QueryModificationStrategy} instances in {@link HapiSpec}s.
 *
 */
public class ModificationUtils {
    private ModificationUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * Returns a factory that computes a list of {@link TxnModification}s from a
     * {@link Transaction}, where these modifications focus on mutating entity ids.
     * The default entity id modification strategies are:
     * <ul>
     *     <li>{@link BodyIdClearingStrategy} - which one at a time clears any
     *     entity id set in the {@link TransactionBody}.</li>
     * </ul>
     *
     * @return the default entity id modifications factory for transactions
     */
    public static Function<Transaction, List<TxnModification>> withSuccessivelyVariedBodyIds() {
        return withTxnModificationStrategies(List.of(new BodyIdClearingStrategy()));
    }

    /**
     * Returns a factory that computes a list of {@link QueryModification}s from a
     * {@link Query}, where these modifications focus on mutating entity ids.
     * The default entity id modification strategies are:
     * <ul>
     *     <li>{@link QueryIdClearingStrategy} - which one at a time clears any
     *     entity id set in the {@link Query}.</li>
     * </ul>
     *
     * @return the default entity id modifications factory for queries
     */
    public static Function<Query, List<QueryModification>> withSuccessivelyVariedQueryIds() {
        return withQueryModificationStrategies(List.of(new QueryIdClearingStrategy()));
    }

    /**
     * Returns a factory that computes a list of {@link QueryModification}s from a
     * {@link Query}, where these modifications are derived from a given list of
     * {@link QueryModificationStrategy} implementations.
     *
     * @return the modifications factory for queries based on the given strategies
     */
    public static Function<Query, List<QueryModification>> withQueryModificationStrategies(
            @NonNull final List<QueryModificationStrategy> strategies) {
        return query -> modificationsFor(query, strategies);
    }

    /**
     * Returns a factory that computes a list of {@link TxnModification}s from a
     * {@link TransactionBody}, where these modifications are derived from a given list of
     * {@link TxnModificationStrategy} implementations.
     *
     * @return the modifications factory for transactions based on the given strategies
     */
    public static Function<Transaction, List<TxnModification>> withTxnModificationStrategies(
            @NonNull final List<TxnModificationStrategy> strategies) {
        return transaction -> {
            final TransactionBody body;
            try {
                body = extractTransactionBody(transaction);
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalArgumentException(e);
            }
            return modificationsFor(body, strategies);
        };
    }

    /**
     * Returns a copy of the given {@link GeneratedMessageV3} with the given
     * occurrence number of the field identified by the given
     * {@link Descriptors.FieldDescriptor} cleared.
     *
     * @param message the message whose field should be cleared
     * @param descriptor the field descriptor
     * @param targetIndex the occurrence number of the field to clear
     * @return the message with the field cleared
     * @param <T> the type of the message
     */
    public static <T extends GeneratedMessageV3> T withClearedField(
            @NonNull final T message, @NonNull final Descriptors.FieldDescriptor descriptor, final int targetIndex) {
        final var currentIndex = new AtomicInteger(0);
        return withClearedField(message, descriptor, targetIndex, currentIndex);
    }

    private static <T, M extends ModificationStrategy<T>> List<T> modificationsFor(
            @NonNull final GeneratedMessageV3 message, @NonNull final List<M> strategies) {
        final List<T> modifications = new ArrayList<>();
        for (final var strategy : strategies) {
            final var targetFields = getTargetFields(message.toBuilder(), strategy::hasTarget);
            final Map<String, AtomicInteger> occurrenceCounts = new HashMap<>();
            modifications.addAll(targetFields.stream()
                    .map(field -> {
                        final var encounterIndex = occurrenceCounts
                                .computeIfAbsent(field.getFullName(), k -> new AtomicInteger(0))
                                .getAndIncrement();
                        return strategy.modificationForTarget(field, encounterIndex);
                    })
                    .toList());
        }
        return modifications;
    }

    private static <T extends GeneratedMessageV3> T withClearedField(
            @NonNull final T message,
            @NonNull final Descriptors.FieldDescriptor descriptor,
            final int targetIndex,
            @NonNull final AtomicInteger currentIndex) {
        final var builder = message.toBuilder();
        if (descriptor.getContainingType().equals(builder.getDescriptorForType())) {
            final var value = builder.getField(descriptor);
            if (value instanceof List<?> list) {
                final var clearedList = list.stream()
                        .filter(subValue -> currentIndex.getAndIncrement() != targetIndex)
                        .toList();
                builder.setField(descriptor, clearedList);
            } else {
                builder.clearField(descriptor);
            }
        } else {
            builder.getAllFields().forEach((field, value) -> {
                if (value instanceof GeneratedMessageV3 subMessage) {
                    builder.setField(field, withClearedField(subMessage, descriptor, targetIndex, currentIndex));
                } else if (value instanceof List<?> list) {
                    final var clearedList = list.stream()
                            .map(item -> (item instanceof GeneratedMessageV3 subMessageItem)
                                    ? withClearedField(subMessageItem, descriptor, targetIndex, currentIndex)
                                    : item)
                            .toList();
                    builder.setField(field, clearedList);
                }
            });
        }
        return (T) builder.build();
    }

    private static List<Descriptors.FieldDescriptor> getTargetFields(
            @NonNull final Message.Builder builder,
            @NonNull final BiPredicate<Descriptors.FieldDescriptor, Object> filter) {
        final List<Descriptors.FieldDescriptor> descriptors = new ArrayList<>();
        accumulateFields(builder, descriptors, filter);
        System.out.println("Descriptors: " + descriptors);
        return descriptors;
    }

    private static void accumulateFields(
            @NonNull final Message.Builder builder,
            @NonNull final List<Descriptors.FieldDescriptor> descriptors,
            @NonNull final BiPredicate<Descriptors.FieldDescriptor, Object> filter) {
        builder.getAllFields().forEach((field, value) -> {
            if (filter.test(field, value)) {
                descriptors.add(field);
            } else if (value instanceof Message message) {
                accumulateFields(message.toBuilder(), descriptors, filter);
            } else if (value instanceof List<?> list) {
                list.forEach(subValue -> {
                    if (filter.test(field, subValue)) {
                        descriptors.add(field);
                    } else if (subValue instanceof Message subMessage) {
                        accumulateFields(subMessage.toBuilder(), descriptors, filter);
                    }
                });
            }
        });
    }
}

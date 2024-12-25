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

package com.hedera.node.app.hints.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.cryptography.bls.BlsPublicKey;
import com.hedera.node.app.hints.HintsService;
import com.hedera.node.app.hints.handlers.HintsAggregationVoteHandler;
import com.hedera.node.app.hints.handlers.HintsHandlers;
import com.hedera.node.app.hints.handlers.HintsKeyPublicationHandler;
import com.hedera.node.app.hints.handlers.HintsPartialSignatureHandler;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.tss.api.FakeGroupElement;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.info.NodeInfo;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.inject.Singleton;

@Module
public interface HintsModule {
    BlsPublicKey FAKE_BLS_PUBLIC_KEY =
            new BlsPublicKey(new FakeGroupElement(BigInteger.valueOf(666L)), HintsService.SIGNATURE_SCHEMA);

    @Provides
    @Singleton
    static Supplier<NodeInfo> provideSelfNodeInfoSupplier(@NonNull final AppContext appContext) {
        return requireNonNull(appContext).selfNodeInfoSupplier();
    }

    @Provides
    @Singleton
    static Supplier<Configuration> provideConfigSupplier(@NonNull final AppContext appContext) {
        return requireNonNull(appContext).configSupplier();
    }

    @Provides
    @Singleton
    static Function<Bytes, BlsPublicKey> provideKeyParser() {
        return bytes -> FAKE_BLS_PUBLIC_KEY;
    }

    @Provides
    @Singleton
    static HintsKeyLoader provideHintsKeyLoader() {
        return () -> FAKE_BLS_PUBLIC_KEY;
    }

    @Provides
    @Singleton
    static HintsHandlers provideHintsHandlers(
            @NonNull final HintsKeyPublicationHandler keyPublicationHandler,
            @NonNull final HintsAggregationVoteHandler aggregationVoteHandler,
            @NonNull final HintsPartialSignatureHandler partialSignatureHandler) {
        return new HintsHandlers(keyPublicationHandler, aggregationVoteHandler, partialSignatureHandler);
    }
}

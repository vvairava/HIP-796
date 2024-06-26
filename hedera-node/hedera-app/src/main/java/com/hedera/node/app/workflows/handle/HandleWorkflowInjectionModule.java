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

package com.hedera.node.app.workflows.handle;

import com.hedera.node.app.service.contract.impl.ContractServiceImpl;
import com.hedera.node.app.service.contract.impl.handlers.EthereumTransactionHandler;
import com.hedera.node.app.state.WorkingStateAccessor;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.HederaState;
import dagger.Module;
import dagger.Provides;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;
import javax.inject.Singleton;

@Module(includes = {HandlersInjectionModule.class})
public interface HandleWorkflowInjectionModule {
    @Provides
    @Singleton
    static EthereumTransactionHandler provideEthereumTransactionHandler(
            @NonNull final ContractServiceImpl contractService) {
        return contractService.handlers().ethereumTransactionHandler();
    }

    Runnable NO_OP = () -> {};

    @Provides
    static Supplier<AutoCloseableWrapper<HederaState>> provideStateSupplier(
            @NonNull final WorkingStateAccessor workingStateAccessor) {
        return () -> new AutoCloseableWrapper<>(workingStateAccessor.getHederaState(), NO_OP);
    }
}

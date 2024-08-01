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

package com.swirlds.platform.test;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.state.MerkleStateLifecycles;
import com.swirlds.platform.state.MerkleStateRoot;
import com.swirlds.platform.state.PlatformState;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.Round;
import com.swirlds.platform.system.SoftwareVersion;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.system.events.Event;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

public class NoOpMerkleStateLifecycles implements MerkleStateLifecycles {
    @Override
    public void onPreHandle(@NonNull Event event, @NonNull State state) {
        // no-op
    }

    @Override
    public void onHandleConsensusRound(
            @NonNull Round round, @NonNull PlatformState platformState, @NonNull State state) {
        // no-op
    }

    @Override
    public void onStateInitialized(
            @NonNull State state,
            @NonNull Platform platform,
            @NonNull PlatformState platformState,
            @NonNull InitTrigger trigger,
            @Nullable SoftwareVersion previousVersion) {
        // no-op
    }

    @Override
    public void onUpdateWeight(
            @NonNull MerkleStateRoot state, @NonNull AddressBook configAddressBook, @NonNull PlatformContext context) {
        // no-op
    }

    @Override
    public void onNewRecoveredState(@NonNull MerkleStateRoot recoveredState) {
        // no-op
    }
}

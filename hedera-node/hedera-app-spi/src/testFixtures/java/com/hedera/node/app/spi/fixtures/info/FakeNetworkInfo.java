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

package com.hedera.node.app.spi.fixtures.info;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.info.SelfNodeInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Holds fake network information for testing.
 */
public class FakeNetworkInfo implements NetworkInfo {
    private static final Bytes DEV_LEDGER_ID = Bytes.wrap(new byte[] {0x03});

    private static final List<NodeInfo> FAKE_NODE_INFOS = List.of(
            fakeInfoWith(
                    false, 2L, "Alpha", AccountID.newBuilder().accountNum(3).build()),
            fakeInfoWith(true, 4L, "Bravo", AccountID.newBuilder().accountNum(4).build()),
            fakeInfoWith(
                    false, 8L, "Charlie", AccountID.newBuilder().accountNum(5).build()));

    @NonNull
    @Override
    public Bytes ledgerId() {
        return DEV_LEDGER_ID;
    }

    @NonNull
    @Override
    public SelfNodeInfo selfNodeInfo() {
        return new SelfNodeInfo() {
            @NonNull
            @Override
            public SemanticVersion hapiVersion() {
                return SemanticVersion.DEFAULT;
            }

            @NonNull
            @Override
            public SemanticVersion appVersion() {
                return SemanticVersion.DEFAULT;
            }

            @Override
            public boolean zeroStake() {
                return FAKE_NODE_INFOS.get(0).zeroStake();
            }

            @Override
            public long nodeId() {
                return FAKE_NODE_INFOS.get(0).nodeId();
            }

            @Override
            public AccountID accountId() {
                return FAKE_NODE_INFOS.get(0).accountId();
            }

            @Override
            public String memo() {
                return FAKE_NODE_INFOS.get(0).memo();
            }
        };
    }

    @NonNull
    @Override
    public List<NodeInfo> addressBook() {
        return FAKE_NODE_INFOS;
    }

    @Nullable
    @Override
    public NodeInfo nodeInfo(long nodeId) {
        return FAKE_NODE_INFOS.get((int) nodeId);
    }

    private static NodeInfo fakeInfoWith(
            final boolean zeroStake,
            final long nodeId,
            @NonNull final String memo,
            @NonNull final AccountID nodeAccountId) {
        return new NodeInfo() {
            @Override
            public long nodeId() {
                return nodeId;
            }

            @Override
            public String memo() {
                return memo;
            }

            @Override
            public boolean zeroStake() {
                return zeroStake;
            }

            @Override
            public AccountID accountId() {
                return nodeAccountId;
            }
        };
    }
}

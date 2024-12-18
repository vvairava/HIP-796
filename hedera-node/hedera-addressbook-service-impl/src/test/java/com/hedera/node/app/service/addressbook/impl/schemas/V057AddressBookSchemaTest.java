// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.schemas;

import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_KEY;
import static com.hedera.node.app.service.addressbook.impl.test.handlers.AddressBookTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.service.addressbook.impl.test.handlers.AddressBookTestBase.WITH_ROSTER_LIFECYCLE;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeMetadata;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.StartupNetworks;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class V057AddressBookSchemaTest {
    private static final Network NETWORK = Network.newBuilder()
            .nodeMetadata(
                    NodeMetadata.newBuilder()
                            .node(Node.newBuilder().nodeId(1L).description("A"))
                            .build(),
                    NodeMetadata.DEFAULT,
                    NodeMetadata.newBuilder()
                            .node(Node.newBuilder().nodeId(2L).description("B"))
                            .build())
            .build();

    @Mock
    private StartupNetworks startupNetworks;

    @Mock
    private MigrationContext ctx;

    @Mock
    private WritableStates writableStates;

    @Mock
    private WritableKVState<EntityNumber, Node> nodes;

    private final V057AddressBookSchema subject = new V057AddressBookSchema();

    @Test
    void migrationIsNoOpIfRosterLifecycleNotEnabled() {
        given(ctx.appConfig()).willReturn(DEFAULT_CONFIG);

        subject.restart(ctx);

        verifyNoMoreInteractions(ctx);
    }

    @Test
    void usesGenesisNodeMetadataIfPresent() {
        given(ctx.appConfig()).willReturn(WITH_ROSTER_LIFECYCLE);
        given(ctx.startupNetworks()).willReturn(startupNetworks);
        given(startupNetworks.genesisNetworkOrThrow(DEFAULT_CONFIG)).willReturn(NETWORK);
        given(ctx.newStates()).willReturn(writableStates);
        given(ctx.isGenesis()).willReturn(true);
        given(writableStates.<EntityNumber, Node>get(NODES_KEY)).willReturn(nodes);

        subject.migrate(ctx);

        verify(nodes)
                .put(new EntityNumber(1L), NETWORK.nodeMetadata().getFirst().nodeOrThrow());
        verify(nodes).put(new EntityNumber(2L), NETWORK.nodeMetadata().getLast().nodeOrThrow());
    }

    @Test
    void usesOverrideMetadataIfPresent() {
        given(ctx.appConfig()).willReturn(WITH_ROSTER_LIFECYCLE);
        given(ctx.startupNetworks()).willReturn(startupNetworks);
        given(startupNetworks.overrideNetworkFor(0L)).willReturn(Optional.of(NETWORK));
        given(ctx.newStates()).willReturn(writableStates);
        given(writableStates.<EntityNumber, Node>get(NODES_KEY)).willReturn(nodes);

        subject.restart(ctx);

        verify(nodes)
                .put(new EntityNumber(1L), NETWORK.nodeMetadata().getFirst().nodeOrThrow());
        verify(nodes).put(new EntityNumber(2L), NETWORK.nodeMetadata().getLast().nodeOrThrow());
    }
}

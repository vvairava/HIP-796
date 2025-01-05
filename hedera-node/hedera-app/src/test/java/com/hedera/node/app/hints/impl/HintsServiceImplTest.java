/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
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

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.node.app.hints.WritableHintsStore;
import com.hedera.node.app.roster.ActiveRosters;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.SchemaRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HintsServiceImplTest {
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L, 890);

    @Mock
    private ActiveRosters activeRosters;

    @Mock
    private WritableHintsStore hintsStore;

    @Mock
    private SchemaRegistry schemaRegistry;

    private final HintsServiceImpl subject = new HintsServiceImpl();

    @Test
    void nothingSupportedYet() {
        assertThrows(
                UnsupportedOperationException.class, () -> subject.reconcile(activeRosters, hintsStore, CONSENSUS_NOW));
        assertThrows(UnsupportedOperationException.class, () -> subject.registerSchemas(schemaRegistry));
        assertThrows(UnsupportedOperationException.class, subject::isReady);
        assertThrows(UnsupportedOperationException.class, () -> subject.signFuture(Bytes.EMPTY));
    }
}

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

package com.swirlds.platform.event.validation;

import com.hedera.wiring.component.InputWireLabel;
import com.swirlds.platform.consensus.EventWindow;
import com.swirlds.platform.event.GossipEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Verifies event signatures
 */
public interface EventSignatureValidator {

    /**
     * Validate event signature
     *
     * @param event the event to verify the signature of
     * @return the event if the signature is valid, otherwise null
     */
    @InputWireLabel("GossipEvent")
    @Nullable
    GossipEvent validateSignature(@NonNull final GossipEvent event);

    /**
     * Set the event window that defines the minimum threshold required for an event to be non-ancient
     *
     * @param eventWindow the event window
     */
    @InputWireLabel("event window")
    void setEventWindow(@NonNull final EventWindow eventWindow);

    /**
     * Set the previous and current address books
     *
     * @param addressBookUpdate the new address books
     */
    @InputWireLabel("AddressBookUpdate")
    void updateAddressBooks(@NonNull final AddressBookUpdate addressBookUpdate);
}

/*
 * Copyright (C) 2022-2024 Hedera Hashgraph, LLC
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

package com.swirlds.platform.gui;

import static com.swirlds.platform.gui.GuiUtils.winRect;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.Console;
import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.gui.internal.SwirldMenu;
import com.swirlds.platform.roster.RosterUtils;
import com.swirlds.platform.state.address.AddressBookNetworkUtils;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.address.Address;
import com.swirlds.platform.system.address.AddressBook;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.Set;
import javax.swing.JFrame;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utilities for creating GUI elements.
 */
public final class SwirldsGui {

    private static final Logger logger = LogManager.getLogger(SwirldsGui.class);

    private SwirldsGui() {}

    /**
     * Create a new window with a text console, of the recommended size and location, including the Swirlds menu.
     *
     * @param platform the platform to create the console with
     * @param visible  should the window be initially visible? If not, call setVisible(true) later.
     * @param nodesToStart the set of nodes to start windows for
     * @return the new window
     */
    public static Console createConsole(
            @NonNull final Platform platform,
            final int winNum,
            final boolean visible,
            @NonNull final Set<NodeId> nodesToStart) {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        final NodeId selfId = platform.getSelfId();
        final int winCount = nodesToStart.size();
        final Roster roster = platform.getRoster();
        final Rectangle winRect = winRect(winCount, winNum);
        // if SwirldMain calls createConsole, this remembers the window created
        final Console console = GuiUtils.createBasicConsole(RosterUtils.formatNodeName(selfId.id()), winRect, visible);
        SwirldMenu.addTo(platform, console.getWindow(), 40, Color.white, false);
        return console;
    }
}

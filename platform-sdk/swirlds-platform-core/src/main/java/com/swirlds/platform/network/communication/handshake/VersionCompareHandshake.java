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

package com.swirlds.platform.network.communication.handshake;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.io.SelfSerializable;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.NetworkProtocolException;
import com.swirlds.platform.network.protocol.ProtocolRunnable;
import com.swirlds.platform.system.SoftwareVersion;
import java.io.IOException;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Exchanges software versions with the peer, either throws a {@link HandshakeException} or logs an error if the versions
 * do not match
 */
public class VersionCompareHandshake implements ProtocolRunnable {
    private static final Logger logger = LogManager.getLogger(VersionCompareHandshake.class);
    private final SoftwareVersion version;
    private final boolean throwOnMismatch;

    /**
     * Calls {@link #VersionCompareHandshake(SoftwareVersion, boolean)} with throwOnMismatch set to true
     */
    public VersionCompareHandshake(final SoftwareVersion version) {
        this(version, true);
    }

    /**
     * @param version
     * 		the version of software this node is running
     * @param throwOnMismatch
     * 		if set to true, the protocol will throw an exception on a version mismatch. if set to false, it will log an
     * 		error and continue
     * @throws NullPointerException in case {@code version} parameter is {@code null}
     */
    public VersionCompareHandshake(final SoftwareVersion version, final boolean throwOnMismatch) {
        Objects.requireNonNull(version, "version must not be null");
        this.version = version;
        this.throwOnMismatch = throwOnMismatch;
    }

    @Override
    public void runProtocol(final Connection connection)
            throws NetworkProtocolException, IOException, InterruptedException {
        connection.getDos().writeSerializable(version, true);
        connection.getDos().flush();
        final SelfSerializable peerVersion = connection.getDis().readSerializable();
        if (!(peerVersion instanceof SoftwareVersion sv) || version.compareTo(sv) != 0) {
            final String message = String.format(
                    "Incompatible versions. Self version is '%s', peer version is '%s'", version, peerVersion);
            if (throwOnMismatch) {
                throw new HandshakeException(message);
            } else {
                logger.error(EXCEPTION.getMarker(), message);
            }
        }
    }
}

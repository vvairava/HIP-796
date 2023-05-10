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

package com.swirlds.platform.heartbeats;

import static com.swirlds.common.utility.CompareTo.isGreaterThanOrEqualTo;

import com.swirlds.common.system.NodeId;
import com.swirlds.common.time.Time;
import com.swirlds.platform.network.ByteConstants;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.NetworkMetrics;
import com.swirlds.platform.network.NetworkProtocolException;
import com.swirlds.platform.network.protocol.Protocol;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Sends a heartbeat to the other node and measures the time it takes to receive a response.
 */
public class HeartbeatProtocol implements Protocol {
    /**
     * ID of the peer
     */
    private final NodeId peerId;

    /**
     * The last time a heartbeat protocol was executed
     */
    private Instant lastHeartbeatTime = Instant.MIN;

    /**
     * The period at which the heartbeat protocol should be executed
     */
    private final Duration heartbeatPeriod;

    /**
     * Network metrics, for recording roundtrip heartbeat time
     */
    private final NetworkMetrics networkMetrics;

    /**
     * Source of time
     */
    private final Time time;

    /**
     * Constructor
     *
     * @param peerId          ID of the peer
     * @param heartbeatPeriod The period (milliseconds) at which this protocol should execute
     * @param networkMetrics  Network metrics, for recording roundtrip heartbeat time
     * @param time            Source of time
     */
    public HeartbeatProtocol(
            @NonNull final NodeId peerId,
            @NonNull final Duration heartbeatPeriod,
            @NonNull final NetworkMetrics networkMetrics,
            @NonNull final Time time) {

        this.peerId = Objects.requireNonNull(peerId);
        this.heartbeatPeriod = Objects.requireNonNull(heartbeatPeriod);
        this.networkMetrics = Objects.requireNonNull(networkMetrics);
        this.time = Objects.requireNonNull(time);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Returns true if the last heartbeat protocol was started more than {@link #heartbeatPeriod} ago
     */
    @Override
    public boolean shouldInitiate() {
        final Duration elapsed = Duration.between(lastHeartbeatTime, time.now());

        return isGreaterThanOrEqualTo(elapsed, heartbeatPeriod);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldAccept() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean acceptOnSimultaneousInitiate() {
        return true;
    }

    /**
     * Send a heartbeat to the peer, and wait to receive the peer's heartbeat
     *
     * @param connection the connection to the peer
     * @return the nanosecond time at which we sent our heartbeat. this is the beginning measurement of the roundtrip
     * time
     * @throws IOException              if there is an error sending or receiving the heartbeat
     * @throws NetworkProtocolException if the peer sends an unexpected message
     */
    private long initiateHeartbeat(final @NonNull Connection connection) throws IOException, NetworkProtocolException {
        connection.getDos().write(ByteConstants.HEARTBEAT);
        connection.getDos().flush();

        // begin measurement after flushing the output stream, so that write time isn't included in the measurement
        final long startTime = time.nanoTime();

        final byte readByte = connection.getDis().readByte();
        if (readByte != ByteConstants.HEARTBEAT) {
            throw new NetworkProtocolException(
                    String.format("received %02x but expected %02x (HEARTBEAT)", readByte, ByteConstants.HEARTBEAT));
        }

        return startTime;
    }

    /**
     * Send a heartbeat acknowledgement, and wait to receive the peer's heartbeat acknowledgement
     *
     * @param connection the connection to the peer
     * @return the nanosecond time at which we received the peer's heartbeat acknowledgement. this is the end of the
     * roundtrip measurement
     * @throws IOException              if there is an error sending or receiving the heartbeat acknowledgement
     * @throws NetworkProtocolException if the peer sends an unexpected message
     */
    private long acknowledgeHeartbeat(final @NonNull Connection connection)
            throws IOException, NetworkProtocolException {

        connection.getDos().write(ByteConstants.HEARTBEAT_ACK);
        connection.getDos().flush();

        final byte readByte = connection.getDis().readByte();
        if (readByte != ByteConstants.HEARTBEAT_ACK) {
            throw new NetworkProtocolException(String.format(
                    "received %02x but expected %02x (HEARTBEAT_ACK)", readByte, ByteConstants.HEARTBEAT_ACK));
        }

        return time.nanoTime();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void runProtocol(final @NonNull Connection connection)
            throws NetworkProtocolException, IOException, InterruptedException {

        // record the time prior to executing the protocol, so that heartbeatPeriod represents a true period, as
        // opposed to a sleep time
        lastHeartbeatTime = time.now();

        final long startTime = initiateHeartbeat(connection);
        final long endTime = acknowledgeHeartbeat(connection);

        networkMetrics.recordPingTime(peerId, endTime - startTime);
    }
}

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

package com.swirlds.platform.event.preconsensus;

import static com.swirlds.common.formatting.StringFormattingUtils.commaSeparatedNumber;
import static com.swirlds.common.units.TimeUnit.UNIT_MILLISECONDS;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.swirlds.base.time.Time;
import com.swirlds.common.formatting.UnitFormatter;
import com.swirlds.common.io.IOIterator;
import com.swirlds.common.wiring.wires.output.StandardOutputWire;
import com.swirlds.platform.event.GossipEvent;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.wiring.NoInput;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class encapsulates the logic for replaying preconsensus events at boot up time.
 */
public class PcesReplayer {
    private static final Logger logger = LogManager.getLogger(PcesReplayer.class);

    private final Time time;

    private final StandardOutputWire<GossipEvent> eventOutputWire;

    private final Runnable flushIntake;
    private final Runnable flushTransactionHandling;

    private final Supplier<ReservedSignedState> latestImmutableState;

    /**
     * Constructor
     *
     * @param time                     a source of time
     * @param eventOutputWire          the wire to put events on, to be replayed
     * @param flushIntake              a runnable that flushes the intake pipeline
     * @param flushTransactionHandling a runnable that flushes the transaction handling pipeline
     * @param latestImmutableState     a supplier of the latest immutable state
     */
    public PcesReplayer(
            final @NonNull Time time,
            final @NonNull StandardOutputWire<GossipEvent> eventOutputWire,
            final @NonNull Runnable flushIntake,
            final @NonNull Runnable flushTransactionHandling,
            final @NonNull Supplier<ReservedSignedState> latestImmutableState) {

        this.time = Objects.requireNonNull(time);
        this.eventOutputWire = Objects.requireNonNull(eventOutputWire);
        this.flushIntake = Objects.requireNonNull(flushIntake);
        this.flushTransactionHandling = Objects.requireNonNull(flushTransactionHandling);
        this.latestImmutableState = Objects.requireNonNull(latestImmutableState);
    }

    /**
     * Log information about the replay
     *
     * @param timestampBeforeReplay the consensus timestamp before replay
     * @param roundBeforeReplay     the round before replay
     * @param eventCount            the number of events replayed
     * @param transactionCount      the number of transactions replayed
     * @param elapsedTime           the elapsed wall clock time during replay
     */
    private void logReplayInfo(
            @Nullable final Instant timestampBeforeReplay,
            final long roundBeforeReplay,
            final long eventCount,
            final long transactionCount,
            @NonNull final Duration elapsedTime) {

        try (final ReservedSignedState stateAfterReplay = latestImmutableState.get()) {
            if (stateAfterReplay == null || stateAfterReplay.isNull()) {
                logger.info(
                        STARTUP.getMarker(),
                        "Replayed {} preconsensus events. No rounds reached consensus.",
                        commaSeparatedNumber(eventCount));
                return;
            }

            final long roundAfterReplay = stateAfterReplay.get().getRound();
            final long elapsedRounds = roundAfterReplay - roundBeforeReplay;

            final Instant timestampAfterReplay = stateAfterReplay.get().getConsensusTimestamp();

            final Duration elapsedConsensusTime;
            if (timestampBeforeReplay != null) {
                elapsedConsensusTime = Duration.between(timestampBeforeReplay, timestampAfterReplay);
            } else {
                elapsedConsensusTime = null;
            }

            logger.info(
                    STARTUP.getMarker(),
                    "Replayed {} preconsensus events. These events contained {} transactions. "
                            + "{} rounds reached consensus spanning {} of consensus time. The latest "
                            + "round to reach consensus is round {}. Replay took {}.",
                    commaSeparatedNumber(eventCount),
                    commaSeparatedNumber(transactionCount),
                    commaSeparatedNumber(elapsedRounds),
                    elapsedConsensusTime != null
                            ? new UnitFormatter(elapsedConsensusTime.toMillis(), UNIT_MILLISECONDS)
                                    .setAbbreviate(false)
                                    .render()
                            : "n/a",
                    commaSeparatedNumber(roundAfterReplay),
                    new UnitFormatter(elapsedTime.toMillis(), UNIT_MILLISECONDS)
                            .setAbbreviate(false)
                            .render());
        }
    }

    /**
     * Replays preconsensus events from disk.
     *
     * @param eventIterator an iterator over the events in the preconsensus stream
     * @return a trigger object indicating when the replay is complete
     */
    @NonNull
    public NoInput replayPces(@NonNull final IOIterator<GossipEvent> eventIterator) {
        Objects.requireNonNull(eventIterator);

        final Instant start = time.now();
        final Instant timestampBeforeReplay;
        final long roundBeforeReplay;
        try (final ReservedSignedState startState = latestImmutableState.get()) {
            if (startState == null || startState.isNull()) {
                timestampBeforeReplay = null;
                roundBeforeReplay = -1;
            } else {
                timestampBeforeReplay = startState.get().getConsensusTimestamp();
                roundBeforeReplay = startState.get().getRound();
            }
        }

        int eventCount = 0;
        int transactionCount = 0;
        try {
            while (eventIterator.hasNext()) {
                final GossipEvent event = eventIterator.next();

                eventCount++;
                transactionCount += event.getHashedData().getTransactions().length;

                eventOutputWire.forward(event);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("error encountered while reading from the PCES", e);
        }

        flushIntake.run();
        flushTransactionHandling.run();

        final Duration elapsedTime = Duration.between(start, time.now());

        logReplayInfo(timestampBeforeReplay, roundBeforeReplay, eventCount, transactionCount, elapsedTime);

        return NoInput.getInstance();
    }
}

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

package com.swirlds.platform.recovery;

import static com.swirlds.common.test.fixtures.RandomUtils.getRandomPrintSeed;
import static com.swirlds.platform.recovery.RecoveryTestUtils.generateRandomEvents;
import static com.swirlds.platform.recovery.RecoveryTestUtils.getLastEventStreamFile;
import static com.swirlds.platform.recovery.RecoveryTestUtils.getMiddleEventStreamFile;
import static com.swirlds.platform.recovery.RecoveryTestUtils.truncateFile;
import static com.swirlds.platform.recovery.RecoveryTestUtils.writeRandomEventStream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.io.IOIterator;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.io.utility.TemporaryFileBuilder;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.recovery.internal.EventStreamPathIterator;
import com.swirlds.platform.recovery.internal.EventStreamRoundIterator;
import com.swirlds.platform.system.Round;
import com.swirlds.platform.system.address.AddressBook;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EventStreamRoundIterator Test")
class EventStreamRoundIteratorTest {

    public static void assertEventsAreEqual(final EventImpl expected, final EventImpl actual) {
        assertEquals(expected.getBaseEvent(), actual.getBaseEvent());
        assertEquals(expected.getConsensusData(), actual.getConsensusData());
    }

    @Test
    @DisplayName("Read All Events Test")
    void readAllEventsTest() throws ConstructableRegistryException, IOException, NoSuchAlgorithmException {
        ConstructableRegistry.getInstance().registerConstructables("com.swirlds");

        final Random random = getRandomPrintSeed();
        final Path directory = TemporaryFileBuilder.buildTemporaryDirectory();

        final int durationInSeconds = 100;
        final int secondsPerFile = 2;

        final List<EventImpl> events = generateRandomEvents(random, 1L, Duration.ofSeconds(durationInSeconds), 1, 20);

        writeRandomEventStream(random, directory, secondsPerFile, events);

        try (final IOIterator<Round> iterator = new EventStreamRoundIterator(
                mock(AddressBook.class), directory, EventStreamPathIterator.FIRST_ROUND_AVAILABLE, true)) {

            final List<EventImpl> deserializedEvents = new ArrayList<>();

            while (iterator.hasNext()) {

                final Round peekRound = iterator.peek();
                final Round nextRound = iterator.next();
                assertSame(peekRound, nextRound, "peek returned wrong object");

                nextRound.iterator().forEachRemaining(event -> {
                    deserializedEvents.add((EventImpl) event);
                    assertEquals(
                            nextRound.getRoundNum(), ((EventImpl) event).getRoundReceived(), "event in wrong round");
                });
            }

            assertEquals(events.size(), deserializedEvents.size(), "wrong number of events read");

            for (int i = 0; i < events.size(); i++) {
                assertEventsAreEqual(events.get(i), deserializedEvents.get(i));
            }
        } finally {
            FileUtils.deleteDirectory(directory);
        }
    }

    @Test
    @DisplayName("Read All Events Starting From Round Test")
    void readAllEventsStartingFromRoundTest()
            throws ConstructableRegistryException, IOException, NoSuchAlgorithmException {

        ConstructableRegistry.getInstance().registerConstructables("com.swirlds");

        final Random random = getRandomPrintSeed();
        final Path directory = TemporaryFileBuilder.buildTemporaryDirectory();

        final int durationInSeconds = 100;
        final int secondsPerFile = 2;
        final long firstRoundToRead = 10;

        final List<EventImpl> events = generateRandomEvents(random, 1L, Duration.ofSeconds(durationInSeconds), 1, 20);

        final List<EventImpl> eventsToBeReturned = new ArrayList<>();
        events.forEach(event -> {
            if (event.getRoundReceived() >= firstRoundToRead) {
                eventsToBeReturned.add(event);
            }
        });

        writeRandomEventStream(random, directory, secondsPerFile, events);

        try (final IOIterator<Round> iterator =
                new EventStreamRoundIterator(mock(AddressBook.class), directory, firstRoundToRead, true)) {

            final List<EventImpl> deserializedEvents = new ArrayList<>();

            while (iterator.hasNext()) {

                final Round peekRound = iterator.peek();
                final Round nextRound = iterator.next();
                assertSame(peekRound, nextRound, "peek returned wrong object");

                assertTrue(
                        nextRound.getRoundNum() >= firstRoundToRead, "low rounds should not be returned for this test");

                nextRound.iterator().forEachRemaining(event -> {
                    deserializedEvents.add((EventImpl) event);
                    assertEquals(
                            nextRound.getRoundNum(), ((EventImpl) event).getRoundReceived(), "event in wrong round");
                });
            }

            assertEquals(eventsToBeReturned.size(), deserializedEvents.size(), "wrong number of events read");

            for (int i = 0; i < eventsToBeReturned.size(); i++) {
                assertEventsAreEqual(eventsToBeReturned.get(i), deserializedEvents.get(i));
            }
        } finally {
            FileUtils.deleteDirectory(directory);
        }
    }

    @Test
    @DisplayName("Missing Event File Test")
    void missingEventFileTest() throws ConstructableRegistryException, IOException, NoSuchAlgorithmException {
        ConstructableRegistry.getInstance().registerConstructables("com.swirlds");

        final Random random = getRandomPrintSeed();
        final Path directory = TemporaryFileBuilder.buildTemporaryDirectory();

        final int durationInSeconds = 100;
        final int secondsPerFile = 2;

        final List<EventImpl> events = generateRandomEvents(random, 1L, Duration.ofSeconds(durationInSeconds), 1, 20);

        writeRandomEventStream(random, directory, secondsPerFile, events);

        Files.delete(getMiddleEventStreamFile(directory));

        boolean exception = false;

        try (final IOIterator<Round> iterator = new EventStreamRoundIterator(
                mock(AddressBook.class), directory, EventStreamPathIterator.FIRST_ROUND_AVAILABLE, true)) {

            final List<EventImpl> deserializedEvents = new ArrayList<>();

            try {
                while (iterator.hasNext()) {

                    final Round peekRound = iterator.peek();
                    final Round nextRound = iterator.next();
                    assertSame(peekRound, nextRound, "peek returned wrong object");

                    nextRound.iterator().forEachRemaining(event -> {
                        deserializedEvents.add((EventImpl) event);
                        assertEquals(
                                nextRound.getRoundNum(),
                                ((EventImpl) event).getRoundReceived(),
                                "event in wrong round");
                    });
                }
            } catch (final IOException e) {
                if (e.getMessage().contains("does not contain any events")) {
                    // The last file had too few events and the truncated file had no events.
                    // This happens randomly, but especially when the original file has 3 or less events in it.
                    // abort the unit tests in a successful state.
                    return;
                } else {
                    throw e;
                }
            }

            assertEquals(events.size(), deserializedEvents.size(), "wrong number of events read");

            for (int i = 0; i < events.size(); i++) {
                assertEquals(events.get(i), deserializedEvents.get(i), "event was deserialized incorrectly");
            }
        } catch (final IOException e) {
            exception = true;
        } finally {
            FileUtils.deleteDirectory(directory);
        }

        assertTrue(exception, "should have been unable to complete iteration with missing file");
    }

    @Test
    @DisplayName("Early Rounds Not Present Test")
    void earlyRoundsNotPresentTest() throws ConstructableRegistryException, IOException, NoSuchAlgorithmException {
        ConstructableRegistry.getInstance().registerConstructables("com.swirlds");

        final Random random = getRandomPrintSeed();
        final Path directory = TemporaryFileBuilder.buildTemporaryDirectory();

        final int durationInSeconds = 100;
        final int secondsPerFile = 2;

        final List<EventImpl> events = generateRandomEvents(random, 100L, Duration.ofSeconds(durationInSeconds), 1, 20);

        writeRandomEventStream(random, directory, secondsPerFile, events);

        assertThrows(
                NoSuchElementException.class,
                () -> new EventStreamRoundIterator(mock(AddressBook.class), directory, 10, true),
                "should be unable to start at requested round");

        FileUtils.deleteDirectory(directory);
    }

    @Test
    @DisplayName("Read All Events Truncated File Test")
    void readAllEventsTruncatedFileTest() throws ConstructableRegistryException, IOException, NoSuchAlgorithmException {
        ConstructableRegistry.getInstance().registerConstructables("com.swirlds");

        final Random random = getRandomPrintSeed();
        final Path directory = TemporaryFileBuilder.buildTemporaryDirectory();

        final int durationInSeconds = 100;
        final int secondsPerFile = 2;

        final List<EventImpl> events = generateRandomEvents(random, 1L, Duration.ofSeconds(durationInSeconds), 1, 20);

        writeRandomEventStream(random, directory, secondsPerFile, events);

        final Path lastFile = getLastEventStreamFile(directory);
        truncateFile(lastFile, false);

        try (final IOIterator<Round> iterator = new EventStreamRoundIterator(
                mock(AddressBook.class), directory, EventStreamPathIterator.FIRST_ROUND_AVAILABLE, true)) {

            final List<EventImpl> deserializedEvents = new ArrayList<>();

            try {
                while (iterator.hasNext()) {

                    final Round peekRound = iterator.peek();
                    final Round nextRound = iterator.next();
                    assertSame(peekRound, nextRound, "peek returned wrong object");

                    nextRound.iterator().forEachRemaining(event -> {
                        deserializedEvents.add((EventImpl) event);
                        assertEquals(
                                nextRound.getRoundNum(),
                                ((EventImpl) event).getRoundReceived(),
                                "event in wrong round");
                    });
                }
            } catch (final IOException e) {
                if (e.getMessage().contains("does not contain any events")) {
                    // The last file had too few events and the truncated file had no events.
                    // This happens randomly, but especially when the original file has 3 or less events in it.
                    // abort the unit tests in a successful state.
                    return;
                } else {
                    throw e;
                }
            }

            assertTrue(events.size() > deserializedEvents.size(), "all original events should not be read");

            for (int i = 0; i < deserializedEvents.size(); i++) {
                assertEventsAreEqual(events.get(i), deserializedEvents.get(i));
            }
        } finally {
            FileUtils.deleteDirectory(directory);
        }
    }

    @Disabled("This test is disabled because it is flaky. Fails ~1/10 times, but only when run remotely.")
    @Test
    @DisplayName("Read Complete Rounds Truncated File Test")
    void readCompleteRoundsTruncatedFileTest()
            throws ConstructableRegistryException, IOException, NoSuchAlgorithmException {

        ConstructableRegistry.getInstance().registerConstructables("com.swirlds");

        final Random random = getRandomPrintSeed();
        final Path directory = TemporaryFileBuilder.buildTemporaryDirectory();

        final int durationInSeconds = 100;
        final int secondsPerFile = 2;

        final List<EventImpl> events = generateRandomEvents(random, 1L, Duration.ofSeconds(durationInSeconds), 1, 20);

        writeRandomEventStream(random, directory, secondsPerFile, events);

        final Path lastFile = getLastEventStreamFile(directory);
        truncateFile(lastFile, false);

        try (final IOIterator<Round> iterator = new EventStreamRoundIterator(
                mock(AddressBook.class), directory, EventStreamPathIterator.FIRST_ROUND_AVAILABLE, false)) {

            final List<EventImpl> deserializedEvents = new ArrayList<>();

            while (iterator.hasNext()) {

                final Round peekRound = iterator.peek();
                final Round nextRound = iterator.next();
                assertSame(peekRound, nextRound, "peek returned wrong object");

                nextRound.iterator().forEachRemaining(event -> {
                    deserializedEvents.add((EventImpl) event);
                    assertEquals(
                            nextRound.getRoundNum(), ((EventImpl) event).getRoundReceived(), "event in wrong round");
                });
            }

            assertTrue(events.size() > deserializedEvents.size(), "all original events should not be read");

            for (int i = 0; i < deserializedEvents.size(); i++) {
                assertEventsAreEqual(events.get(i), deserializedEvents.get(i));
            }

            assertTrue(
                    deserializedEvents.get(deserializedEvents.size() - 1).isLastInRoundReceived(),
                    "partial round should not have been returned");
        } finally {
            FileUtils.deleteDirectory(directory);
        }
    }
}

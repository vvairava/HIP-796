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

package com.swirlds.cli.utility;

import static com.swirlds.cli.logging.LogProcessingUtils.parseTimestamp;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.cli.logging.LogLine;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the LogLine class
 */
@DisplayName("LogLine Tests")
//CHECKSTYLE:OFF
class LogLineTests {
    public static final String testString =
            """
            2023-08-04 13:50:09.751 102      INFO  PLATFORM_STATUS  <<platform: status-state-machine>> PlatformStatusStateMachine: Platform spent 441.0 ms in STARTING_UP. Now in REPLAYING_EVENTS {"oldStatus":"STARTING_UP","newStatus":"REPLAYING_EVENTS"} [com.swirlds.logging.legacy.payload.PlatformStatusPayload]""";

    @Test
    @DisplayName("Test splitting a log line")
    void splitLogLine() {
        final LogLine logLine = new LogLine(testString, ZoneId.systemDefault());

        assertEquals(parseTimestamp("2023-08-04 13:50:09.751", ZoneId.systemDefault()), logLine.getTimestamp());
        assertEquals("102", logLine.getLogNumber());
        assertEquals("INFO", logLine.getLogLevel());
        assertEquals("PLATFORM_STATUS", logLine.getMarker());
        assertEquals("<<platform: status-state-machine>>", logLine.getThreadName());
        assertEquals("PlatformStatusStateMachine", logLine.getClassName());
        assertEquals(
                "Platform spent 441.0 ms in STARTING_UP. Now in REPLAYING_EVENTS {\"oldStatus\":\"STARTING_UP\",\"newStatus\":\"REPLAYING_EVENTS\"} [com.swirlds.logging.legacy.payload.PlatformStatusPayload]",
                logLine.getRemainderOfLine().getOriginalPlaintext());
    }
}

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

package com.swirlds.logging.api.internal.format;

import com.swirlds.logging.api.extensions.emergency.EmergencyLogger;
import com.swirlds.logging.api.extensions.emergency.EmergencyLoggerProvider;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class StackTracePrinter {

    private static final EmergencyLogger EMERGENCY_LOGGER = EmergencyLoggerProvider.getEmergencyLogger();

    private static void print(
            @NonNull final Appendable writer,
            @NonNull Throwable throwable,
            @NonNull final Set<Throwable> alreadyPrinted,
            @NonNull final StackTraceElement[] enclosingTrace)
            throws IOException {
        if (writer == null) {
            EMERGENCY_LOGGER.logNPE("printWriter");
            return;
        }
        if (throwable == null) {
            EMERGENCY_LOGGER.logNPE("throwable");
            writer.append("[NULL REFERENCE]");
            return;
        }
        if (alreadyPrinted == null) {
            EMERGENCY_LOGGER.logNPE("alreadyPrinted");
            writer.append("[INVALID REFERENCE]");
            return;
        }
        if (enclosingTrace == null) {
            EMERGENCY_LOGGER.logNPE("enclosingTrace");
            writer.append("[INVALID REFERENCE]");
            return;
        }
        if (alreadyPrinted.contains(throwable)) {
            writer.append("[CIRCULAR REFERENCE: " + throwable + "]");
            return;
        }
        alreadyPrinted.add(throwable);
        if (alreadyPrinted.size() > 1) {
            writer.append("Cause: ");
        }
        writer.append(throwable.getClass().getName());
        writer.append(": ");
        writer.append(throwable.getMessage());
        writer.append(System.lineSeparator());

        final StackTraceElement[] stackTrace = throwable.getStackTrace();
        int m = stackTrace.length - 1;
        int n = enclosingTrace.length - 1;
        while (m >= 0 && n >= 0 && stackTrace[m].equals(enclosingTrace[n])) {
            m--;
            n--;
        }
        final int framesInCommon = stackTrace.length - 1 - m;
        for (int i = 0; i <= m; i++) {
            final StackTraceElement stackTraceElement = stackTrace[i];
            final String className = stackTraceElement.getClassName();
            final String methodName = stackTraceElement.getMethodName();
            final int line = stackTraceElement.getLineNumber();
            writer.append("\tat ");
            writer.append(className);
            writer.append(".");
            writer.append(methodName);
            writer.append("(");
            writer.append(className);
            writer.append(".java:");
            writer.append(Integer.toString(line));
            writer.append(")");
            writer.append(System.lineSeparator());
        }
        if (framesInCommon != 0) {
            writer.append("\t... ");
            writer.append(Integer.toString(framesInCommon));
            writer.append(" more");
            writer.append(System.lineSeparator());
        }
        final Throwable cause = throwable.getCause();
        if (cause != null) {
            print(writer, cause, alreadyPrinted, stackTrace);
        }
    }

    public static void print(@NonNull final Appendable writer, @NonNull Throwable throwable) throws IOException {
        print(writer, throwable, new HashSet<>(), new StackTraceElement[0]);
    }
}

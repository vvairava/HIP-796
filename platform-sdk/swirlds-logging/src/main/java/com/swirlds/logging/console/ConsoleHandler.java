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

package com.swirlds.logging.console;

import com.swirlds.config.api.Configuration;
import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.api.extensions.handler.AbstractSyncedHandler;
import com.swirlds.logging.api.internal.format.LineBasedFormat;
import java.io.PrintWriter;

public class ConsoleHandler extends AbstractSyncedHandler {

    private final LineBasedFormat lineBasedFormat;

    private final PrintWriter printWriter = new PrintWriter(System.out, true);

    public ConsoleHandler(final Configuration configuration) {
        super("console", configuration);
        lineBasedFormat = new LineBasedFormat(printWriter);
    }

    @Override
    protected void handleEvent(final LogEvent event) {
        lineBasedFormat.print(event);
    }

    @Override
    protected void handleStopAndFinalize() {
        super.handleStopAndFinalize();
        printWriter.close();
    }
}

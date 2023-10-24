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

package com.swirlds.common.wiring.wires;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A unit of work that is handled by a task scheduler.
 */
public abstract class AbstractTask extends ForkJoinTask<Void> {

    /**
     * Counts outstanding dependencies. When it reaches 0, the task is ready to run.
     */
    private final AtomicInteger dependencyCount;

    /**
     * The fork join pool that will execute this task.
     */
    private final ForkJoinPool pool;

    /**
     * Constructor.
     *
     * @param dependencyCount the number of dependencies that must be satisfied before this task is eligible for
     *                        execution
     */
    protected AbstractTask(@NonNull final ForkJoinPool pool, final int dependencyCount) {
        this.pool = pool;
        this.dependencyCount = dependencyCount > 0 ? new AtomicInteger(dependencyCount) : null;
    }

    /**
     * Unused.
     */
    @Override
    public final Void getRawResult() {
        return null;
    }

    /**
     * Unused.
     */
    @Override
    protected final void setRawResult(Void value) {}

    /**
     * If the task has no dependencies then execute it. If the task has dependencies, decrement the dependency count and
     * execute it if the resulting number of dependencies is zero.
     */
    protected void send() {
        if (dependencyCount == null || dependencyCount.decrementAndGet() == 0) {
            pool.execute(this);
        }
    }

    /**
     * Execute the work represented by this task.
     *
     * @return true if this task is known to have been completed normally
     */
    @Override
    protected abstract boolean exec();
}

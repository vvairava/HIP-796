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

package com.swirlds.state.spi;

import com.hedera.hapi.block.stream.output.StateChange;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedList;
import java.util.List;

/**
 * An interface responsible for observing any state changes occurred on state
 * and some additional helper methods
 */
public interface StateChangesListener {

    /**
     * Add a state change so that it can be written to the block stream
     * @param stateChange the state change to be written
     */
    void addStateChange(@NonNull final StateChange stateChange);

    /**
     * Return all the state changes currently extracted
     * @return all state changes
     */
    List<StateChange> getStateChanges();

    /**
     * Return state changes from the end of a round
     * @return end of round state changes
     */
    LinkedList<StateChange> getEndOfRoundStateChanges();

    /**
     * Save the state change when an entry is added in to a map.
     *
     * @param label The label of the map
     * @param key The key added to the map
     * @param value The value added to the map
     * @param <K> The type of the key
     * @param <V> The type of the value
     */
    <K, V> void mapUpdateChange(@NonNull final String label, @NonNull final K key, @NonNull final V value);

    /**
     * Save the state change when an entry is removed from a map.
     *
     * @param label The label of the map
     * @param key The key removed from the map
     * @param <K> The type of the key
     */
    <K> void mapDeleteChange(@NonNull final String label, @NonNull final K key);

    /**
     * Save the state change when a value is added to a queue
     *
     * @param label The label of the queue
     * @param value The value added to the queue
     * @param <V> The type of the value
     */
    <V> void queuePushChange(@NonNull final String label, @NonNull final V value);

    /**
     * Save the state change when a value is removed from a queue
     *
     * @param label The label of the queue
     */
    void queuePopChange(@NonNull final String label);

    /**
     * Save the state change when the value of a singleton is written.
     *
     * @param label The label of the singleton
     * @param value The value of the singleton
     * @param <V> The type of the value
     */
    <V> void singletonUpdateChange(@NonNull final String label, @NonNull final V value);

    /**
     * Reset end of round state changes
     */
    void resetEndOfRoundStateChanges();

    /**
     * Reset current state changes
     */
    void resetStateChanges();

    /**
     * Check if there are any state changes currently extracted
     * @return true or false
     */
    boolean hasRecordedStateChanges();

    /**
     * Check if there are any end of round state changes currently extracted
     * @return true or false
     */
    boolean hasRecordedEndOfRoundStateChanges();
}

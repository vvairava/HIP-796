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

package com.swirlds.base.sample.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.undertow.server.HttpServerExchange;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Utility class for transforming information
 */
public class DataTransferUtils {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DataTransferUtils() {}

    /**
     * Serializes  {@code object} into a Json String
     *
     * @param object instance to be serialized
     * @param <T>    class {@code object} belongs to
     * @return the serialized json string
     */
    public static <T> @NonNull String serializeToJson(final @Nullable T object) {
        if (Objects.isNull(object)) {
            return "";
        }
        try {
            return MAPPER.writeValueAsString(object);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Could not serialize object of type " + object.getClass().getName(), e);
        }
    }

    /**
     * Deserializes a json string {@code json} into an object instance of type {@code type}
     *
     * @param json The json string to deserialize
     * @param type The type to deserialize to
     * @return an instance of {@code T}
     */
    public static <T> @Nullable T deserializeJson(final @Nullable String json, final @NonNull Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Could not deserialize an object of type " + type + " out of input string", e);
        }
    }

    /**
     * Deserializes a json string {@code json} into an object instance of type {@code type} from a
     * {@code HttpServerExchange}
     */
    public static <T> @Nullable T deserializeJsonFromExchange(
            final @NonNull HttpServerExchange exchange, final @NonNull Class<T> clazz) {
        try {
            StringBuilder requestBody = new StringBuilder();
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(exchange.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    requestBody.append(line);
                }
            }
            return deserializeJson(requestBody.toString(), clazz);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not deserialize into requested type:" + clazz, e);
        }
    }

    /**
     * Separate the requestUri into a list of components.
     *
     * @param exchange Http exchange
     * @return the path components of the URL
     */
    public static @NonNull List<String> urlToList(final @NonNull HttpServerExchange exchange) {
        return urlToList(exchange.getRequestURI());
    }

    /**
     * Separate the path into a list of components.
     *
     * @param path the url
     * @return the path components of the URL
     */
    public static @NonNull List<String> urlToList(final @NonNull String path) {
        List<String> paths = new ArrayList<>();
        // Split the path into individual segments
        String[] pathSegments = path.split("/");

        // Add each path segment to the list
        for (String segment : pathSegments) {
            if (!segment.isEmpty()) {
                paths.add(segment);
            }
        }

        return paths;
    }

    /**
     * It does not convert lists into List objects.
     *
     * @param url the url to get the parameters from
     * @return all parameters after ? in a map.
     */
    public static @NonNull Map<String, String> getUrlParams(final @NonNull String url) {
        int lastIndex = url.lastIndexOf("?");
        if (lastIndex >= 0 && lastIndex < url.length() - 1) {
            return Arrays.stream(url.substring(lastIndex + 1).split("&"))
                    .map(v -> v.split("="))
                    .filter(v -> v.length == 2)
                    .collect(Collectors.toMap(v -> v[0], v -> v[1]));
        } else {
            return ImmutableMap.of();
        }
    }
}

/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.swirlds.config.processor;

import java.io.IOException;
import javax.tools.JavaFileObject;
import org.jboss.forge.roaster.Roaster;
import org.jboss.forge.roaster.model.source.JavaClassSource;

public class ConstantClassFactory {

    public static final String JAVA_LANG_STRING = "java.lang.String";
    public static final String CONSTANTS_CLASS_SUFFIX = "Constants";

    public void doWork(ConfigDataRecordDefinition configDataRecordDefinition, JavaFileObject constantsSourceFile)
            throws IOException {

        final JavaClassSource javaClassSource = Roaster.create(JavaClassSource.class);
        final int split = configDataRecordDefinition.className().lastIndexOf(".");
        javaClassSource.setPackage(configDataRecordDefinition.className().substring(0, split))
                .setName(configDataRecordDefinition.className()
                        .substring(split + 1, configDataRecordDefinition.className().length()) + CONSTANTS_CLASS_SUFFIX)
                .setFinal(true);

        configDataRecordDefinition.propertyDefinitions().forEach(propertyDefinition -> {
            final String name = toConstantName(
                    propertyDefinition.name().replace(configDataRecordDefinition.configDataName() + ".", ""));
            javaClassSource.addField()
                    .setName(name)
                    .setType(JAVA_LANG_STRING)
                    .setLiteralInitializer("\"" + propertyDefinition.name() + "\"")
                    .setPublic()
                    .setStatic(true);
        });
        System.out.println("Writing " + constantsSourceFile.toUri().getPath());
        constantsSourceFile.openWriter().append(javaClassSource.toString()).close();
    }

    public static String toConstantName(final String propertyName) {
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < propertyName.length(); i++) {
            final char character = propertyName.charAt(i);
            if (Character.isUpperCase(character)) {
                builder.append("_");
                builder.append(character);
            } else {
                builder.append(Character.toUpperCase(character));
            }
        }
        return builder.toString();
    }
}

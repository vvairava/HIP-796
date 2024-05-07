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

package com.swirlds.config.processor.antlr;

import static java.util.Map.entry;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.DefaultValue;
import com.swirlds.config.api.EmptyValue;
import com.swirlds.config.api.UnsetValue;
import com.swirlds.config.processor.ConfigDataPropertyDefinition;
import com.swirlds.config.processor.ConfigDataRecordDefinition;
import com.swirlds.config.processor.antlr.generated.JavaParser.AnnotationContext;
import com.swirlds.config.processor.antlr.generated.JavaParser.CompilationUnitContext;
import com.swirlds.config.processor.antlr.generated.JavaParser.RecordComponentContext;
import com.swirlds.config.processor.antlr.generated.JavaParser.RecordDeclarationContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

public final class AntlrConfigRecordParser {

    /**
     * Property name for: {@link ConfigProperty#defaultValue()}
     */
    private static final String DEFAULT_VALUE = "defaultValue";
    /**
     * Property name for: {@code @SomeAnnotation.value()}
     */
    private static final String VALUE = "value";
    /**
     * The translation between type and default value
     */
    private static final Map<String, String> TYPE_TO_DEFAULT_VALUE = Map.ofEntries(
            entry(Byte.TYPE.getTypeName(), "0"),
            entry(Short.TYPE.getTypeName(), "0"),
            entry(Character.TYPE.getTypeName(), Character.valueOf((char) 0).toString()),
            entry(Integer.TYPE.getTypeName(), "0"),
            entry(Long.TYPE.getTypeName(), "0"),
            entry(Float.TYPE.getTypeName(), "0.0"),
            entry(Double.TYPE.getTypeName(), "0.0"),
            entry(Boolean.TYPE.getTypeName(), "false"));

    private static boolean isAnnotatedWith(
            @NonNull final RecordDeclarationContext ctx,
            @NonNull String packageName,
            @NonNull List<String> imports,
            @NonNull final Class<? extends Annotation> annotation) {
        final List<AnnotationContext> allAnnotations = AntlrUtils.getAllAnnotations(ctx);
        return AntlrUtils.findAnnotationOfType(annotation, allAnnotations, packageName, imports)
                .isPresent();
    }

    @NonNull
    private static Optional<AnnotationContext> getAnnotation(
            @NonNull final List<AnnotationContext> annotations,
            @NonNull final String packageName,
            @NonNull final List<String> imports,
            @NonNull final Class<? extends Annotation> annotation) {
        return AntlrUtils.findAnnotationOfType(annotation, annotations, packageName, imports);
    }

    @Nullable
    private static String getAnnotationValue(
            @NonNull final RecordDeclarationContext ctx,
            @NonNull final String packageName,
            @NonNull final List<String> imports,
            @NonNull final Class<? extends Annotation> annotation) {
        final List<AnnotationContext> annotations = AntlrUtils.getAllAnnotations(ctx);
        return getAnnotation(annotations, packageName, imports, annotation)
                .map(AnnotationContext::elementValue)
                .map(RuleContext::getText)
                .map(text -> text.substring(1, text.length() - 1)) // remove quotes
                .orElse(null);
    }

    @NonNull
    private static String getAnnotationPropertyOrElse(
            @NonNull final RecordComponentContext ctx,
            @NonNull final String packageName,
            @NonNull final List<String> imports,
            @NonNull final Class<? extends Annotation> annotation,
            @NonNull final String property,
            @NonNull final String orElseValue) {
        final List<AnnotationContext> allAnnotations = AntlrUtils.getAllAnnotations(ctx);
        return getAnnotation(allAnnotations, packageName, imports, annotation)
                .flatMap(annotationContext -> AntlrUtils.getAnnotationValue(annotationContext, property))
                .orElse(orElseValue);
    }

    @Nullable
    private static String getAnnotationProperty(
            @NonNull final RecordComponentContext ctx,
            @NonNull final String packageName,
            @NonNull final List<String> imports,
            @NonNull final Class<? extends Annotation> annotation,
            @NonNull final String property) {
        final List<AnnotationContext> allAnnotations = AntlrUtils.getAllAnnotations(ctx);
        return getAnnotation(allAnnotations, packageName, imports, annotation)
                .flatMap(annotationContext -> AntlrUtils.getAnnotationValue(annotationContext, property))
                .orElse(null);
    }

    @NonNull
    @Deprecated
    private static ConfigDataPropertyDefinition createDefinitionFromConfigProperty(
            @NonNull final RecordComponentContext ctx,
            @Nullable final String configPropertyNamePrefix,
            @NonNull final String packageName,
            @NonNull final List<String> imports,
            @NonNull final Map<String, String> javadocParams) {
        final String componentName = ctx.identifier().getText();
        try {
            final String configPropertyNameSuffix =
                    getAnnotationPropertyOrElse(ctx, packageName, imports, ConfigProperty.class, VALUE, componentName);
            final String name = createPropertyName(configPropertyNamePrefix, configPropertyNameSuffix);
            final String defaultValue = getAnnotationPropertyOrElse(
                    ctx,
                    packageName,
                    imports,
                    ConfigProperty.class,
                    DEFAULT_VALUE,
                    ConfigProperty.UNDEFINED_DEFAULT_VALUE);
            final String type = getType(ctx, imports);
            final String description =
                    Optional.ofNullable(javadocParams.get(componentName)).orElse("");

            return new ConfigDataPropertyDefinition(componentName, name, type, defaultValue, description);
        } catch (Exception e) {
            throw new IllegalArgumentException(ConfigProperty.class.getTypeName() + " is not correctly defined for "
                    + componentName + " property");
        }
    }

    private static String getType(final RecordComponentContext ctx, final List<String> imports) {
        return Optional.ofNullable(ctx.typeType().classOrInterfaceType())
                .map(RuleContext::getText)
                .map(typeText -> imports.stream()
                        .filter(importText -> importText.endsWith(typeText))
                        .findAny()
                        .orElse(typeText))
                .map(AntlrConfigRecordParser::getTypeForJavaLang)
                .orElseGet(() -> ctx.typeType().primitiveType().getText());
    }

    @NonNull
    private static ConfigDataPropertyDefinition createDefinitionFromDefaultValue(
            @NonNull final RecordComponentContext ctx,
            @NonNull final String configPropertyNamePrefix,
            @NonNull final String packageName,
            @NonNull final List<String> imports,
            @NonNull final Map<String, String> javadocParams) {
        final String componentName = ctx.identifier().getText();
        try {
            final String configPropertyNameSuffix =
                    getAnnotationPropertyOrElse(ctx, packageName, imports, ConfigProperty.class, VALUE, componentName);
            final String name = createPropertyName(configPropertyNamePrefix, configPropertyNameSuffix);
            final String defaultValue = getAnnotationProperty(ctx, packageName, imports, DefaultValue.class, VALUE);
            final String type = getType(ctx, imports);
            final String description =
                    Optional.ofNullable(javadocParams.get(componentName)).orElse("");

            return new ConfigDataPropertyDefinition(componentName, name, type, defaultValue, description);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    DefaultValue.class.getTypeName() + " is not correctly defined for " + componentName + " property");
        }
    }

    @NonNull
    private static ConfigDataPropertyDefinition createDefinitionFromEmptyValue(
            @NonNull final RecordComponentContext ctx,
            @NonNull final String configPropertyNamePrefix,
            @NonNull final String packageName,
            @NonNull final List<String> imports,
            @NonNull final Map<String, String> javadocParams) {
        final String componentName = ctx.identifier().getText();
        final String configPropertyNameSuffix =
                getAnnotationPropertyOrElse(ctx, packageName, imports, ConfigProperty.class, VALUE, componentName);
        final String name = createPropertyName(configPropertyNamePrefix, configPropertyNameSuffix);
        final String type = getType(ctx, imports);
        if (!type.startsWith("java.lang.List")
                && !type.startsWith("java.lang.Set")
                && !type.startsWith("java.lang.String")) {
            throw new IllegalArgumentException("EmptyValue for Type " + type + " is not supported");
        }
        final String description =
                Optional.ofNullable(javadocParams.get(componentName)).orElse("");
        return new ConfigDataPropertyDefinition(componentName, name, type, "", description);
    }

    @NonNull
    private static ConfigDataPropertyDefinition createDefinitionFromUnsetValue(
            @NonNull final RecordComponentContext ctx,
            @NonNull final String configPropertyNamePrefix,
            @NonNull final String packageName,
            @NonNull final List<String> imports,
            @NonNull final Map<String, String> javadocParams) {
        final String componentName = ctx.identifier().getText();
        final String configPropertyNameSuffix =
                getAnnotationPropertyOrElse(ctx, packageName, imports, ConfigProperty.class, VALUE, componentName);
        final String name = createPropertyName(configPropertyNamePrefix, configPropertyNameSuffix);
        final String type = getType(ctx, imports);
        final String description =
                Optional.ofNullable(javadocParams.get(componentName)).orElse("");
        final String defaultValue = TYPE_TO_DEFAULT_VALUE.get(type);
        return new ConfigDataPropertyDefinition(componentName, name, type, defaultValue, description);
    }

    @NonNull
    private static String createPropertyName(
            @Nullable final String configPropertyNamePrefix, @NonNull final String configPropertyNameSuffix) {
        if (configPropertyNamePrefix == null || configPropertyNamePrefix.isBlank()) {
            return configPropertyNameSuffix;
        } else {
            return configPropertyNamePrefix + "." + configPropertyNameSuffix;
        }
    }

    @NonNull
    private static String getTypeForJavaLang(@NonNull final String type) {
        if (!type.contains(".")) {
            return String.class.getPackageName() + "." + type;
        }
        return type;
    }

    @NonNull
    private static List<ConfigDataRecordDefinition> createDefinitions(
            @NonNull final CompilationUnitContext unitContext) {
        final String packageName = AntlrUtils.getPackage(unitContext);
        final List<String> imports = AntlrUtils.getImports(unitContext);
        return AntlrUtils.getRecordDeclarationContext(unitContext).stream()
                .filter(c -> isAnnotatedWith(c, packageName, imports, ConfigData.class))
                .map(recordContext -> createDefinition(unitContext, recordContext, packageName, imports))
                .collect(Collectors.toList());
    }

    @NonNull
    private static ConfigDataRecordDefinition createDefinition(
            @NonNull final CompilationUnitContext unitContext,
            @NonNull final RecordDeclarationContext recordContext,
            @NonNull final String packageName,
            @NonNull final List<String> imports) {
        final String recordName = recordContext.identifier().getText();

        try {
            final String configPropertyNamePrefix = Optional.ofNullable(
                            getAnnotationValue(recordContext, packageName, imports, ConfigData.class))
                    .orElse("");
            final Map<String, String> javadocParams = unitContext.children.stream()
                    .filter(AntlrUtils::isJavaDocNode)
                    .map(ParseTree::getText)
                    .map(AntlrUtils::getJavaDocParams)
                    .reduce((m1, m2) -> {
                        m1.putAll(m2);
                        return m1;
                    })
                    .orElse(Map.of());

            final Set<ConfigDataPropertyDefinition> propertyDefinitions =
                    recordContext.recordHeader().recordComponentList().recordComponent().stream()
                            .map(c -> createDefinitionFromRecordComponent(
                                    c, configPropertyNamePrefix, packageName, imports, javadocParams))
                            .collect(Collectors.toSet());
            return new ConfigDataRecordDefinition(
                    packageName, recordName, configPropertyNamePrefix, propertyDefinitions);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not process " + packageName + "." + recordName, e);
        }
    }

    private static ConfigDataPropertyDefinition createDefinitionFromRecordComponent(
            final RecordComponentContext ctx,
            @Nullable final String configPropertyNamePrefix,
            final String packageName,
            final List<String> imports,
            final Map<String, String> javadocParams) {
        final String componentName = ctx.identifier().getText();
        final String prefix = Optional.ofNullable(configPropertyNamePrefix).orElse("");

        final String configPropertyDefaultValue =
                getAnnotationProperty(ctx, packageName, imports, ConfigProperty.class, DEFAULT_VALUE);
        if (configPropertyDefaultValue != null) {
            return createDefinitionFromConfigProperty(ctx, prefix, packageName, imports, javadocParams);
        }

        final List<AnnotationContext> allAnnotations = AntlrUtils.getAllAnnotations(ctx);
        final AnnotationContext defaultValue = AntlrUtils.findAnnotationOfType(
                        DefaultValue.class, allAnnotations, packageName, imports)
                .orElse(null);
        final AnnotationContext emptyValue = AntlrUtils.findAnnotationOfType(
                        EmptyValue.class, allAnnotations, packageName, imports)
                .orElse(null);
        final AnnotationContext unSetValue = AntlrUtils.findAnnotationOfType(
                        UnsetValue.class, allAnnotations, packageName, imports)
                .orElse(null);

        if (Stream.of(defaultValue, emptyValue, unSetValue)
                        .filter(Objects::nonNull)
                        .count()
                > 1) {
            throw new IllegalArgumentException("Multiple default values found for component " + componentName);
        }

        if (defaultValue != null) {
            return createDefinitionFromDefaultValue(ctx, prefix, packageName, imports, javadocParams);
        } else if (emptyValue != null) {
            return createDefinitionFromEmptyValue(ctx, prefix, packageName, imports, javadocParams);
        } else {
            return createDefinitionFromUnsetValue(ctx, prefix, packageName, imports, javadocParams);
        }
    }

    /**
     * Creates a list of {@link ConfigDataRecordDefinition} from a given Java source file.
     *
     * @param fileContent the content of the Java source file
     */
    @NonNull
    public static List<ConfigDataRecordDefinition> parse(@NonNull final String fileContent) {
        final CompilationUnitContext parsedContext = AntlrUtils.parse(fileContent);
        return createDefinitions(parsedContext);
    }
}

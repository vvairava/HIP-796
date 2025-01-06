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

package com.swirlds.platform.cli;

import static com.swirlds.common.io.utility.FileUtils.getAbsolutePath;
import static com.swirlds.platform.cli.utils.JtrUtils.scrapeTestData;
import static com.swirlds.platform.testreader.JrsTestReportGenerator.generateReport;

import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.platform.cli.utils.JtrUtils;
import com.swirlds.platform.testreader.JrsReportData;
import com.swirlds.platform.testreader.JrsTestIdentifier;
import com.swirlds.platform.testreader.JrsTestMetadata;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import picocli.CommandLine;

@CommandLine.Command(
        name = "report",
        mixinStandardHelpOptions = true,
        description = "Scrape test data from gcp buckets and generate an HTML report. "
                + "Equivalent to running 'scrape' followed by 'render'.")
@SubcommandOf(JrsTestReaderCommand.class)
public class JrsTestReaderReportCommand extends AbstractCommand {
    private String bucketPrefix = "gs://swirlds-circleci-jrs-results";
    private String bucketPrefixReplacement = "http://35.247.76.217:8095";
    private List<String> targets;
    private List<Path> outputPaths;
    private int days = 7;
    private Path metadataFile;
    private int threads = 48;

    @CommandLine.Option(
            names = {"-b", "--bucket"},
            description = "The gs bucket to scrape data from. Defaults to 'gs://swirlds-circleci-jrs-results'.")
    private void setBucketPrefix(@NonNull final String bucketPrefix) {
        this.bucketPrefix = bucketPrefix;
    }

    @CommandLine.Option(
            names = {"-r", "--bucket-replacement"},
            description = "The replacement for bucket prefix in order to convert bucket URLs to web links. Defaults"
                    + "to http://35.247.76.217:8095")
    private void setBucketPrefixReplacement(@NonNull final String bucketPrefix) {
        this.bucketPrefixReplacement = bucketPrefix;
    }

    @CommandLine.Option(
            names = {"-t", "--targets"},
            description = "The targets to generate a report for. Branch names or release numbers are valid inputs.")
    private void setTargets(@NonNull final List<String> targets) {
        this.targets = new ArrayList<>();
        this.targets.addAll(targets);
    }

    @CommandLine.Option(
            names = {"-d", "--days"},
            description = "Specify the number of days in the past to begin scraping from. Defaults to 7.")
    private void setDays(final int days) {
        this.days = days;
    }

    @CommandLine.Option(
            names = {"-o", "--output"},
            description = "Specify the paths to the output html files. If not specified, then output path will be"
                    + "autogenerated based on the test directory, and written into the current working directory.")
    private void setOutputPaths(@NonNull final List<Path> outputPaths) {
        this.outputPaths = new ArrayList<>();
        outputPaths.forEach(outputPath -> this.outputPaths.add(getAbsolutePath(outputPath)));
    }

    @CommandLine.Option(
            names = {"-m", "--metadata"},
            description = "Specify the path to the test metadata csv file.")
    private void setMetadataFile(@NonNull final Path metadataFile) {
        this.metadataFile = getAbsolutePath(metadataFile);
    }

    @CommandLine.Option(
            names = {"--threads"},
            description = "Specify the number of threads to use. Defaults to 48.")
    private void setThreads(final int threads) {
        this.threads = threads;
    }

    private JrsTestReaderReportCommand() {}

    /**
     * Interpret the target input string as either a branch name or a release number, and return the corresponding
     * directory, relative to the bucket prefix.
     * <p>
     * If the input string is a number, then it is interpreted as a release number. Otherwise, it is interpreted as a
     * branch name.
     * <p>
     * If the input string contains a slash, then it is interpreted as a path to a non-standard test directory.
     * Otherwise, it is assumed that the desired tests are in the `swirlds-automation` directory.
     *
     * @param inputString the input string
     * @return the interpreted target directory
     */
    @NonNull
    private static String interpretTargetDirectory(@NonNull final String inputString) {
        final StringBuilder stringBuilder = new StringBuilder();

        // allow the user to specify a path to a non-standard test directory
        if (!inputString.contains("/")) {
            stringBuilder.append("swirlds-automation/");
        }

        // if the input string is a number, then it is interpreted as a release number
        if (inputString.matches("\\d+")) {
            stringBuilder.append("release/0.");
        }

        stringBuilder.append(inputString);

        return stringBuilder.toString();
    }

    /**
     * Generate a report for the given test directory.
     *
     * @param testDirectory the test directory
     * @param outputPath    the output path
     */
    private void generateIndividualReport(
            @NonNull final String testDirectory,
            @NonNull final Path outputPath,
            @NonNull final Map<JrsTestIdentifier, JrsTestMetadata> metadata) {

        if (Files.exists(outputPath)) {
            try {
                Files.delete(outputPath);
            } catch (final IOException e) {
                throw new RuntimeException(e);
            }
        }

        final JrsReportData data = scrapeTestData(bucketPrefix, testDirectory, days, threads);

        generateReport(data, metadata, Instant.now(), bucketPrefix, bucketPrefixReplacement, outputPath);
    }

    /**
     * Auto generate a name of an output directory, based on the target and the current instant
     *
     * @param target the target
     * @return the auto generated output directory name
     */
    @NonNull
    private static Path autoGenerateOutputDirectoryName(@NonNull final String target) {
        // remove everything before a slash, if one exists
        final String targetWithoutPath = target.substring(target.lastIndexOf("/") + 1);

        // format example 'ThursdaySeptember21'
        final DateTimeFormatter formatter =
                DateTimeFormatter.ofPattern("EEEELLLLd").withZone(ZoneId.systemDefault());
        final String todayString = formatter.format(Instant.now());

        return getAbsolutePath(Path.of("jtr-" + targetWithoutPath + "-" + todayString + ".html"));
    }

    @Override
    public Integer call() {
        // if no targets were specified, then default to the main branch
        if (targets == null) {
            targets = List.of("main");
        }

        final Map<JrsTestIdentifier, JrsTestMetadata> metadata = JtrUtils.getTestMetadata(metadataFile);

        for (int i = 0; i < targets.size(); i++) {
            final String target = targets.get(i);
            final String targetDirectory = interpretTargetDirectory(target);

            System.out.println("Generating report for `" + targetDirectory + "`");

            // use the specified path if defined, otherwise auto generate a path
            final Path outputPath = outputPaths != null && outputPaths.size() > i
                    ? outputPaths.get(i)
                    : autoGenerateOutputDirectoryName(target);
            System.out.println("Writing output file to: " + outputPath);

            generateIndividualReport(targetDirectory, outputPath, metadata);
        }

        return 0;
    }
}

/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.grails.compiler.beans;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;

import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.WarningMessage;

/**
 * Registers a generated auto-configuration in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 *
 * <p>The class a {@code beans} closure compiles to is created during compilation and is not a source
 * file anyone can open. Leaving its registration to be written by hand made a plugin whose beans are
 * silently never registered the ordinary consequence of not knowing the class exists - and the name
 * to write is one only the compiler knows, since it follows from the descriptor's name and package.
 * Writing it where the class is created is the only point at which that name is known for certain.
 *
 * <p>A module that keeps the file by hand at {@value #SOURCE_IMPORTS_LOCATION} keeps it: generating
 * a second copy would put the same resource at the same path twice, and folding its entries into a
 * copy under the build directory would lose them the moment anyone deleted the file that was, until
 * then, where they were written down. Such a module is warned when the generated class is missing
 * from it and is otherwise left alone, so nothing that builds today builds differently - deleting
 * the hand-authored file is what opts in, and is safe once it holds nothing but what is generated.
 *
 * <p>That one conventional location is all a compiler can look in: a source set's resource
 * directories are a build-tool notion and are not among the things the compiler is told, so a module
 * that relocates them keeps a file this cannot see and gets a second copy generated, which the build
 * then reports as two resources at one path. {@code FactoriesFileWriter} reads
 * {@code META-INF/grails.factories} from the same fixed location for the same reason. Handling a
 * relocated one needs the build to say where it is.
 *
 * <p>Hand-authored entries have to remain possible: a module may register a class from another jar,
 * one annotated with a composed annotation, or one carrying no annotation at all, the imports file
 * being the registration and {@code @AutoConfiguration} only supplying ordering.
 *
 * @since 8.0
 */
final class AutoConfigurationImportsWriter {

    static final String IMPORTS_LOCATION =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    static final String SOURCE_IMPORTS_LOCATION = "src/main/resources/" + IMPORTS_LOCATION;

    /** Set by the Grails Gradle plugin on the compiler's fork options; see GrailsAppBaseDirProvider. */
    private static final String BASE_DIR_PROPERTY = "base.dir";

    private static final String COMMENT_START = "#";

    private static final String CLASS_FILE_EXTENSION = ".class";

    /**
     * What each compilation has registered so far, so an entry survives the pruning below before the
     * class file backing it has been written - class generation runs long after this does, and two
     * descriptors recompiling together would otherwise prune each other. Weakly keyed on the
     * compilation, which is what makes the state per-build rather than per-JVM in a reused daemon.
     */
    private static final Map<Object, Set<String>> REGISTERED_BY_COMPILATION =
            Collections.synchronizedMap(new WeakHashMap<>());

    private AutoConfigurationImportsWriter() {
    }

    /**
     * Adds {@code className} to the generated imports file under {@code targetDirectory}, together
     * with anything an earlier source unit of the same compilation registered there. A module that
     * keeps the file by hand is warned instead, and its file is left as the only one.
     *
     * @param className the generated auto-configuration's binary name
     * @param targetDirectory the compilation output directory, or {@code null} when the compiler did
     *                        not supply one - in which case there is nowhere to write and the class
     *                        stays registerable by hand
     * @return {@code true} when the file was written
     */
    static boolean register(String className, File targetDirectory, SourceUnit source, Object compilation) {
        if (className == null || className.isEmpty() || targetDirectory == null) {
            return false;
        }

        File sourceDirectory = findSourceDirectory(targetDirectory);
        File handAuthored = sourceDirectory == null ? null : new File(sourceDirectory, SOURCE_IMPORTS_LOCATION);
        if (handAuthored != null && handAuthored.isFile()) {
            Set<String> handAuthoredEntries = new TreeSet<>();
            readEntries(handAuthored, handAuthoredEntries);
            if (!handAuthoredEntries.contains(className)) {
                warn(source, className + " is generated from a beans closure but is not listed in " +
                        SOURCE_IMPORTS_LOCATION + ", so Spring Boot will not read it. Add it there, or delete " +
                        "that file once it holds nothing that is not generated and it will be written for you.");
            }
            return false;
        }

        Set<String> registeredHere = registeredBy(compilation);
        registeredHere.add(className);

        File importsFile = new File(targetDirectory, IMPORTS_LOCATION);
        Set<String> entries = new TreeSet<>();
        readEntries(importsFile, entries);

        // A descriptor that was renamed, deleted, or given a different autoConfigurationName leaves
        // an entry naming a class that is no longer generated, and Spring Boot fails to start on an
        // auto-configuration it cannot load. Anything this compilation registered is kept regardless:
        // its class file is written in a later phase than this one runs in.
        entries.removeIf(entry -> !registeredHere.contains(entry) && !isGeneratedHere(targetDirectory, entry));

        Set<String> written = new TreeSet<>(entries);
        written.addAll(registeredHere);
        if (written.equals(entries) && importsFile.isFile() && entries.contains(className)) {
            return false;
        }
        entries = written;

        try {
            Files.createDirectories(importsFile.toPath().getParent());
            // Sorted and newline-terminated, so recompiling the same sources rewrites the same bytes.
            Files.write(importsFile.toPath(), (String.join("\n", entries) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            return true;
        }
        catch (IOException notWritable) {
            // The class is still generated and still registerable by hand, so failing compilation
            // over the convenience of not having to would be the worse trade.
            return false;
        }
    }

    private static Set<String> registeredBy(Object compilation) {
        if (compilation == null) {
            // No compilation to scope to, so nothing is remembered between calls; the pruning below
            // then rests entirely on which class files are present, which is right for a single one.
            return Collections.synchronizedSet(new TreeSet<>());
        }
        return REGISTERED_BY_COMPILATION.computeIfAbsent(compilation,
                key -> Collections.synchronizedSet(new TreeSet<>()));
    }

    /** Whether {@code className} is still a class this module generates into its own output. */
    private static boolean isGeneratedHere(File targetDirectory, String className) {
        return new File(targetDirectory, className.replace('.', File.separatorChar) + CLASS_FILE_EXTENSION).isFile();
    }

    private static void warn(SourceUnit source, String message) {
        if (source != null) {
            source.getErrorCollector().addWarning(WarningMessage.LIKELY_ERRORS, message, null, source);
        }
    }

    /**
     * The module's base directory, so a hand-authored imports file can be found. Mirrors
     * {@code FactoriesFileWriter.findSourceDirectory}: the build tool's own answer if it supplied
     * one, otherwise the directory above the output root.
     */
    private static File findSourceDirectory(File targetDirectory) {
        String baseDir = System.getProperty(BASE_DIR_PROPERTY);
        if (baseDir != null && !baseDir.isEmpty()) {
            File candidate = new File(baseDir);
            if (candidate.isDirectory()) {
                return candidate;
            }
        }
        File directory = targetDirectory;
        while (directory != null && !("build".equals(directory.getName()) || "target".equals(directory.getName()))) {
            directory = directory.getParentFile();
        }
        return directory == null ? null : directory.getParentFile();
    }

    /** Adds the names in {@code file}, skipping blanks and the {@code #} comments Spring Boot skips. */
    private static void readEntries(File file, Set<String> entries) {
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String entry = line.trim();
                if (!entry.isEmpty() && !entry.startsWith(COMMENT_START)) {
                    entries.add(entry);
                }
            }
        }
        catch (IOException unreadable) {
            // Nothing to merge that can be read; the generated entry is still written below.
        }
    }

}

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
package org.apache.grails.buildsrc

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Scans this project's own compiled main classes for {@code @AutoConfiguration} and writes
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}, so the
 * file never needs to be hand-maintained. Mirrors how {@code META-INF/grails-plugin.xml} is already
 * generated from scanned {@code *GrailsPlugin} classes elsewhere in this build.
 *
 * <p>Classes are inspected via a scratch {@link URLClassLoader} over this project's own runtime
 * classpath, isolated from the Gradle daemon's classpath (parent set to the platform loader) so a
 * different Spring/Groovy version on the daemon's classpath cannot shadow the project's own.
 */
abstract class GenerateAutoConfigurationImportsTask extends DefaultTask {

    static final String IMPORTS_RESOURCE_PATH =
            'META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports'

    static final String AUTO_CONFIGURATION_ANNOTATION = 'org.springframework.boot.autoconfigure.AutoConfiguration'

    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getClassesDirs()

    /**
     * The classpath the scratch classloader resolves annotations/supertypes against. Deliberately
     * built from the project's compile classpath (dependencies only) rather than its runtime
     * classpath: the runtime classpath includes the source set's own output, which - once this
     * task's generated directory is registered on that same output - would make this task depend
     * on itself.
     */
    @Classpath
    abstract ConfigurableFileCollection getScanClasspath()

    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @TaskAction
    void generate() {
        SortedSet<String> discovered = scan(classesDirs.files, scanClasspath.files) { String className, Throwable failure ->
            logger.warn('generateAutoConfigurationImports: could not inspect {} - it will be excluded from ' +
                    'the generated imports file even if it is genuinely annotated @AutoConfiguration. Cause: {}',
                    className, failure.toString())
        }
        File importsFile = outputDirectory.file(IMPORTS_RESOURCE_PATH).get().asFile
        importsFile.parentFile.mkdirs()
        importsFile.text = discovered.isEmpty() ? '' : discovered.join('\n') + '\n'
    }

    /**
     * Package-private so it is directly unit-testable without running a real Gradle task.
     *
     * @param classesDirs directories of compiled {@code .class} files to inspect (a project's own
     * output only - dependency jars on {@code classpathFiles} are never scanned for candidates)
     * @param classpathFiles the classpath the scratch classloader resolves supertypes/annotations
     * against; must include {@code classesDirs} plus every runtime dependency
     * @param onUnresolvable invoked with (className, failure) for every candidate class that could
     * not be loaded against {@code classpathFiles}. Defaults to a no-op so existing callers are
     * unaffected; {@link #generate()} passes a callback that logs a build warning, since a class
     * that fails to load here is silently excluded from the generated imports file - which, if it
     * was genuinely a real {@code @AutoConfiguration}, means its beans would never be registered
     * with no other signal that anything went wrong.
     * @return the fully-qualified names of every top-level class annotated {@code @AutoConfiguration},
     * sorted for a deterministic, diff-friendly output file
     */
    static SortedSet<String> scan(Set<File> classesDirs, Set<File> classpathFiles,
            Closure<?> onUnresolvable = { String className, Throwable failure -> }) {
        URL[] urls = classpathFiles.collect { it.toURI().toURL() } as URL[]
        URLClassLoader scanLoader = new URLClassLoader(urls, ClassLoader.systemClassLoader.parent)
        try {
            Class<?> autoConfigurationAnnotation = Class.forName(AUTO_CONFIGURATION_ANNOTATION, false, scanLoader)
            SortedSet<String> discovered = new TreeSet<>()
            classesDirs.each { dir -> scanDirectory(dir, scanLoader, autoConfigurationAnnotation, discovered, onUnresolvable) }
            discovered
        }
        finally {
            scanLoader.close()
        }
    }

    private static void scanDirectory(File dir, URLClassLoader scanLoader, Class<?> autoConfigurationAnnotation,
            SortedSet<String> discovered, Closure<?> onUnresolvable) {
        if (!dir.exists()) {
            return
        }
        dir.eachFileRecurse(groovy.io.FileType.FILES) { file ->
            if (!file.name.endsWith('.class') || file.name.contains('$')) {
                return
            }
            String relative = dir.toPath().relativize(file.toPath()).toString()
            String className = relative.replace(File.separator, '.') - '.class'
            try {
                Class<?> candidate = Class.forName(className, false, scanLoader)
                if (candidate.isAnnotationPresent(autoConfigurationAnnotation)) {
                    discovered << candidate.name
                }
            }
            catch (Throwable unresolvable) {
                onUnresolvable.call(className, unresolvable)
            }
        }
    }

}

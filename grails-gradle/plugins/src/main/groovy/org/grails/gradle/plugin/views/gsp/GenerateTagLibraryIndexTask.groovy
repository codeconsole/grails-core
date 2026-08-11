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
package org.grails.gradle.plugin.views.gsp

import javax.inject.Inject

import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.process.JavaExecSpec

/**
 * Writes the tag library index describing the tag libraries in this project.
 *
 * <p>The index has to exist before anything that resolves tag calls is compiled, which is why this
 * runs ahead of compilation rather than being produced as a side effect of it. Generating it for the
 * whole source set at once is also what lets a renamed or deleted tag library disappear from it,
 * where an index accumulated class by class keeps describing tags that no longer exist.
 *
 * <p>The work runs in a forked process against the project's own compile classpath, because the rules
 * that decide what a tag is belong to the framework being built rather than to the build tooling, and
 * must be the same rules the application applies when it starts.
 *
 * @since 8.0.0
 */
@CacheableTask
@CompileStatic
abstract class GenerateTagLibraryIndexTask extends DefaultTask {

    static final String GENERATOR_CLASS = 'org.grails.taglib.index.TagLibraryIndexGenerator'

    private final ExecOperations execOperations

    @Inject
    GenerateTagLibraryIndexTask(ExecOperations execOperations) {
        this.execOperations = execOperations
        description = 'Generates the tag library index used to resolve tag calls at compile time'
        group = 'build'
    }

    /**
     * The directory holding tag library sources, normally {@code grails-app/taglib}.
     */
    @InputDirectory
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getSourceDirectory()

    /**
     * Where the index is written. Placed on the compile classpath and packaged with the artifact.
     */
    @OutputDirectory
    abstract DirectoryProperty getDestinationDirectory()

    /**
     * The classpath the generator runs against, which supplies the framework's discovery rules.
     */
    @Classpath
    abstract ConfigurableFileCollection getGeneratorClasspath()

    /**
     * Whether this compilation writes parameter names into class files. It decides whether a tag's
     * attributes and body parameters have to carry those names to be dispatchable, so the index must
     * be generated under the same setting the sources are compiled with.
     */
    @Input
    abstract Property<Boolean> getParameterNamesRetained()

    /**
     * The source encoding, matching the one compilation uses.
     */
    @Input
    @Optional
    abstract Property<String> getSourceEncoding()

    @TaskAction
    void generate() {
        File source = sourceDirectory.get().asFile
        File destination = destinationDirectory.get().asFile
        destination.mkdirs()
        execOperations.javaexec { JavaExecSpec spec ->
            spec.mainClass.set(GENERATOR_CLASS)
            spec.classpath = generatorClasspath
            spec.args(
                    source.canonicalPath,
                    destination.canonicalPath,
                    String.valueOf(parameterNamesRetained.getOrElse(true)),
                    sourceEncoding.getOrElse('UTF-8')
            )
        }.assertNormalExitValue()
    }
}

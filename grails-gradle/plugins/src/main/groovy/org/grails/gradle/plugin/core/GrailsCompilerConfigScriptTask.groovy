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
package org.grails.gradle.plugin.core

import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Writes the Groovy compiler configuration script a {@link org.gradle.api.tasks.compile.GroovyCompile}
 * task compiles with.
 *
 * <p>The script cannot be assigned from a task action: Gradle finalizes task properties before any
 * action runs, so from Gradle 9.7 on — where {@code GroovyCompileOptions} became a lazy property —
 * assigning {@code groovyOptions.configurationScript} at execution time fails. Assigning it during
 * configuration then makes it an input file that must exist before compilation starts, which is what
 * this task guarantees.</p>
 *
 * <p>The script is a plain {@link Input}, built entirely from configuration state, so this task is
 * up-to-date checked like any other rather than opting out of state tracking.</p>
 *
 * @since 8.0
 */
@CompileStatic
abstract class GrailsCompilerConfigScriptTask extends DefaultTask {

    /**
     * The complete script: the Grails compiler configuration combined with any
     * {@code configurationScript} the build configured for the compile task.
     */
    @Input
    abstract Property<String> getScript()

    @OutputFile
    abstract RegularFileProperty getOutputFile()

    @TaskAction
    void writeScript() {
        File target = outputFile.get().asFile
        target.parentFile.mkdirs()
        target.text = script.get()
    }
}

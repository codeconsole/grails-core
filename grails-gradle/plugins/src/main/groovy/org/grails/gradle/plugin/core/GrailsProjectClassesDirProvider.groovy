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

import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.process.CommandLineArgumentProvider

import grails.util.BuildSettings

/**
 * Provides the {@code -Dgrails.project.class.dir} system property (see {@link BuildSettings#PROJECT_CLASSES_DIR})
 * to forked JVM tasks: where the main source set's Groovy classes are compiled, relative to the project directory.
 *
 * <p>In development a changed source file is compiled again into {@link BuildSettings#BUILD_CLASSES_PATH}, which
 * {@code GrailsApp.recompile} resolves against the application directory. Without this property that path falls back
 * to {@code build/classes/groovy/main}, so an application whose build directory is elsewhere (a second instance of
 * one checkout built with {@code -PbuildDir}, say) had each change compiled where it does not load classes from:
 * the artefact was reloaded from the class it already had, and the old code went on answering.</p>
 *
 * <p>The path is relative because {@code GrailsApp.recompile} joins it to the application directory; a directory
 * outside the project comes out as a path that climbs out of it, which joins correctly too. It is read when the task
 * runs, so a build directory changed after the plugin is applied is the one passed.</p>
 *
 * <p>Both values are {@link Internal}, as for {@link GrailsAppBaseDirProvider}: the classes are the task's classpath,
 * already tracked there.</p>
 */
@CompileStatic
class GrailsProjectClassesDirProvider implements CommandLineArgumentProvider {

    @Internal
    final File projectDir

    @Internal
    final Provider<Directory> classesDir

    GrailsProjectClassesDirProvider(File projectDir, Provider<Directory> classesDir) {
        this.projectDir = projectDir
        this.classesDir = classesDir
    }

    @Override
    Iterable<String> asArguments() {
        Directory directory = classesDir.getOrNull()
        if (directory == null) {
            return []
        }
        String relative = projectDir.toPath().relativize(directory.asFile.toPath()).toString()
        ["-D${BuildSettings.PROJECT_CLASSES_DIR}=${relative}".toString()]
    }
}

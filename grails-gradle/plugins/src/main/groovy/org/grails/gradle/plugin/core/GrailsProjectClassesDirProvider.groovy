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

import java.nio.file.Path

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
 * <p>The path is relative because {@code GrailsApp.recompile} joins it to the application directory, and its names
 * are separated by {@code /} on every platform, as the fallback is, because {@code IOUtils} compares it with class
 * locations written as URLs. When the classes directory has no path relative to the project, such as one on another
 * drive on Windows, no property is passed and the application keeps the fallback.</p>
 *
 * <p>A classes directory outside the project gives a path that climbs out of it ({@code ../}). Joining it, as
 * {@code GrailsApp.recompile}, {@code MainClassFinder} and the i18n plugin do, resolves correctly; the {@code IOUtils}
 * lookups that compare it with a class location find no match and fall back as they did before.</p>
 *
 * <p>{@code GrailsApp.recompile} also joins this path to the directory of a plugin subproject whose sources it
 * watches, so it assumes every project lays out its build directory in the same way relative to itself, as Gradle's
 * {@code -PbuildDir} or an {@code allprojects} block does. A plugin subproject whose build directory is laid out
 * differently from the application's has its changes compiled where it does not load classes from.</p>
 *
 * <p>Both values are {@link Internal}, as for {@link GrailsAppBaseDirProvider}: the classes are the task's classpath,
 * already tracked there. The directory is read when the task runs, so a build directory changed after the plugin is
 * applied is the one passed.</p>
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
        String relative = relativePath(projectDir.toPath(), classesDir.get().asFile.toPath())
        relative ? ["-D${BuildSettings.PROJECT_CLASSES_DIR}=${relative}".toString()] : []
    }

    /**
     * {@code classesDir} relative to {@code projectDir}, its names separated by {@code /}; null when it has no path
     * relative to the project, such as one on another drive on Windows.
     */
    static String relativePath(Path projectDir, Path classesDir) {
        try {
            projectDir.relativize(classesDir).collect { Path name -> name.toString() }.join('/')
        }
        catch (IllegalArgumentException ignored) {
            null
        }
    }
}

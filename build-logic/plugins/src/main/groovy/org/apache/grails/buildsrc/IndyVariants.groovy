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

import groovy.transform.CompileStatic

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.GroovyRuntime
import org.gradle.api.tasks.GroovySourceDirectorySet
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.jvm.toolchain.JavaToolchainService

/**
 * Publishes every Groovy module of the framework twice: the main artifact compiled with
 * {@code invokedynamic} disabled, and an {@code indy} classifier artifact compiled with it enabled.
 * An application picks between them by setting {@code grails.indy}.
 *
 * <p>The second artifact is a plain Maven classifier and adds no variant to the published metadata,
 * so nothing about how anyone else resolves these modules changes. The application side turns the
 * classifier into something selectable.
 *
 * <p>Mirrors {@code GrailsIndyVariants} from the Grails Gradle plugins, which does the same for
 * plugins built outside this repository. That class is not on build-logic's classpath — the two
 * builds are independent — so the attribute name, classifier and task names are duplicated here and
 * must be kept in step. The same duplication already exists for {@code BaseDirArgumentProvider}.
 *
 * <p>Both implementations are idempotent and agree on their task names, so the modules of this
 * repository that apply the {@code grails-plugin} Gradle plugin are configured exactly once no
 * matter which of the two reaches them first.
 */
@CompileStatic
class IndyVariants {

    /** Must match {@code GrailsIndyVariants.INDY_CLASSIFIER}. */
    static final String INDY_CLASSIFIER = 'indy'

    /** Must match {@code GrailsIndyVariants.INDY_MANIFEST_ATTRIBUTE}. */
    static final String INDY_MANIFEST_ATTRIBUTE = 'Grails-Indy-Artifact'

    /** Must match {@code GrailsIndyVariants.INDY_COMPILE_TASK_NAME}. */
    static final String INDY_COMPILE_TASK_NAME = 'compileIndyGroovy'

    /** Must match {@code GrailsIndyVariants.INDY_JAR_TASK_NAME}. */
    static final String INDY_JAR_TASK_NAME = 'indyJar'

    private IndyVariants() {
    }

    /**
     * Adds the second compilation and its published variants to a module.
     *
     * <p>Only modules that are actually published are configured: an unpublished module has no
     * consumer that could ask for the other flavour, so compiling it twice would be wasted work.
     *
     * @param project the module to configure
     */
    static void configure(Project project) {
        project.plugins.withId('groovy') {
            project.plugins.withId('maven-publish') {
                if (project.tasks.names.contains(INDY_JAR_TASK_NAME)) {
                    return
                }
                if (GradleUtils.lookupPropertyByType(project, 'skipJavaComponent', Boolean)) {
                    return
                }

                SourceSet main = project.extensions.getByType(SourceSetContainer)
                        .findByName(SourceSet.MAIN_SOURCE_SET_NAME)
                if (main == null) {
                    return
                }

                TaskProvider<GroovyCompile> indyCompile = registerIndyCompileTask(project, main)
                TaskProvider<Jar> indyJar = registerIndyJarTask(project, indyCompile)

                PublishingExtension publishing = project.extensions.getByType(PublishingExtension)
                publishing.publications.withType(MavenPublication).configureEach { MavenPublication publication ->
                    publication.artifact(indyJar)
                }

                project.tasks.named('jar', Jar).configure { Jar jar ->
                    jar.manifest.attributes((INDY_MANIFEST_ATTRIBUTE): project.provider {
                        "${project.group}:${project.name}".toString()
                    })
                }
            }
        }
    }

    private static TaskProvider<GroovyCompile> registerIndyCompileTask(Project project, SourceSet main) {
        GroovyRuntime groovyRuntime = project.extensions.getByType(GroovyRuntime)
        JavaPluginExtension javaExtension = project.extensions.getByType(JavaPluginExtension)
        Provider<Directory> destination = project.layout.buildDirectory.dir("classes/groovy/${INDY_CLASSIFIER}")

        return project.tasks.register(INDY_COMPILE_TASK_NAME, GroovyCompile) { GroovyCompile compile ->
            compile.description = 'Compiles the main Groovy source set with invokedynamic enabled.'
            compile.group = BasePlugin.BUILD_GROUP

            compile.source = main.extensions.getByType(GroovySourceDirectorySet)
            compile.classpath = main.compileClasspath
            compile.groovyClasspath = groovyRuntime.inferGroovyClasspath(main.compileClasspath)
            compile.destinationDirectory.set(destination)

            // The one intentional difference from the default compilation. Everything else is left to
            // the conventions CompilePlugin applies to all GroovyCompile tasks, so this task shares
            // the encoding, fork settings and compiler configuration script of the default one.
            compile.groovyOptions.optimizationOptions.put('indy', true)

            compile.sourceCompatibility = javaExtension.sourceCompatibility.toString()
            compile.targetCompatibility = javaExtension.targetCompatibility.toString()
            if (javaExtension.toolchain.languageVersion.present) {
                JavaToolchainService toolchains = project.extensions.getByType(JavaToolchainService)
                compile.javaLauncher.set(toolchains.launcherFor(javaExtension.toolchain))
            }
        }
    }

    private static TaskProvider<Jar> registerIndyJarTask(Project project, TaskProvider<GroovyCompile> indyCompile) {
        return project.tasks.register(INDY_JAR_TASK_NAME, Jar) { Jar jar ->
            jar.description = 'Assembles a jar whose Groovy classes are compiled with invokedynamic.'
            jar.group = BasePlugin.BUILD_GROUP
            jar.archiveClassifier.set(INDY_CLASSIFIER)

            // Mirror the main jar rather than reassembling it: a plugin's jar also carries AST
            // classes copied in from another source set, plus staged command and template resources
            // laid out at paths this task does not know. Listing the pieces would silently omit them,
            // and reusing the main jar's own copy spec would import its duplicatesStrategy with it.
            // Unpacking the finished jar keeps the two artifacts identical by construction: the indy
            // classes are added first and duplicates dropped, so they win at the paths they share
            // with the main jar's Groovy classes and everything else arrives untouched.
            jar.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            jar.from(indyCompile.flatMap { GroovyCompile compile -> compile.destinationDirectory })
            jar.from(project.tasks.named('jar', Jar).flatMap { Jar mainJar -> mainJar.archiveFile }
                    .map { RegularFile mainArchive -> project.zipTree(mainArchive) }) { CopySpec spec ->
                // The Jar task writes its own manifest.
                spec.exclude('META-INF/MANIFEST.MF')
            }
        }
    }

}

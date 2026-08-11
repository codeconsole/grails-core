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

import groovy.transform.CompileStatic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.CopySpec
import groovy.transform.CompileDynamic
import org.gradle.api.file.Directory
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetOutput
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.War
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService

import org.grails.gradle.plugin.util.SourceSets

/**
 * A plugin that adds support for compiling Groovy Server Pages (GSP)
 *
 * @author Graeme Rocher
 * @since 3.0
 */
@CompileStatic
class GroovyPagePlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.pluginManager.withPlugin('groovy') {
            configureProject(project)
        }
    }

    /**
     * Whether compilation keeps parameter names, which decides whether a tag's attributes and body
     * parameters have to carry those names to be dispatchable. The index has to be generated under the
     * same setting the sources are compiled with, or it would describe a different set of tags.
     */
    @CompileDynamic
    private static Provider<Boolean> resolvePreserveParameterNames(Project project) {
        project.provider {
            Object grails = project.extensions.findByName('grails')
            Object preserve = grails?.hasProperty('preserveParameterNames') ? grails.preserveParameterNames : null
            if (preserve instanceof Provider) {
                return ((Provider) preserve).getOrElse(true) as Boolean
            }
            preserve == null ? Boolean.TRUE : (preserve as Boolean)
        }
    }

    private void configureProject(Project project) {
        TaskContainer tasks = project.tasks

        SourceSet mainSourceSet = SourceSets.findMainSourceSet(project)
        SourceSetOutput output = mainSourceSet?.output
        FileCollection classesDirs = resolveClassesDirs(output, project)
        Provider<Directory> destDir = project.layout.buildDirectory.dir('gsp-classes/main')
        Provider<Directory> webappDestDir = project.layout.buildDirectory.dir('gsp-classes/webapp')
        output?.dir('gsp-classes')

        FileCollection allClasspath = project.getObjects().fileCollection().from(
                [
                        project.configurations.named('compileClasspath'),
                        classesDirs,
                        project.configurations.findByName('providedCompile') ?: null
                ].findAll { it }
        )

        // The Java the rest of the project is built with, so that pages are built with it too.
        // Absent a toolchain this resolves to the JVM running Gradle, which is what compiling
        // pages fell back to before and remains the right answer when nothing else was asked for.
        JavaPluginExtension javaExtension = project.extensions.getByType(JavaPluginExtension)
        JavaToolchainService toolchains = project.extensions.getByType(JavaToolchainService)
        Provider<JavaLauncher> launcher = toolchains.launcherFor(javaExtension.toolchain)

        // The index describes the tag libraries in this project and has to exist before anything that
        // resolves tag calls against it is compiled. It is generated from source rather than from
        // compiled classes, so its classpath is the compile classpath alone: adding this project's own
        // output would make it wait for the compilation it is meant to precede.
        Provider<Directory> tagLibIndexDir = project.layout.buildDirectory.dir('generated/grails-taglibs')
        def generateTagLibraryIndex = tasks.register('generateTagLibraryIndex', GenerateTagLibraryIndexTask) {
            it.sourceDirectories.from(project.layout.projectDirectory.dir('grails-app/taglib'))
            it.destinationDirectory.set(tagLibIndexDir)
            it.generatorClasspath.from(project.configurations.named('compileClasspath'))
            it.parameterNamesRetained.set(resolvePreserveParameterNames(project))
        }
        mainSourceSet?.resources?.srcDir(tagLibIndexDir)
        tasks.named('processResources').configure { it.dependsOn(generateTagLibraryIndex) }

        def compileGroovyPages = tasks.register('compileGroovyPages', GroovyPageForkCompileTask) {
            it.destinationDirectory.set(destDir)
            it.tmpDirPath = getTmpDirPath(project)
            it.source = project.layout.projectDirectory.dir('grails-app/views')
            it.serverpath.set('/WEB-INF/grails-app/views/')
            it.classpath = allClasspath
            it.javaLauncher.convention(launcher)
        }

        def compileWebappGroovyPages = tasks.register('compileWebappGroovyPages', GroovyPageForkCompileTask) {
            it.destinationDirectory.set(webappDestDir)
            it.source = project.layout.projectDirectory.dir('src/main/webapp')
            it.tmpDirPath = getTmpDirPath(project)
            it.serverpath.set('/')
            it.classpath = allClasspath
            it.javaLauncher.convention(launcher)
        }

        compileGroovyPages.configure {
            it.dependsOn(
                    tasks.named('classes'),
                    compileWebappGroovyPages,
                    // Pages resolve tag calls against the index, so it has to be written first.
                    generateTagLibraryIndex
            )
        }

        tasks.withType(War).configureEach { War war ->
            war.dependsOn(compileGroovyPages)
            war.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            if (war.name == 'bootWar') {
                war.from(destDir) { CopySpec it ->
                    it.into('WEB-INF/classes')
                }
                war.from(webappDestDir) { CopySpec it ->
                    it.into('WEB-INF/classes')
                }
            } else if (war.name == 'war') {
                war.from(destDir)
                war.from(webappDestDir)
            }

            if (war.classpath) {
                war.classpath = war.classpath + project.files(destDir, webappDestDir)
            } else {
                war.classpath = project.files(destDir, webappDestDir)
            }
        }

        tasks.withType(Jar).configureEach { Jar jar ->
            jar.dependsOn(compileGroovyPages)
            jar.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            if (!(jar instanceof War)) {
                if (jar.name == 'bootJar') {
                    jar.from(destDir) { CopySpec it ->
                        it.into('BOOT-INF/classes')
                    }
                    jar.from(webappDestDir) { CopySpec it ->
                        it.into('BOOT-INF/classes')
                    }
                } else if (jar.name == 'jar') {
                    jar.from(destDir)
                    jar.from(webappDestDir)
                }
            }
        }
    }

    protected FileCollection resolveClassesDirs(SourceSetOutput output, Project project) {
        output?.classesDirs ?: project.files(project.layout.buildDirectory.dir('classes/main'))
    }

    protected String getTmpDirPath(Project project) {
        File tmpdir = project.layout.buildDirectory.dir('gsptmp').get().asFile
        return tmpdir.absolutePath
    }

}

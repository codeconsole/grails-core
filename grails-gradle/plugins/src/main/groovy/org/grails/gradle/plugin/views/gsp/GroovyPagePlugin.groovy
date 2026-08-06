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
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.util.PatternFilterable
import org.gradle.api.file.CopySpec
import org.gradle.api.file.Directory
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetOutput
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.War

import org.grails.gradle.plugin.scaffolding.GenerateScaffoldedViewsTask
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

        // Scaffolded views are expanded from their templates here and staged alongside the
        // application's own, so a single compile produces a single gsp/views.properties. Compiling
        // them separately would produce a second manifest, and the jar's duplicate handling would
        // silently keep only one of them.
        Provider<Directory> stagedViews = project.layout.buildDirectory.dir('generated/views')
        def generateScaffoldedViews = tasks.register(
                'generateScaffoldedViews', GenerateScaffoldedViewsTask) { GenerateScaffoldedViewsTask it ->
            it.group = BasePlugin.BUILD_GROUP
            it.description = 'Expands the views of scaffolded controllers so they can be precompiled'
            it.classesDirs.from(classesDirs)
            it.templateClasspath.from(project.configurations.named('compileClasspath'))
            it.templateOverrides.from(
                    project.fileTree(project.layout.projectDirectory.dir('src/main/templates/scaffolding'))
                            .matching { PatternFilterable p -> p.include('*.gsp') })
            it.applicationViews.from(
                    project.fileTree(project.layout.projectDirectory.dir('grails-app/views'))
                            .matching { PatternFilterable p -> p.include('**/*.gsp') })
            it.outputDirectory.set(project.layout.buildDirectory.dir('generated/scaffolded-views'))
        }

        def stageGroovyPages = tasks.register('stageGroovyPages', Sync) { Sync it ->
            it.description = 'Collects the application and scaffolded views for GSP compilation'
            it.into(stagedViews)
            it.from(project.layout.projectDirectory.dir('grails-app/views'))
            it.from(generateScaffoldedViews)
            // the application's own page wins, matching how the view resolvers are ordered
            it.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }

        def compileGroovyPages = tasks.register('compileGroovyPages', GroovyPageForkCompileTask) {
            it.destinationDirectory.set(destDir)
            it.tmpDirPath = getTmpDirPath(project)
            // resolved here because the setter takes a directory, not a provider: it has to set
            // both srcDir and the SourceTask inputs, and setting srcDir alone compiles nothing
            it.source = stagedViews.get()
            it.serverpath.set('/WEB-INF/grails-app/views/')
            it.classpath = allClasspath
            it.dependsOn(stageGroovyPages)
        }

        def compileWebappGroovyPages = tasks.register('compileWebappGroovyPages', GroovyPageForkCompileTask) {
            it.destinationDirectory.set(webappDestDir)
            it.source = project.layout.projectDirectory.dir('src/main/webapp')
            it.tmpDirPath = getTmpDirPath(project)
            it.serverpath.set('/')
            it.classpath = allClasspath
        }

        compileGroovyPages.configure {
            it.dependsOn(
                    tasks.named('classes'),
                    compileWebappGroovyPages
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

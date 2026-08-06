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

        // A scaffolded controller has no views of its own, so they are expanded from their
        // templates and compiled with the rest. They are staged together rather than compiled
        // separately, because a second compilation writes a second gsp/views.properties and the
        // archive tasks discard duplicates, losing the views one of them lists.
        //
        // Only a project that scaffolds pays for this. Staging copies the views, and pointing the
        // compilation at the copy would change what every other project compiles for no reason.
        Directory appViews = project.layout.projectDirectory.dir('grails-app/views')
        boolean scaffolds = scaffoldsAnyController(project)

        Directory viewsToCompile = appViews
        if (scaffolds) {
            Provider<Directory> stagedViews = project.layout.buildDirectory.dir('generated/views')
            def generateScaffoldedViews = tasks.register(
                    'generateScaffoldedViews', GenerateScaffoldedViewsTask) { GenerateScaffoldedViewsTask it ->
                it.group = BasePlugin.BUILD_GROUP
                it.description = 'Expands the views of scaffolded controllers so they can be precompiled'
                // the classes directory is written by more than one task, so the dependency is stated
                // against the compilation rather than inferred from the directory
                it.dependsOn(tasks.named('compileJava'))
                ['compileGroovy', 'copyAstClasses'].each { String name ->
                    if (project.tasks.findByName(name)) {
                        it.dependsOn(tasks.named(name))
                    }
                }
                it.classesDirs.from(classesDirs)
                it.templateClasspath.from(project.configurations.named('compileClasspath'))
                it.templateOverrides.from(
                        project.fileTree(project.layout.projectDirectory.dir('src/main/templates/scaffolding'))
                                .matching { PatternFilterable p -> p.include('*.gsp') })
                it.applicationViews.from(
                        project.fileTree(appViews)
                                .matching { PatternFilterable p -> p.include('**/*.gsp') })
                it.outputDirectory.set(project.layout.buildDirectory.dir('generated/scaffolded-views'))
            }

            tasks.register('stageGroovyPages', Sync) { Sync it ->
                it.description = 'Collects the application and scaffolded views for GSP compilation'
                it.into(stagedViews)
                it.from(appViews)
                it.from(generateScaffoldedViews)
                // the application's own page wins, matching how the view resolvers are ordered
                it.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            }
            viewsToCompile = stagedViews.get()
        }

        def compileGroovyPages = tasks.register('compileGroovyPages', GroovyPageForkCompileTask) {
            it.destinationDirectory.set(destDir)
            it.tmpDirPath = getTmpDirPath(project)
            // the setter takes a directory rather than a provider: it has to set both srcDir
            // and the SourceTask inputs, and setting srcDir alone compiles nothing
            it.source = viewsToCompile
            it.serverpath.set('/WEB-INF/grails-app/views/')
            it.classpath = allClasspath
            if (scaffolds) {
                it.dependsOn(tasks.named('stageGroovyPages'))
            }
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

    /**
     * Whether any controller in this project is scaffolded, read from the sources because the
     * decision is needed before anything is compiled. A project that does not scaffold compiles its
     * views where they are, as it did before.
     */
    protected boolean scaffoldsAnyController(Project project) {
        File controllers = project.layout.projectDirectory.dir('grails-app/controllers').asFile
        if (!controllers.isDirectory()) {
            return false
        }
        boolean found = false
        controllers.eachFileRecurse { File file ->
            if (!found && file.name.endsWith('.groovy') && file.text.contains('@Scaffold')) {
                found = true
            }
        }
        found
    }

    protected FileCollection resolveClassesDirs(SourceSetOutput output, Project project) {
        output?.classesDirs ?: project.files(project.layout.buildDirectory.dir('classes/main'))
    }

    protected String getTmpDirPath(Project project) {
        File tmpdir = project.layout.buildDirectory.dir('gsptmp').get().asFile
        return tmpdir.absolutePath
    }

}

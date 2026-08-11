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
import org.gradle.api.tasks.compile.GroovyCompile
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
import org.gradle.language.jvm.tasks.ProcessResources
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

    /**
     * Whether the build declared that every tag library it uses is described at compile time, so that
     * a tag missing from the index is a mistake rather than something contributed later.
     */
    @CompileDynamic
    private static Provider<Boolean> resolveStrictTags(Project project) {
        project.provider {
            Object compileStatic = project.extensions.findByName('grails')?.compileStatic
            Object strict = compileStatic?.hasProperty('strictTags') ? compileStatic.strictTags : null
            strict instanceof Provider ? ((Provider) strict).getOrElse(false) as Boolean : Boolean.FALSE
        }
    }

    /**
     * The namespaces the build declared as filled in while the application runs.
     */
    @CompileDynamic
    private static Provider<Set<String>> resolveDynamicTagNamespaces(Project project) {
        project.provider {
            Object compileStatic = project.extensions.findByName('grails')?.compileStatic
            Object namespaces = compileStatic?.hasProperty('dynamicTagNamespaces') ?
                    compileStatic.dynamicTagNamespaces : null
            namespaces instanceof Provider ?
                    (((Provider) namespaces).getOrElse([] as Set) as Set<String>) : ([] as Set<String>)
        }
    }

    /**
     * The Groovy source roots of a source set, which is where a type this project declares is found.
     */
    @CompileDynamic
    private static Set<File> resolveGroovySourceRoots(SourceSet sourceSet) {
        Object groovy = sourceSet?.extensions?.findByName('groovy')
        groovy ? (groovy.srcDirs as Set<File>) : ([] as Set<File>)
    }

    private void configureProject(Project project) {
        TaskContainer tasks = project.tasks

        SourceSet mainSourceSet = SourceSets.findMainSourceSet(project)
        SourceSetOutput output = mainSourceSet?.output
        FileCollection classesDirs = resolveClassesDirs(output, project)
        Provider<Directory> destDir = project.layout.buildDirectory.dir('gsp-classes/main')
        Provider<Directory> webappDestDir = project.layout.buildDirectory.dir('gsp-classes/webapp')
        output?.dir('gsp-classes')

        // The Java the rest of the project is built with, so that pages are built with it too.
        // Absent a toolchain this resolves to the JVM running Gradle, which is what compiling
        // pages fell back to before and remains the right answer when nothing else was asked for.
        JavaPluginExtension javaExtension = project.extensions.getByType(JavaPluginExtension)
        JavaToolchainService toolchains = project.extensions.getByType(JavaToolchainService)
        Provider<JavaLauncher> launcher = toolchains.launcherFor(javaExtension.toolchain)

        // The index is written twice, because the two things that read it need different guarantees.
        //
        // This one exists before this project is compiled, so that a call to a tag the project itself
        // declares can be resolved as it compiles. It is read from source, so it cannot describe
        // everything: a tag library referring to a type written in another language, or generated by
        // the build, is left out, and what was missed is recorded so that nothing in an incompletely
        // described namespace is reported as a misspelling. It is never packaged - a consumer must not
        // be given a partial description - and pages are not compiled against it either.
        Provider<Directory> tagLibIndexDir = project.layout.buildDirectory.dir('generated/grails-taglibs')
        def generateTagLibraryIndex = tasks.register('generateTagLibraryIndex', GenerateTagLibraryIndexTask) {
            it.sourceDirectories.from(project.layout.projectDirectory.dir('grails-app/taglib'))
            it.destinationDirectory.set(tagLibIndexDir)
            it.generatorClasspath.from(project.configurations.named('compileClasspath'))
            // A tag library referring to a service, base class or trait of this project needs that
            // source to be read, not guessed, or it would be described wrongly or not at all.
            it.resolutionSourceRoots.from(project.provider { resolveGroovySourceRoots(mainSourceSet) })
            it.parameterNamesRetained.set(resolvePreserveParameterNames(project))
            it.strictTags.set(resolveStrictTags(project))
            it.dynamicTagNamespaces.set(resolveDynamicTagNamespaces(project))
            it.javaLauncher.convention(launcher)
        }
        FileCollection tagLibIndex = project.files(tagLibIndexDir).builtBy(generateTagLibraryIndex)

        // And this one is written again once the project has been compiled, with its own classes on
        // the classpath, where every tag library resolves whatever language it was written in. It is
        // the authoritative index: the one pages are compiled against, the one packaged, and the one a
        // project depending on this one reads. Every run replaces the directory, so a renamed or
        // deleted tag library cannot survive in it.
        Provider<Directory> packagedIndexDir =
                project.layout.buildDirectory.dir('generated/grails-taglibs-packaged')
        def packageTagLibraryIndex = tasks.register('packageTagLibraryIndex', GenerateTagLibraryIndexTask) {
            it.description = 'Regenerates the tag library index against the compiled project'
            it.sourceDirectories.from(project.layout.projectDirectory.dir('grails-app/taglib'))
            it.destinationDirectory.set(packagedIndexDir)
            // The whole output, which is built by classes, so this waits for everything that writes
            // into it rather than for the compile tasks alone.
            it.generatorClasspath.from(project.configurations.named('compileClasspath'), output)
            it.parameterNamesRetained.set(resolvePreserveParameterNames(project))
            it.strictTags.set(resolveStrictTags(project))
            it.dynamicTagNamespaces.set(resolveDynamicTagNamespaces(project))
            it.javaLauncher.convention(launcher)
        }
        FileCollection packagedTagLibIndex =
                project.files(packagedIndexDir).builtBy(packageTagLibraryIndex)

        // Pages resolve tag calls against the index and are compiled in a process of their own, so the
        // authoritative index has to be on their classpath.
        FileCollection allClasspath = project.getObjects().fileCollection().from(
                [
                        project.configurations.named('compileClasspath'),
                        classesDirs,
                        packagedTagLibIndex,
                        project.configurations.findByName('providedCompile') ?: null
                ].findAll { it }
        )

        // Carried into the artifact and onto the runtime classpath directly rather than through
        // processResources, which the classes task waits for - and this waits for the classes task.
        if (mainSourceSet != null) {
            mainSourceSet.runtimeClasspath = mainSourceSet.runtimeClasspath.plus(packagedTagLibIndex)
        }

        // Compiling this project's own controllers and tag libraries has to see the index too, or a
        // call to a tag the same project declares cannot be resolved. The directory joins the compile
        // classpath rather than the source set output, which would make the index wait for the
        // compilation it exists to precede.
        tasks.named('compileGroovy', GroovyCompile).configure { GroovyCompile compile ->
            compile.classpath = compile.classpath.plus(tagLibIndex)
        }

        String settingsPath = "${TagLibraryIndexFiles.INDEX_LOCATION}/${TagLibraryIndexFiles.SETTINGS_FILE}"
        tasks.withType(Jar).configureEach { Jar archive ->
            archive.from(packagedTagLibIndex) { CopySpec spec ->
                // The settings say how this project is compiled, not what its tag libraries declare,
                // so they stay out of the artifact: a consumer must not inherit them.
                spec.exclude(settingsPath)
            }
        }

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

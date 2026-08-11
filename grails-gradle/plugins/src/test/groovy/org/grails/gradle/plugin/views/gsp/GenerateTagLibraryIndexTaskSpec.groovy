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

import java.nio.file.Path

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The index has to be generated before anything that resolves tag calls is compiled, and has to travel
 * with the artifact so that a project depending on this one can resolve its tags too. Both are
 * properties of how the task is wired rather than of what it writes.
 */
class GenerateTagLibraryIndexTaskSpec extends Specification {

    @TempDir
    Path projectDir

    Project project

    def setup() {
        // A tag library has to be present for the task to have any input: it is skipped when the
        // source directory is absent or empty, which is what a project with no tag libraries wants.
        File taglibDir = new File(projectDir.toFile(), 'grails-app/taglib/demo')
        taglibDir.mkdirs()
        new File(taglibDir, 'DemoTagLib.groovy').text = '''
            package demo
            class DemoTagLib {
                static namespace = 'demo'
                def hello(Map attrs) { }
            }
        '''
        project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build()
        project.pluginManager.apply('groovy')
        project.pluginManager.apply(GroovyPagePlugin)
    }

    void 'the task is registered'() {
        expect:
        project.tasks.findByName('generateTagLibraryIndex') instanceof GenerateTagLibraryIndexTask
    }

    void 'it reads the tag library source directory and writes into the build directory'() {
        given:
        GenerateTagLibraryIndexTask task = project.tasks.getByName('generateTagLibraryIndex') as GenerateTagLibraryIndexTask

        expect:
        task.sourceDirectories.files*.canonicalFile ==
                [new File(projectDir.toFile(), 'grails-app/taglib').canonicalFile]
        task.destinationDirectory.get().asFile.canonicalFile ==
                new File(projectDir.toFile(), 'build/generated/grails-taglibs').canonicalFile
    }

    void 'page compilation runs after the index exists'() {
        expect: 'pages resolve tag calls against the index, so it has to be written first'
        dependencyNames(project.tasks.getByName('compileGroovyPages')).contains('generateTagLibraryIndex')
    }

    void 'the generator does not wait for this project to be compiled'() {
        given: 'it reads source, so requiring compiled output would invert the ordering it exists for'
        Task generate = project.tasks.getByName('generateTagLibraryIndex')

        expect:
        !dependencyNames(generate).contains('classes')
        !dependencyNames(generate).contains('compileGroovy')
    }

    void 'the index is packaged as a resource'() {
        given:
        SourceSet main = (project.extensions.getByType(SourceSetContainer)).getByName('main')

        expect: 'so that a project depending on this one can resolve its tags'
        main.resources.srcDirs*.canonicalFile.contains(
                new File(projectDir.toFile(), 'build/generated/grails-taglibs').canonicalFile)

        and: 'and resource processing waits for it to be written'
        dependencyNames(project.tasks.getByName('processResources')).contains('generateTagLibraryIndex')
    }

    private static Set<String> dependencyNames(Task task) {
        task.taskDependencies.getDependencies(task)*.name as Set
    }

    void 'further tag library source directories can be added'() {
        given: 'a project keeping tag libraries outside grails-app/taglib as well'
        GenerateTagLibraryIndexTask task = project.tasks.getByName('generateTagLibraryIndex') as GenerateTagLibraryIndexTask
        File extra = new File(projectDir.toFile(), 'src/main/groovy')

        when:
        task.sourceDirectories.from(extra)

        then: 'both are scanned, so tags declared in either resolve in the same compilation'
        task.sourceDirectories.files*.canonicalFile.contains(extra.canonicalFile)
        task.sourceDirectories.files.size() == 2
    }

    void 'the task declares its inputs and outputs so it can be skipped and cached'() {
        given:
        Task task = project.tasks.getByName('generateTagLibraryIndex')

        expect: 'a declared output directory, without which stale entries could never be detected'
        !task.outputs.files.isEmpty()

        and: 'declared inputs, so an unchanged source set does not regenerate'
        !task.inputs.files.isEmpty()

        and: 'and it is cacheable'
        task.class.superclass.isAnnotationPresent(org.gradle.api.tasks.CacheableTask) ||
                task.class.isAnnotationPresent(org.gradle.api.tasks.CacheableTask)
    }
}

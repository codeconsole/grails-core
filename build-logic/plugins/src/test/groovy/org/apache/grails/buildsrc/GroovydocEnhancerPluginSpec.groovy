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

import org.gradle.api.Project
import org.gradle.api.tasks.javadoc.Groovydoc
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

class GroovydocEnhancerPluginSpec extends Specification {

    @TempDir
    File projectDir

    void 'groovydoc classpath includes runtime-only jars that Class.forName needs'() {
        given: 'a groovy project whose runtime-only jar is not on the compile classpath'
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.extensions.extraProperties.set('javaVersion', 21)
        project.pluginManager.apply('groovy')
        project.pluginManager.apply(GroovydocEnhancerPlugin)

        File compileOnlyJar = new File(projectDir, 'compile-only.jar')
        File runtimeOnlyJar = new File(projectDir, 'runtime-only.jar')
        compileOnlyJar.bytes = [] as byte[]
        runtimeOnlyJar.bytes = [] as byte[]
        project.dependencies.add('compileOnly', project.files(compileOnlyJar))
        project.dependencies.add('runtimeOnly', project.files(runtimeOnlyJar))

        when: 'the groovydoc task classpath is resolved'
        Groovydoc groovydoc = project.tasks.named('groovydoc', Groovydoc).get()
        Set<File> groovydocFiles = groovydoc.classpath.files

        then: 'runtime-only jars are visible to groovydoc alongside compile-only jars'
        groovydocFiles.any { it.name == runtimeOnlyJar.name }
        groovydocFiles.any { it.name == compileOnlyJar.name }
    }

    void 'Groovydoc tasks in different projects do not overlap in a parallel build'() {
        given: 'two documentation tasks competing for the build JVM heap'
        new File(projectDir, 'settings.gradle').text = "include 'one', 'two'"
        ['one', 'two'].each { name ->
            File directory = new File(projectDir, name)
            directory.mkdirs()
            new File(directory, 'Sample.groovy').text = 'class Sample {}'
        }
        new File(projectDir, 'build.gradle').text = """
            plugins {
                id 'org.apache.grails.buildsrc.groovydoc-enhancer' apply false
            }
            subprojects {
                apply plugin: 'groovy'
                ext.javaVersion = 21
                apply plugin: 'org.apache.grails.buildsrc.groovydoc-enhancer'
                tasks.register('apiDocs', org.gradle.api.tasks.javadoc.Groovydoc) {
                    source file('Sample.groovy')
                    classpath = files()
                    groovyClasspath = files()
                    destinationDir = layout.buildDirectory.dir('docs').get().asFile
                    // Observe scheduling without generating a large documentation tree.
                    actions.clear()
                    doLast {
                        File active = rootProject.file('active-documentation-task')
                        assert active.createNewFile(): 'Groovydoc tasks overlapped'
                        try {
                            Thread.sleep(1000)
                            destinationDir.mkdirs()
                            new File(destinationDir, 'index.html').text = project.name
                        } finally {
                            active.delete()
                        }
                    }
                }
            }
        """

        when: 'Gradle can run both subprojects concurrently'
        def result = GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments('apiDocs', '--parallel', '--max-workers=2',
                        '-Dorg.gradle.jvmargs=-Xmx512m', '--stacktrace')
                .build()

        then: 'both tasks finish without simultaneous documentation work'
        result.task(':one:apiDocs').outcome == TaskOutcome.SUCCESS
        result.task(':two:apiDocs').outcome == TaskOutcome.SUCCESS
        new File(projectDir, 'one/build/docs/index.html').text == 'one'
        new File(projectDir, 'two/build/docs/index.html').text == 'two'
    }

}

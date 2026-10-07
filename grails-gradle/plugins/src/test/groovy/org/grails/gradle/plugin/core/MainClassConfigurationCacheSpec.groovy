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

import java.util.jar.JarFile

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner

class MainClassConfigurationCacheSpec extends GradleSpecification {

    def "bootJar records the main class found while the build runs with the configuration cache"() {
        given: 'an application whose main class is only known once it is compiled'
            GradleRunner runner = setupTestResourceProject('main-class-configuration-cache')

        when: 'the archive is built from a clean build, storing and then reusing the configuration cache entry'
            BuildResult stored = executeTask('clean', ['bootJar'])
            BuildResult reused = executeTask('clean', ['bootJar'])

        then:
            stored.output.contains('Configuration cache entry stored')
            reused.output.contains('Configuration cache entry reused')

        and: 'the archive starts the class that findMainClass found'
            startClass(new File(runner.projectDir, 'build/libs/main-class-configuration-cache.jar')) == 'example.Application'
    }

    def "bootRun runs the main class found while the build runs with the configuration cache"() {
        given:
            setupTestResourceProject('main-class-configuration-cache')

        when: 'the application is run from a clean build, storing and then reusing the configuration cache entry'
            BuildResult stored = executeTask('clean', ['bootRun'])
            BuildResult reused = executeTask('clean', ['bootRun'])

        then:
            stored.output.contains('Configuration cache entry stored')
            stored.output.contains('APPLICATION_STARTED')
            reused.output.contains('Configuration cache entry reused')
            reused.output.contains('APPLICATION_STARTED')
    }

    def "build scripts reading springBoot.mainClass and mainClassName get the class the build found"() {
        given: 'a task that reads both while it runs'
            GradleRunner runner = setupTestResourceProject('main-class-configuration-cache')
            new File(runner.projectDir, 'build.gradle') << '''
                tasks.register('printMainClass') {
                    dependsOn('findMainClass')
                    def springBootMainClass = springBoot.mainClass
                    def mainClassName = project.findProperty('mainClassName')
                    doLast {
                        println "springBoot.mainClass=${springBootMainClass.getOrNull()}"
                        println "mainClassName=${mainClassName.getOrNull()}"
                    }
                }
            '''.stripIndent()

        when: 'it runs from a clean build, storing and then reusing the configuration cache entry'
            BuildResult stored = executeTask('clean', ['printMainClass'])
            BuildResult reused = executeTask('clean', ['printMainClass'])

        then:
            stored.output.contains('Configuration cache entry stored')
            stored.output.contains('springBoot.mainClass=example.Application')
            stored.output.contains('mainClassName=example.Application')
            reused.output.contains('Configuration cache entry reused')
            reused.output.contains('springBoot.mainClass=example.Application')
            reused.output.contains('mainClassName=example.Application')
    }

    def "a build script may read springBoot.mainClass while the build is configured"() {
        given: 'a script reading the value before findMainClass has run'
            GradleRunner runner = setupTestResourceProject('main-class-configuration-cache')
            new File(runner.projectDir, 'build.gradle') << '''
                afterEvaluate {
                    println "configured with ${springBoot.mainClass.getOrNull()}"
                }
            '''.stripIndent()

        when:
            BuildResult result = executeTask('clean', ['bootJar'])

        then: 'it gets no value instead of failing, and the archive still starts the class found'
            result.output.contains('configured with null')
            startClass(new File(runner.projectDir, 'build/libs/main-class-configuration-cache.jar')) == 'example.Application'
    }

    private static String startClass(File jar) {
        new JarFile(jar).withCloseable { JarFile jarFile ->
            jarFile.manifest.mainAttributes.getValue('Start-Class')
        }
    }
}

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
import java.nio.file.Paths

import spock.lang.Specification

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder

class GrailsProjectClassesDirProviderSpec extends Specification {

    void 'the classes directory is passed relative to the project, its names joined by /'() {
        given:
        Project project = ProjectBuilder.builder().build()
        def classesDir = project.layout.buildDirectory.dir('classes/groovy/main')

        expect:
        new GrailsProjectClassesDirProvider(project.projectDir, classesDir).asArguments().toList() == [
                '-Dgrails.project.class.dir=build/classes/groovy/main'
        ]
    }

    void 'a path is joined by / whatever the platform separator: #classesDir'() {
        expect:
        GrailsProjectClassesDirProvider.relativePath(Paths.get('/app'), Paths.get(classesDir)) == relative

        where:
        classesDir                                         | relative
        '/app/build/classes/groovy/main'                   | 'build/classes/groovy/main'
        '/app/build-parent/build-8070/classes/groovy/main' | 'build-parent/build-8070/classes/groovy/main'
        '/elsewhere/build/classes/groovy/main'             | '../elsewhere/build/classes/groovy/main'
    }

    void 'a classes directory with no path relative to the project, such as one on another drive, passes nothing'() {
        given: 'paths that cannot be relativized, as for C:\\app and D:\\build on Windows'
        Path projectDir = Paths.get('/app')
        Path classesDir = Paths.get('build/classes/groovy/main')

        expect: 'the application keeps its fallback rather than bootRun failing'
        GrailsProjectClassesDirProvider.relativePath(projectDir, classesDir) == null
    }
}

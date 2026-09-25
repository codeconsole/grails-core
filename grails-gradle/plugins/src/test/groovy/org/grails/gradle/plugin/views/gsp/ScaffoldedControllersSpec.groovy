/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.grails.gradle.plugin.views.gsp

import spock.lang.Specification
import spock.lang.TempDir

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder

class ScaffoldedControllersSpec extends Specification {

    @TempDir
    File projectDir

    private boolean scaffolds(String controller) {
        if (controller != null) {
            File source = new File(projectDir, 'grails-app/controllers/com/example/BookController.groovy')
            source.parentFile.mkdirs()
            source.text = controller
        }
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        new GroovyPagePlugin().scaffoldsAnyController(project).get()
    }

    void 'a controller is scaffolded however the annotation is written'() {
        expect:
        scaffolds(controller) == expected

        where:
        controller                                                                                       | expected
        'import grails.plugin.scaffolding.annotation.Scaffold\n@Scaffold(Book)\nclass BookController {}' | true
        '@grails.plugin.scaffolding.annotation.Scaffold(Book)\nclass BookController {}'                  | true
        '@Scaffold(domain = Book, readOnly = true)\nclass BookController {}'                             | true
        '@ScaffoldingHelper\nclass BookController {}'                                                    | false
        'class BookController {}'                                                                        | false
        null                                                                                             | false
    }
}

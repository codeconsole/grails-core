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
package org.grails.web.pages

import spock.lang.Specification
import spock.lang.TempDir

import grails.util.BuildSettings

class GroovyPageForkedCompilerSpec extends Specification {

    @TempDir
    File dir

    void 'the directories of generated pages reach the page compiler from the build'() {
        given:
        String previous = System.getProperty(BuildSettings.GENERATED_GSP_DIRECTORIES)
        System.setProperty(BuildSettings.GENERATED_GSP_DIRECTORIES, 'grails-scaffolded, other')

        when:
        def compiler = new GroovyPageForkedCompiler(dir, dir, dir).createPageCompiler()

        then:
        compiler.generatedDirectories == ['grails-scaffolded', 'other']

        cleanup:
        previous == null ? System.clearProperty(BuildSettings.GENERATED_GSP_DIRECTORIES) :
                System.setProperty(BuildSettings.GENERATED_GSP_DIRECTORIES, previous)
    }

    void 'a generated page left out is printed, which is what the build shows of this process'() {
        given:
        File views = new File(dir, 'views')
        new File(views, 'generated').mkdirs()
        File broken = new File(views, 'generated/broken.gsp')
        broken.text = '<% def x = ; %>'
        File classes = new File(dir, 'classes')
        File work = new File(dir, 'work')
        [classes, work]*.mkdirs()
        def compiler = new GroovyPageForkedCompiler(views, classes, work)
        String previous = System.getProperty(BuildSettings.GENERATED_GSP_DIRECTORIES)
        System.setProperty(BuildSettings.GENERATED_GSP_DIRECTORIES, 'generated')
        PrintStream err = System.err
        ByteArrayOutputStream printed = new ByteArrayOutputStream()
        System.err = new PrintStream(printed, true, 'UTF-8')

        when:
        compiler.compile([broken])

        then:
        printed.toString('UTF-8').contains('Left out the generated page generated/broken.gsp')

        cleanup:
        System.err = err
        previous == null ? System.clearProperty(BuildSettings.GENERATED_GSP_DIRECTORIES) :
                System.setProperty(BuildSettings.GENERATED_GSP_DIRECTORIES, previous)
    }

    void 'without the setting no page is treated as generated'() {
        given:
        String previous = System.getProperty(BuildSettings.GENERATED_GSP_DIRECTORIES)
        System.clearProperty(BuildSettings.GENERATED_GSP_DIRECTORIES)

        expect:
        new GroovyPageForkedCompiler(dir, dir, dir).createPageCompiler().generatedDirectories.isEmpty()

        cleanup:
        previous == null ? System.clearProperty(BuildSettings.GENERATED_GSP_DIRECTORIES) :
                System.setProperty(BuildSettings.GENERATED_GSP_DIRECTORIES, previous)
    }
}

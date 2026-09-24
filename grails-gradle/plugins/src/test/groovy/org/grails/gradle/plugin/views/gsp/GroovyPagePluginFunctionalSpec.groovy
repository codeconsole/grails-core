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

import org.grails.gradle.plugin.core.GradleSpecification

/**
 * Functional tests for the classpath {@link GroovyPagePlugin} assembles for GSP compilation.
 *
 * <p>Uses Gradle TestKit to apply {@code org.apache.grails.gradle.grails-gsp} to a project and
 * assert what the {@code compileGroovyPages} and {@code compileWebappGroovyPages} tasks compile
 * against. The plugin no longer registers a {@code gspCompile} configuration: it was introduced
 * as the classpath for the original Ant-based GSP compiler, and once compilation moved to a
 * forked task its only remaining content was a hardcoded servlet API dependency, which the
 * compile classpath already supplies transitively.</p>
 *
 * @since 8.0
 */
class GroovyPagePluginFunctionalSpec extends GradleSpecification {

    def "plugin does not register a gspCompile configuration"() {
        given:
        setupTestResourceProject('gsp-compile-classpath')

        when:
        def result = executeTask('inspectGspCompileClasspath')

        then:
        result.output.contains('HAS_GSP_COMPILE_CONFIGURATION=false')
    }

    def "GSP compile tasks still resolve the compile classpath, provided dependencies and compiled classes"() {
        given:
        setupTestResourceProject('gsp-compile-classpath')

        when:
        def result = executeTask('inspectGspCompileClasspath')

        then: 'compileGroovyPages sees everything it needs to compile a GSP'
        result.output.contains('MAIN_HAS_COMPILE_CLASSPATH=true')
        result.output.contains('MAIN_HAS_PROVIDED_COMPILE=true')
        result.output.contains('MAIN_HAS_CLASSES_DIR=true')

        and: 'compileWebappGroovyPages resolves the same classpath'
        result.output.contains('WEBAPP_HAS_COMPILE_CLASSPATH=true')
        result.output.contains('WEBAPP_HAS_PROVIDED_COMPILE=true')
        result.output.contains('WEBAPP_HAS_CLASSES_DIR=true')
    }

    def "the page opt-in reaches both the build's page compiler and the JVM running the application"() {
        given:
        setupTestResourceProject('gsp-compile-static')

        when:
        def result = executeTask('inspectGspCompileStatic')

        then: 'the pages the build compiles ahead of time'
        result.output.contains('PAGE_COMPILER=true')
        result.output.contains('WEBAPP_PAGE_COMPILER=true')

        and: 'and the pages compiled again while the application runs'
        result.output.contains('RUNNING_APPLICATION=true')

        and: 'strictness travels with it, to both'
        result.output.contains('PAGE_COMPILER_STRICT=true')
        result.output.contains('RUNNING_APPLICATION_STRICT=true')
    }

    def "pages compile the way configuration says where the opt-in is not set"() {
        given:
        setupTestResourceProject('gsp-compile-classpath')

        when:
        def result = executeTask('inspectGspCompileClasspath')

        then: 'the option is read only where the grails extension exists, so this project keeps the default'
        result.output.contains('PAGE_COMPILER_STATIC=false')
        result.output.contains('WEBAPP_PAGE_COMPILER_STATIC=false')
    }

    def "compiled pages are on the test runtime class path"() {
        given: 'a project whose pages the plugin compiles'
        setupTestResourceProject('gsp-compile-classpath')

        when:
        def result = executeTask('inspectGspRuntimeClasspath')

        then: 'a test of the application loads the pages it would ship, the view registry included'
        result.output.contains('TEST_RUNTIME_HAS_PAGES=true')
        result.output.contains('TEST_RUNTIME_HAS_WEBAPP_PAGES=true')

        and: 'they are off the main runtime class path, which a boot archive would package a second time'
        result.output.contains('MAIN_RUNTIME_HAS_PAGES=false')
        result.output.contains('MAIN_RUNTIME_HAS_WEBAPP_PAGES=false')

        and: 'running the tests compiles them first, rather than using whatever an earlier run left'
        result.output.contains('TEST_WAITS_FOR_PAGE_COMPILATION=true')
        result.output.contains('TEST_WAITS_FOR_WEBAPP_PAGE_COMPILATION=true')
    }

    def "a Grails project gets no compiled pages on its test runtime class path"() {
        given: 'the same wiring in a Grails build'
        setupTestResourceProject('gsp-compile-classpath-grails')

        when:
        def result = executeTask('inspectGspRuntimeClasspath')

        then: 'a Grails application renders the views under grails-app/views, which its tests find as they are'
        result.output.contains('TEST_RUNTIME_HAS_PAGES=false')
        result.output.contains('TEST_RUNTIME_HAS_WEBAPP_PAGES=false')

        and: 'so its test task is not put behind compiling pages it does not read'
        result.output.contains('TEST_WAITS_FOR_PAGE_COMPILATION=false')
    }

    def "scaffolded pages are staged beside the application views, in a directory of their own"() {
        given:
        def runner = setupTestResourceProject('gsp-compile-classpath')
        File projectDir = runner.projectDir
        new File(projectDir, 'build.gradle').append("""
            dependencies {
                implementation localGroovy()
                runtimeOnly files('generator')
                runtimeOnly files('theme')
            }
            sourceSets.main.groovy.srcDir('grails-app/controllers')
        """)
        // The generator the task runs comes from grails-scaffolding, which this build cannot depend
        // on; the tests' stand-in records what it is handed instead.
        String generator = 'org/apache/grails/scaffolding/ScaffoldedPagesGenerator.class'
        File generatorClass = new File(projectDir, "generator/${generator}")
        generatorClass.parentFile.mkdirs()
        generatorClass.bytes = getClass().classLoader.getResource(generator).bytes
        // Only the annotation's bytecode is read by the task; no application is started.
        Map<String, String> sources = [
            'src/main/groovy/grails/plugin/scaffolding/annotation/Scaffold.groovy': """
                package grails.plugin.scaffolding.annotation
                import java.lang.annotation.Retention
                import java.lang.annotation.RetentionPolicy
                @Retention(RetentionPolicy.RUNTIME)
                @interface Scaffold { Class value() }
            """,
            'grails-app/controllers/admin/EventController.groovy': """
                package admin
                import grails.plugin.scaffolding.annotation.Scaffold
                @Scaffold(String)
                class EventController { static namespace = 'admin' }
            """,
            'grails-app/controllers/BookController.groovy': """
                import grails.plugin.scaffolding.annotation.Scaffold
                @Scaffold(Integer)
                class BookController { }
            """,
            'src/main/templates/scaffolding/show.gsp': 'show ${className}',
            'src/main/templates/scaffolding/admin/show.gsp': 'admin show ${className}',
            // a template from a dependency the application only has at runtime
            'theme/META-INF/templates/scaffolding/list.gsp': 'theme list ${className}',
            'grails-app/views/book/index.gsp': 'handwritten index'
        ]
        sources.each { String path, String content ->
            File file = new File(projectDir, path)
            file.parentFile.mkdirs()
            file.text = content.stripIndent()
        }
        File staged = new File(projectDir, 'build/generated/views')
        Closure<Map<String, String>> pagesOf = { String domain ->
            File dir = new File(staged, "grails-scaffolded/${domain}")
            Map<String, String> pages = [:]
            dir.eachFileRecurse { File f ->
                if (f.isFile()) {
                    pages[dir.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/' as char)] = f.text
                }
            }
            pages
        }

        when:
        def result = executeTask('stageGroovyPages')

        then: 'the application views are staged as they are'
        assertTaskSuccess('stageGroovyPages', result)
        new File(staged, 'book/index.gsp').text == 'handwritten index'

        and: 'no scaffolded page lands where a controller view resolves from'
        !new File(staged, 'book/show.gsp').exists()
        !new File(staged, 'event').exists()

        and: 'every template the application runs with, namespace-specific ones included, is expanded for every scaffolded domain class'
        pagesOf('java.lang.String') == ['show.gsp': 'show ${className}', 'admin/show.gsp': 'admin show ${className}',
                                        'list.gsp': 'theme list ${className}']
        pagesOf('java.lang.Integer') == ['show.gsp': 'show ${className}', 'admin/show.gsp': 'admin show ${className}',
                                         'list.gsp': 'theme list ${className}']

        when: 'a template override is edited'
        new File(projectDir, 'src/main/templates/scaffolding/show.gsp').text = 'edited show ${className}'
        def rebuild = executeTask('stageGroovyPages')

        then: 'the pages expanded from it are replaced, not added to'
        assertTaskSuccess('stageGroovyPages', rebuild)
        pagesOf('java.lang.String') == ['show.gsp': 'edited show ${className}', 'admin/show.gsp': 'admin show ${className}',
                                        'list.gsp': 'theme list ${className}']
    }
}

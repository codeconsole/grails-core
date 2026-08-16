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
package org.grails.web.taglib

import java.nio.file.Files
import java.nio.file.Path

import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * A controller can call tags too, through the tag library invoker trait rather than by being a tag
 * library, so the same rewriting has to reach it.
 *
 * <p>Checked in the class file, because a rewritten call and a dynamic one produce the same output.
 */
class ControllerTagCallRewriteSpec extends Specification {

    @TempDir
    Path tempDir

    void 'a class that can call tags has its tag calls compiled into invocations'() {
        when: 'a class carrying the tag library invoker trait, as a controller does'
        byte[] compiled = compile('''
            import grails.artefact.gsp.TagLibraryInvoker
            class TagCallingController implements TagLibraryInvoker {
                def index() {
                    g.createLink(controller: 'book')
                }
            }
        ''', 'TagCallingController')

        then:
        references(compiled, 'org/grails/taglib/CompiledTagInvocation')
    }

    void 'a class that cannot call tags is left alone'() {
        when: 'no tag library invoker trait, so g is not a namespace here'
        byte[] compiled = compile('''
            class PlainService {
                def index() {
                    g.createLink(controller: 'book')
                }
            }
        ''', 'PlainService')

        then:
        !references(compiled, 'org/grails/taglib/CompiledTagInvocation')
    }

    void 'a controller declared by convention has its tag calls compiled into invocations'() {
        when: 'under grails-app/controllers, which is how a controller is normally declared'
        byte[] compiled = compileAt('grails-app/controllers/demo', '''
            package demo

            class ConventionController {
                def index() {
                    g.createLink(controller: 'book')
                }
            }
        ''', 'ConventionController', 'demo')

        then:
        references(compiled, 'org/grails/taglib/CompiledTagInvocation')
    }

    void 'a controller declared by annotation outside that directory is not rewritten'() {
        when: 'the trait arrives from a local transform, which runs after every global one'
        byte[] compiled = compileAt('src/main/groovy/demo', '''
            package demo

            import grails.artefact.Artefact

            @Artefact('Controller')
            class AnnotatedController {
                def index() {
                    g.createLink(controller: 'book')
                }
            }
        ''', 'AnnotatedController', 'demo')

        then: 'a known limitation rather than an intent: the call is dispatched as it was before, so ' +
                'it behaves correctly, it just does not get the faster path'
        !references(compiled, 'org/grails/taglib/CompiledTagInvocation')

        and: 'and it really is a controller, so the difference is the source layout alone'
        references(compiled, 'grails/artefact/gsp/TagLibraryInvoker')
    }

    private static boolean references(byte[] classBytes, String internalName) {
        new String(classBytes, 'ISO-8859-1').contains(internalName)
    }

    private byte[] compileAt(String relativeDir, String source, String className, String packageName) {
        Path sourceDir = Files.createDirectories(tempDir.resolve(relativeDir))
        Path sourceFile = sourceDir.resolve(className + '.groovy')
        sourceFile.toFile().text = source
        Path outputDir = Files.createDirectories(tempDir.resolve('out-' + className))

        CompilerConfiguration configuration = new CompilerConfiguration()
        configuration.targetDirectory = outputDir.toFile()
        configuration.parameters = true
        CompilationUnit unit = new CompilationUnit(configuration, null,
                new GroovyClassLoader(getClass().classLoader, configuration))
        unit.addSource(sourceFile.toFile())
        unit.compile()

        Files.readAllBytes(outputDir.resolve(packageName.replace('.', '/')).resolve(className + '.class'))
    }

    private byte[] compile(String source, String className) {
        Path sourceFile = tempDir.resolve(className + '.groovy')
        sourceFile.toFile().text = source
        Path outputDir = Files.createDirectories(tempDir.resolve('classes-' + className))

        CompilerConfiguration configuration = new CompilerConfiguration()
        configuration.targetDirectory = outputDir.toFile()
        configuration.parameters = true
        CompilationUnit unit = new CompilationUnit(configuration, null,
                new GroovyClassLoader(getClass().classLoader, configuration))
        unit.addSource(sourceFile.toFile())
        unit.compile()

        Files.readAllBytes(outputDir.resolve(className + '.class'))
    }
}

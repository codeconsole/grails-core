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
import spock.lang.Unroll

/**
 * A name that happens to match a tag library namespace is only a namespace when nothing else in scope
 * has claimed it.
 *
 * <p>Rewriting a call on a local variable, a parameter or a field named {@code g} would silently send
 * it to a tag library instead of the object the author meant, which is the one failure this rewriting
 * must never produce.
 */
class TagCallShadowingSpec extends Specification {

    @TempDir
    Path tempDir

    @Unroll
    void 'a call on #description is not rewritten'() {
        when:
        byte[] compiled = compile("""
            import grails.artefact.gsp.TagLibraryInvoker
            class ${className} implements TagLibraryInvoker {
                ${member}
                def index(${parameter}) {
                    ${body}
                    g.createLink(controller: 'book')
                }
            }
        """, className)

        then: 'the author meant their own g, not the tag library namespace'
        !references(compiled, 'org/grails/taglib/CompiledTagInvocation')

        where:
        description         | className            | member            | parameter | body
        'a local variable'  | 'LocalShadow'        | ''                | ''        | 'def g = new Expando(createLink: { Map a -> "x" })'
        'a parameter'       | 'ParameterShadow'    | ''                | 'Object g'| ''
        'a field'           | 'FieldShadow'        | 'Object g'        | ''        | ''
        'a typed local'     | 'TypedLocalShadow'   | ''                | ''        | 'Object g = null'
    }

    void 'a call on the namespace itself is still rewritten'() {
        when: 'nothing in scope claims the name'
        byte[] compiled = compile('''
            import grails.artefact.gsp.TagLibraryInvoker
            class UnshadowedCaller implements TagLibraryInvoker {
                def index() {
                    g.createLink(controller: 'book')
                }
            }
        ''', 'UnshadowedCaller')

        then:
        references(compiled, 'org/grails/taglib/CompiledTagInvocation')
    }

    void 'a call in an inherited method is not rewritten through a subclass'() {
        when: 'only the subclass can call tags; the superclass method is not its code to change'
        byte[] compiled = compile('''
            import grails.artefact.gsp.TagLibraryInvoker
            class PlainBase {
                def helper() {
                    g.createLink(controller: 'book')
                }
            }
            class TagAwareSubclass extends PlainBase implements TagLibraryInvoker {
                def index() { helper() }
            }
        ''', 'PlainBase')

        then: 'the superclass class file is untouched'
        !references(compiled, 'org/grails/taglib/CompiledTagInvocation')
    }

    private static boolean references(byte[] classBytes, String internalName) {
        new String(classBytes, 'ISO-8859-1').contains(internalName)
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

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
import org.grails.taglib.index.TagLibraryIndex
import spock.lang.Specification
import spock.lang.TempDir

/**
 * That a rewritten call produces the right output says nothing about whether it was rewritten, since
 * the dynamic route produces the same output. This looks at what was actually compiled.
 */
class CompiledTagCallBytecodeSpec extends Specification {

    @TempDir
    Path tempDir

    void 'a call to a known tag is compiled as an invocation, not a dynamic call'() {
        when:
        byte[] compiled = compile('''
            import grails.gsp.TagLib
            @TagLib
            class BytecodeCheckTagLib {
                static namespace = 'bytecheck'
                def calls(Map attrs) {
                    out << g.createLink(controller: 'book')
                }
            }
        ''', 'BytecodeCheckTagLib')

        then: 'the invocation entry point is referenced'
        references(compiled, 'org/grails/taglib/CompiledTagInvocation')
    }

    void 'a call into a namespace no compiled tag library declares is left dynamic'() {
        when:
        byte[] compiled = compile('''
            import grails.gsp.TagLib
            @TagLib
            class UntouchedTagLib {
                static namespace = 'untouched'
                def calls(Map attrs) {
                    out << nosuchnamespace.whatever(a: 1)
                }
            }
        ''', 'UntouchedTagLib')

        then: 'nothing was rewritten, so it resolves as it did before'
        !references(compiled, 'org/grails/taglib/CompiledTagInvocation')
    }

    void 'a closure based tag is left dynamic even though it is known'() {
        when: 'g.link is declared as a Closure field, so it carries no signature to bind to'
        byte[] compiled = compile('''
            import grails.gsp.TagLib
            @TagLib
            class ClosureTagCallerTagLib {
                static namespace = 'closurecaller'
                def calls(Map attrs) {
                    out << g.link(controller: 'book')
                }
            }
        ''', 'ClosureTagCallerTagLib')

        then:
        !references(compiled, 'org/grails/taglib/CompiledTagInvocation')

        and: 'it is still known, so it is never reported as a misspelling'
        TagLibraryIndex.load(getClass().classLoader).lookup('g', 'link') != null
    }

    void 'the index this build compiles against is populated'() {
        expect: 'otherwise the first case would pass for the wrong reason'
        TagLibraryIndex.load(getClass().classLoader).lookup('g', 'link') != null
    }

    private static boolean references(byte[] classBytes, String internalName) {
        new String(classBytes, 'ISO-8859-1').contains(internalName)
    }

    private byte[] compile(String source, String className) {
        Path sourceFile = tempDir.resolve(className + '.groovy')
        sourceFile.toFile().text = source
        Path outputDir = Files.createDirectories(tempDir.resolve('classes'))

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

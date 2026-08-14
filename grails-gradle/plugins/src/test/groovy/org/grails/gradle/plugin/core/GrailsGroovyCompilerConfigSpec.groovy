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

/**
 * Covers the lifecycle guarantees of the Grails Groovy compiler configuration script wiring.
 *
 * <p>The combined script is produced by a generator task rather than by a {@code doFirst} on the
 * compile task, because Gradle finalizes task properties before task actions run. These tests pin
 * the three ordering properties that arrangement has to preserve.</p>
 *
 * @see GrailsGradlePlugin#getGroovyCompilerScript
 */
class GrailsGroovyCompilerConfigSpec extends GradleSpecification {

    def "a GroovyCompile registered after the project is evaluated still gets a generator"() {
        given: 'a source set registered from projectsEvaluated, after every afterEvaluate callback'
        setupTestResourceProject('compiler-config-late-source-set')

        when:
        def result = executeTask('inspectLate')

        then: 'the late compile task is wired, not only the ones present during evaluation'
        result.output.contains('LATE_GENERATOR_EXISTS=true')
        result.output.contains('MAIN_GENERATOR_EXISTS=true')
    }

    def "the generator is decoupled from the compile and runtime classpaths"() {
        given: 'an application with both an implementation and a runtimeOnly project dependency'
        setupTestResourceProject('compiler-config-classpath-decoupling')

        when:
        def result = executeTask(':app:inspectDecoupling')

        then: 'the script is built from configuration state, so no classpath enters the chain'
        result.output.contains('GENERATOR_DEPENDS_ON_COMPILE_CLASSPATH=false')
        result.output.contains('GENERATOR_DEPENDS_ON_RUNTIME_CLASSPATH=false')
        result.output.contains('GENERATOR_DECLARED_INPUT_FILES=0')

        and: 'its content is a declared input, so it is up-to-date checked rather than untracked'
        result.output.contains('GENERATOR_HAS_CONTENT_INPUT=true')
    }

    def "a configurationScript assigned from a later callback is folded in, not clobbered"() {
        given: 'a user assigning configurationScript from projectsEvaluated'
        setupTestResourceProject('compiler-config-late-assignment')

        when:
        def result = executeTask('inspectAssign')

        then: 'the combined script wins and carries the user script content'
        result.output.contains('FINAL_CONFIG_SCRIPT=grailsGroovyCompilerConfig-compileGroovy.groovy')
        result.output.contains('COMBINED_EXISTS=true')
        result.output.contains('COMBINED_CONTAINS_USER_IMPORT=true')
    }

    def "a plugin project declares the version and name it bakes into compiled classes as inputs"() {
        given: 'a Grails plugin project, whose script stamps projectVersion/projectName AST metadata'
        setupTestResourceProject('compiler-config-plugin-inputs')

        when:
        def result = executeTask('inspectPluginInputs')

        then: 'both are inputs of the compile task, so changing either recompiles'
        result.output.contains('HAS_VERSION_INPUT=true')
        result.output.contains('HAS_NAME_INPUT=true')
    }

    def "the generator waits for a task that produces the build's own config script"() {
        given: 'a build whose configurationScript is the output of another task'
        setupTestResourceProject('compiler-config-generated-user-script')

        when: 'the compile task graph is built'
        def result = executeTask('compileGroovy', ['--dry-run'])
        def order = result.output.readLines().findAll { it.startsWith(':') }
        int producer = order.findIndexOf { it.startsWith(':generateUserConfigScript') }
        int generator = order.findIndexOf { it.startsWith(':generateCompileGroovyGrailsCompilerConfig') }

        then: 'the producer is scheduled first, so its content is present when the script is combined'
        producer >= 0
        generator >= 0
        producer < generator
    }
}

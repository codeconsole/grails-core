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

    def "the generator runs after the tasks that produce the compile classpath"() {
        given: 'an application whose compile classpath comes from a sibling project'
        setupTestResourceProject('compiler-config-classpath-ordering')

        when:
        def result = executeTask(':app:inspectOrder')

        then: 'the generator depends on the producing task, so the classpath probes see the artifact'
        result.output.contains('GENERATOR_DEPENDS_ON_PRODUCER_JAR=true')

        and: 'it stays scoped to the compile classpath and declares no inputs of its own, so the'
        'runtime classpath is neither pulled into the dependency chain nor into an up-to-date check'
        result.output.contains('GENERATOR_DEPENDS_ON_RUNTIME_ONLY_JAR=false')
        result.output.contains('GENERATOR_DECLARED_INPUT_FILES=0')
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
}

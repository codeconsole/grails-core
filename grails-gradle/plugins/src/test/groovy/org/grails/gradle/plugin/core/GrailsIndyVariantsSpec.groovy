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

class GrailsIndyVariantsSpec extends GradleSpecification {

    def "a plugin compiles its classes both with and without invokedynamic"() {
        given:
        setupTestResourceProject('indy-variants')

        when:
        def result = executeTask(':plugin:inspectIndyVariants')

        then: 'the main compilation disables indy and the second one enables it'
        result.output.contains('MAIN_INDY=false')
        result.output.contains('INDY_INDY=true')

        and: 'the second compilation is packaged under the indy classifier'
        result.output.contains('INDY_JAR=plugin-1.0.0-indy.jar')

        and: 'only the call-site bytecode differs between the two jars'
        result.output.contains('MAIN_BYTECODE=indy=false,callsite=true')
        result.output.contains('INDY_BYTECODE=indy=true,callsite=false')
    }

    def "the indy artifact is a plain classifier and adds no variant"() {
        given:
        setupTestResourceProject('indy-variants')

        when:
        def result = executeTask(':plugin:inspectIndyVariants')

        then: 'the published variants are exactly the ones the module always had'
        result.output.contains('API_HAS_INDY=false')
        result.output.contains('RUNTIME_HAS_INDY=false')

        and: 'the module advertises the classifier so applications can discover it'
        result.output.contains('ADVERTISED=org.example.test:plugin')
    }

    def "an application resolves the artifacts matching how it compiles its own code"() {
        given:
        setupTestResourceProject('indy-variants')

        when:
        def result = executeTask(':app:inspectResolved', ["-PappIndy=${indy}".toString()])

        then:
        result.output.contains("RESOLVED=${resolved}")

        where:
        indy    || resolved
        'true'  || 'legacy-1.0.0.jar,plugin-1.0.0-indy.jar'
        'false' || 'legacy-1.0.0.jar,plugin-1.0.0.jar'
    }

    def "a dependency that publishes only a main artifact stays resolvable either way"() {
        given: 'the application also depends on a library with no noindy variant'
        setupTestResourceProject('indy-variants')

        when:
        def result = executeTask(':app:inspectResolved', ["-PappIndy=${indy}".toString()])

        then: 'that library resolves to its single artifact rather than failing to match'
        result.output.contains('legacy-1.0.0.jar')

        where:
        indy << ['false', 'true']
    }

    def "an application that configures nothing gets invokedynamic disabled"() {
        given:
        setupTestResourceProject('indy-variants')

        when:
        def result = executeTask(':defaultapp:inspectDefault')

        then: 'invokedynamic is off'
        result.output.contains('DEFAULT_INDY=false')

        and: 'and the main artifacts are the ones resolved'
        result.output.contains('DEFAULT_RESOLVED=plugin-1.0.0.jar')
    }

    def "an artifact view that constrains nothing resolves a single artifact"() {
        given: 'a consumer resolving the way the CLI companion probe does'
        setupTestResourceProject('indy-variants')

        when:
        def result = executeTask(':plain:inspectArtifactView')

        then:
        result.output.contains('VIEW=plugin-1.0.0.jar')
    }

    def "a configuration that declares no attributes resolves a single artifact"() {
        given: 'the shape used by ad-hoc configurations such as tck and the CLI probe'
        setupTestResourceProject('indy-variants')

        when:
        def result = executeTask(':plain:inspectProbe')

        then: 'publishing no second variant leaves nothing to be ambiguous about'
        result.output.contains('PROBE=plugin-1.0.0.jar')
    }

    def "a plain Gradle consumer still resolves the main artifact"() {
        given:
        setupTestResourceProject('indy-variants')

        when: 'a consumer that never requests the attribute resolves the plugin'
        def result = executeTask(':plain:inspectResolved')

        then: 'it sees a single candidate rather than an ambiguity failure'
        result.output.contains('RESOLVED=plugin-1.0.0.jar')
    }
}

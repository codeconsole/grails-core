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

        then: 'the default compilation is left on Groovy\'s own default and the second one disables indy'
        result.output.contains('MAIN_INDY=null')
        result.output.contains('NOINDY_INDY=false')

        and: 'the second compilation is packaged under the noindy classifier'
        result.output.contains('NOINDY_JAR=plugin-1.0.0-noindy.jar')

        and: 'only the call-site bytecode differs between the two jars'
        result.output.contains('MAIN_BYTECODE=indy=true,callsite=false')
        result.output.contains('NOINDY_BYTECODE=indy=false,callsite=true')
    }

    def "the noindy artifact is published as a secondary variant of both element configurations"() {
        given:
        setupTestResourceProject('indy-variants')

        when:
        def result = executeTask(':plugin:inspectIndyVariants')

        then:
        result.output.contains('API_HAS_NOINDY=true')
        result.output.contains('RUNTIME_HAS_NOINDY=true')
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
        'false' || 'legacy-1.0.0.jar,plugin-1.0.0-noindy.jar'
        'true'  || 'legacy-1.0.0.jar,plugin-1.0.0.jar'
    }

    def "a dependency that publishes only the default artifact stays resolvable either way"() {
        given: 'the application also depends on a library with no noindy variant'
        setupTestResourceProject('indy-variants')

        when:
        def result = executeTask(':app:inspectResolved', ["-PappIndy=${indy}".toString()])

        then: 'that library resolves to its single artifact rather than failing to match'
        result.output.contains('legacy-1.0.0.jar')

        where:
        indy << ['false', 'true']
    }

    def "an application that configures nothing follows Groovy's own default"() {
        given:
        setupTestResourceProject('indy-variants')

        when:
        def result = executeTask(':defaultapp:inspectDefault')

        then: 'the compiler option is left unset, so Groovy decides'
        result.output.contains('DEFAULT_INDY=true')

        and: 'and the default artifacts are the ones resolved'
        result.output.contains('DEFAULT_RESOLVED=plugin-1.0.0.jar')
    }

    def "a plain Gradle consumer still resolves the default artifact"() {
        given:
        setupTestResourceProject('indy-variants')

        when: 'a consumer that never requests the attribute resolves the plugin'
        def result = executeTask(':plain:inspectResolved')

        then: 'it sees a single candidate rather than an ambiguity failure'
        result.output.contains('RESOLVED=plugin-1.0.0.jar')
    }
}

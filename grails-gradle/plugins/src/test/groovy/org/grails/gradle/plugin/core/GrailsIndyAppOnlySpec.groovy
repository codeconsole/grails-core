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

class GrailsIndyAppOnlySpec extends GradleSpecification {

    def "a plugin keeps invokedynamic even when its build asks for callsite caching"() {
        given: 'a plugin whose build sets indy = false'
        setupTestResourceProject('indy-app-only')

        when:
        def result = executeTask(':plugin:inspectIndy')

        then: 'the setting does not reach the compiler, so Groovy applies its own default'
        result.output.contains('PLUGIN_INDY=null')

        and: 'the published classes are the invokedynamic flavour a native image needs'
        result.output.contains('PLUGIN_BYTECODE=indy=true,callsite=false')
    }

    def "a plugin publishes a single artifact"() {
        given:
        setupTestResourceProject('indy-app-only')

        when:
        def result = executeTask(':plugin:inspectIndy')

        then: 'there is no second flavour to build or publish'
        result.output.contains('PLUGIN_NOINDY_TASKS=')
        !result.output.contains('noindyJar')
    }

    def "an application may still choose callsite caching for its own sources"() {
        given:
        setupTestResourceProject('indy-app-only')

        when:
        def result = executeTask(':app:inspectIndy')

        then:
        result.output.contains('APP_INDY=false')

        and: 'the plugin it depends on is unaffected by that choice'
        result.output.contains('APP_RESOLVED=plugin-1.0.0.jar')
    }

    def "an application that configures nothing follows Groovy's own default"() {
        given:
        setupTestResourceProject('indy-app-only')

        when:
        def result = executeTask(':defaultapp:inspectIndy')

        then:
        result.output.contains('DEFAULT_INDY=true')
        result.output.contains('DEFAULT_RESOLVED=plugin-1.0.0.jar')
    }
}

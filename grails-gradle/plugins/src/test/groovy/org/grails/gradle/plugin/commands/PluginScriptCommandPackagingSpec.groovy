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
package org.grails.gradle.plugin.commands

import org.grails.gradle.plugin.core.GradleSpecification

/**
 * Verifies that {@code src/main/scripts} command resources are packaged according to whether the
 * plugin publishes a companion {@code -cli} artifact (issue #16035).
 *
 * <p>With {@code grails-plugin-cli}, Groovy/YAML command scripts must leave the runtime plugin jar
 * and land only in the companion. Without a companion, the historical runtime-jar packaging is
 * preserved so unmigrated Grails 7 plugins keep working under {@code legacyCommandSupport}.</p>
 */
class PluginScriptCommandPackagingSpec extends GradleSpecification {

    def "companion plugins package src/main/scripts only in the -cli jar"() {
        given:
        setupTestResourceProject('plugin-script-commands')

        when:
        def result = executeTask(':plugin-with-cli:inspectCommandPackaging')

        then:
        result.output.contains('RUNTIME_HAS_SCRIPT=false')
        result.output.contains('RUNTIME_HAS_YAML=false')
        result.output.contains('RUNTIME_HAS_REMOVED=false')
        result.output.contains('RUNTIME_HAS_HAND_AUTHORED=true')
        result.output.contains('CLI_HAS_SCRIPT=true')
        result.output.contains('CLI_HAS_YAML=true')

        and: 'templates remain a runtime concern'
        result.output.contains('RUNTIME_HAS_TEMPLATE=true')
    }

    def "plugins without a companion keep src/main/scripts in the runtime jar"() {
        given:
        setupTestResourceProject('plugin-script-commands')

        when:
        def result = executeTask(':plugin-without-cli:inspectCommandPackaging')

        then:
        result.output.contains('RUNTIME_HAS_SCRIPT=true')
        result.output.contains('RUNTIME_HAS_TEMPLATE=true')
    }
}

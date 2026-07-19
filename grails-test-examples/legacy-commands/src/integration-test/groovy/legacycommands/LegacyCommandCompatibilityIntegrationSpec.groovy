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
package legacycommands

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ConfigurableApplicationContext

import grails.testing.mixin.integration.Integration
import org.apache.grails.core.cli.ApplicationCommand
import org.apache.grails.core.cli.ApplicationContextCommandRegistry
import org.apache.grails.core.cli.ExecutionContext
import org.apache.grails.core.cli.LegacyApplicationCommandAware
import org.grails.build.parsing.CommandLine
import spock.lang.Specification

@Integration
class LegacyCommandCompatibilityIntegrationSpec extends Specification {

    @Autowired
    ConfigurableApplicationContext applicationContext

    def "discovers adapts and executes legacy application commands from a Grails 7 plugin"() {
        given: 'the registry created from the application classpath'
        ApplicationContextCommandRegistry registry = ApplicationContextCommandRegistry.instance
        ApplicationCommand applicationCommand = registry.findCommand('hello-legacy-app')
        ApplicationCommand grailsApplicationCommand = registry.findCommand('hello-legacy-grails')
        ExecutionContext executionContext = new ExecutionContext(Mock(CommandLine))
        File applicationMarkerFile = new File(executionContext.baseDir, 'hello-legacy-app.txt')
        File grailsApplicationMarkerFile = new File(executionContext.baseDir, 'hello-legacy-grails.txt')
        applicationMarkerFile.delete()
        grailsApplicationMarkerFile.delete()

        expect: 'both legacy factory registrations are discovered and adapted'
        applicationCommand != null
        applicationCommand instanceof LegacyApplicationCommandAware
        grailsApplicationCommand != null
        grailsApplicationCommand instanceof LegacyApplicationCommandAware

        when: 'the legacy ApplicationCommand runs through its Grails 8 adapter'
        applicationCommand.applicationContext = applicationContext
        boolean applicationCommandResult = applicationCommand.handle(executionContext)

        then: 'the command succeeds and preserves its Grails 7 side effect'
        applicationCommandResult
        applicationMarkerFile.text == 'RAN'

        when: 'the legacy GrailsApplicationCommand runs through its Grails 8 adapter'
        grailsApplicationCommand.applicationContext = applicationContext
        boolean grailsApplicationCommandResult = grailsApplicationCommand.handle(executionContext)

        then: 'the subtype initializes its legacy execution context and succeeds'
        grailsApplicationCommandResult
        grailsApplicationMarkerFile.text == 'RAN'

        cleanup:
        applicationMarkerFile.delete()
        grailsApplicationMarkerFile.delete()
    }

    def "packages a hyphenated legacy plugin command script at its original command path"() {
        expect: 'the script is available for GroovyScriptCommandFactory discovery'
        // LegacyPluginScriptCompatSpec verifies factory discovery and hyphen-preserving name resolution.
        getClass().classLoader.getResource('META-INF/commands/hello-legacy-script.groovy') != null
    }

}

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
package org.apache.grails.core.cli

import grails.dev.commands.ExecutionContext as LegacyExecutionContext
import org.apache.grails.core.cli.compat.LegacyApplicationCommandAdapter
import org.grails.build.parsing.CommandLine
import org.springframework.context.ConfigurableApplicationContext
import spock.lang.Specification
import spock.lang.TempDir

class LegacyCommandRegistryLoadingSpec extends Specification {

    @TempDir
    File tempDir

    private ClassLoader originalContextClassLoader
    private URLClassLoader factoryClassLoader

    def cleanup() {
        Thread.currentThread().contextClassLoader = originalContextClassLoader
        factoryClassLoader?.close()
    }

    def "discovers adapts and executes a command registered under the legacy factory key"() {
        given:
        useFactoryResources('grails.dev.commands.ApplicationCommand=org.apache.grails.core.cli.LegacyRegistryTestCommand')
        ConfigurableApplicationContext applicationContext = Mock()
        CommandLine commandLine = Mock()

        when:
        ApplicationCommand command = new ApplicationContextCommandRegistry().findCommand('legacy-registry-command')

        then:
        command instanceof LegacyApplicationCommandAdapter
        command instanceof LegacyApplicationCommandAware
        LegacyApplicationCommandAdapter adapter = command as LegacyApplicationCommandAdapter
        LegacyRegistryTestCommand legacyCommand = ((LegacyApplicationCommandAware) command).legacyCommand as LegacyRegistryTestCommand
        adapter.legacyCommand.is(legacyCommand)

        when:
        adapter.applicationContext = applicationContext
        boolean result = adapter.handle(new ExecutionContext(commandLine))

        then:
        result
        legacyCommand.invoked
        legacyCommand.applicationContext.is(applicationContext)
        legacyCommand.executionContext instanceof LegacyExecutionContext
        legacyCommand.executionContext.commandLine.is(commandLine)
    }

    def "discovers adapts and executes a legacy Grails application command"() {
        given:
        useFactoryResources('grails.dev.commands.ApplicationCommand=org.apache.grails.core.cli.LegacyRegistryGrailsApplicationTestCommand')
        CommandLine commandLine = Mock()

        when:
        ApplicationCommand command = new ApplicationContextCommandRegistry().findCommand('legacy-registry-grails-application-command')

        then:
        command instanceof LegacyApplicationCommandAdapter
        command instanceof LegacyApplicationCommandAware
        LegacyApplicationCommandAdapter adapter = command as LegacyApplicationCommandAdapter
        LegacyRegistryGrailsApplicationTestCommand legacyCommand = ((LegacyApplicationCommandAware) command).legacyCommand as LegacyRegistryGrailsApplicationTestCommand
        adapter.legacyCommand.is(legacyCommand)

        when:
        boolean result = adapter.handle(new ExecutionContext(commandLine))

        then:
        result
        legacyCommand.handleCalls == 1
        legacyCommand.executionContext instanceof LegacyExecutionContext
        legacyCommand.executionContext.commandLine.is(commandLine)
        legacyCommand.templateRenderer != null
        legacyCommand.fileSystemInteraction != null
    }

    def "prefers a new-contract command when legacy and new registrations share a name"() {
        given:
        useFactoryResources(
                'grails.dev.commands.ApplicationCommand=org.apache.grails.core.cli.LegacyRegistryCollisionCommand',
                'org.apache.grails.core.cli.ApplicationCommand=org.apache.grails.core.cli.ConfigReportCommand')

        when:
        ApplicationCommand command = new ApplicationContextCommandRegistry().findCommand('config-report')

        then:
        command instanceof ConfigReportCommand
        !(command instanceof LegacyApplicationCommandAdapter)
    }

    def "continues loading commands when a legacy command constructor fails"() {
        given:
        useFactoryResources(
                'grails.dev.commands.ApplicationCommand=org.apache.grails.core.cli.ThrowingLegacyCommand,org.apache.grails.core.cli.LegacyRegistryTestCommand',
                'org.apache.grails.core.cli.ApplicationCommand=org.apache.grails.core.cli.ConfigReportCommand')

        when:
        ApplicationContextCommandRegistry registry = new ApplicationContextCommandRegistry()

        then:
        registry.findCommand('throwing-legacy-command') == null

        and:
        ApplicationCommand legacyCommand = registry.findCommand('legacy-registry-command')
        legacyCommand instanceof LegacyApplicationCommandAware

        and:
        registry.findCommand('config-report') instanceof ConfigReportCommand
    }

    private void useFactoryResources(String legacyFactories, String cliFactories = null) {
        File metaInf = new File(tempDir, 'META-INF')
        assert metaInf.mkdirs()
        new File(metaInf, 'grails.factories').text = legacyFactories
        if (cliFactories != null) {
            new File(metaInf, 'grails-cli.factories').text = cliFactories
        }
        originalContextClassLoader = Thread.currentThread().contextClassLoader
        factoryClassLoader = new URLClassLoader([tempDir.toURI().toURL()] as URL[], getClass().classLoader)
        Thread.currentThread().contextClassLoader = factoryClassLoader
    }
}

class LegacyRegistryTestCommand implements grails.dev.commands.ApplicationCommand {

    boolean invoked
    LegacyExecutionContext executionContext

    @Override
    String getName() {
        'legacy-registry-command'
    }

    @Override
    String getDescription() {
        'Legacy registry test command'
    }

    @Override
    boolean handle(LegacyExecutionContext executionContext) {
        invoked = true
        this.executionContext = executionContext
        true
    }
}

class LegacyRegistryGrailsApplicationTestCommand implements grails.dev.commands.GrailsApplicationCommand {

    int handleCalls

    @Override
    String getName() {
        'legacy-registry-grails-application-command'
    }

    @Override
    String getDescription() {
        'Legacy registry Grails application test command'
    }

    @Override
    boolean handle() {
        handleCalls++
        templateRenderer != null && fileSystemInteraction != null
    }
}

class LegacyRegistryCollisionCommand implements grails.dev.commands.ApplicationCommand {

    @Override
    String getName() {
        'config-report'
    }

    @Override
    String getDescription() {
        'Legacy registry collision command'
    }

    @Override
    boolean handle(LegacyExecutionContext executionContext) {
        true
    }
}

class ThrowingLegacyCommand implements grails.dev.commands.ApplicationCommand {

    ThrowingLegacyCommand() {
        throw new RuntimeException('boom')
    }

    @Override
    String getName() {
        'throwing-legacy-command'
    }

    @Override
    String getDescription() {
        'Throwing legacy command'
    }

    @Override
    boolean handle(LegacyExecutionContext executionContext) {
        true
    }
}

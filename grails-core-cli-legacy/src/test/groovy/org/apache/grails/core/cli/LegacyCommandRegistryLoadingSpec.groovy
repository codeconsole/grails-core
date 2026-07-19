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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import grails.dev.commands.ExecutionContext as LegacyExecutionContext
import org.apache.grails.core.cli.compat.LegacyApplicationCommandAdapter
import org.apache.grails.core.cli.compat.LegacyApplicationCommandProvider
import org.grails.build.parsing.CommandLine
import org.slf4j.LoggerFactory
import org.springframework.context.ConfigurableApplicationContext
import spock.lang.Specification
import spock.lang.TempDir

class LegacyCommandRegistryLoadingSpec extends Specification {

    @TempDir
    File tempDir

    private ClassLoader originalContextClassLoader
    private URLClassLoader factoryClassLoader
    private Logger providerLogger
    private ListAppender<ILoggingEvent> providerAppender

    def cleanup() {
        Thread.currentThread().contextClassLoader = originalContextClassLoader
        factoryClassLoader?.close()
        providerLogger?.detachAppender(providerAppender)
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
        command instanceof ApplicationCommandTargetAware
        LegacyApplicationCommandAdapter adapter = command as LegacyApplicationCommandAdapter
        LegacyRegistryTestCommand legacyCommand = ((ApplicationCommandTargetAware) command).target as LegacyRegistryTestCommand
        adapter.target.is(legacyCommand)

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
        command instanceof ApplicationCommandTargetAware
        LegacyApplicationCommandAdapter adapter = command as LegacyApplicationCommandAdapter
        LegacyRegistryGrailsApplicationTestCommand legacyCommand = ((ApplicationCommandTargetAware) command).target as LegacyRegistryGrailsApplicationTestCommand
        adapter.target.is(legacyCommand)

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
        legacyCommand instanceof ApplicationCommandTargetAware

        and:
        registry.findCommand('config-report') instanceof ConfigReportCommand
    }

    def "does not warn when every legacy command collides with a modern command"() {
        given:
        attachProviderAppender()
        useFactoryResources(
                'grails.dev.commands.ApplicationCommand=org.apache.grails.core.cli.LegacyRegistryCollisionCommand',
                'org.apache.grails.core.cli.ApplicationCommand=org.apache.grails.core.cli.ConfigReportCommand')

        when:
        new ApplicationContextCommandRegistry()

        then:
        deprecationWarnings.empty
    }

    def "warns once when multiple legacy commands are installed"() {
        given:
        attachProviderAppender()
        useFactoryResources('grails.dev.commands.ApplicationCommand=org.apache.grails.core.cli.LegacyRegistryTestCommand,org.apache.grails.core.cli.LegacyRegistryGrailsApplicationTestCommand')

        when:
        ApplicationContextCommandRegistry registry = new ApplicationContextCommandRegistry()

        then:
        registry.findCommand('legacy-registry-command') != null
        registry.findCommand('legacy-registry-grails-application-command') != null
        deprecationWarnings.size() == 1
    }

    def "resolves a legacy command name once while installing and warning"() {
        given:
        attachProviderAppender()
        useFactoryResources('grails.dev.commands.ApplicationCommand=org.apache.grails.core.cli.SingleAccessNameLegacyCommand')

        when:
        ApplicationCommand command = new ApplicationContextCommandRegistry().findCommand('single-access-name')

        then:
        command instanceof ApplicationCommandTargetAware
        (((ApplicationCommandTargetAware) command).target as SingleAccessNameLegacyCommand).nameCalls == 1
        deprecationWarnings.size() == 1
        deprecationWarnings.first().formattedMessage.contains("Command 'single-access-name'")
    }

    def "logs linkage failures at error and continues loading legacy commands"() {
        given:
        attachProviderAppender()
        useFactoryResources('grails.dev.commands.ApplicationCommand=org.apache.grails.core.cli.LinkageErrorLegacyCommand,org.apache.grails.core.cli.LegacyRegistryTestCommand')

        when:
        ApplicationContextCommandRegistry registry = new ApplicationContextCommandRegistry()

        then:
        registry.findCommand('legacy-registry-command') != null
        List<ILoggingEvent> errors = providerAppender.list.findAll { ILoggingEvent event -> event.level == Level.ERROR }
        errors.size() == 1
        errors.first().formattedMessage.contains(LinkageErrorLegacyCommand.name)
        errors.first().formattedMessage.contains('does not link against the restored grails.dev.commands contract')
    }

    private void attachProviderAppender() {
        providerLogger = LoggerFactory.getLogger(LegacyApplicationCommandProvider) as Logger
        providerAppender = new ListAppender<>()
        providerAppender.start()
        providerLogger.addAppender(providerAppender)
    }

    private List<ILoggingEvent> getDeprecationWarnings() {
        providerAppender.list.findAll { ILoggingEvent event ->
            event.formattedMessage.contains('deprecated grails.dev.commands compatibility layer')
        }
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

class SingleAccessNameLegacyCommand implements grails.dev.commands.ApplicationCommand {

    int nameCalls

    @Override
    String getName() {
        if (++nameCalls > 1) {
            throw new IllegalStateException('name accessed more than once')
        }
        'single-access-name'
    }

    @Override
    String getDescription() {
        'Single access name legacy command'
    }

    @Override
    boolean handle(LegacyExecutionContext executionContext) {
        true
    }
}

class LinkageErrorLegacyCommand implements grails.dev.commands.ApplicationCommand {

    LinkageErrorLegacyCommand() {
        throw new NoClassDefFoundError('simulated missing command dependency')
    }

    @Override
    String getName() {
        'linkage-error-legacy-command'
    }

    @Override
    String getDescription() {
        'Linkage error legacy command'
    }

    @Override
    boolean handle(LegacyExecutionContext executionContext) {
        true
    }
}

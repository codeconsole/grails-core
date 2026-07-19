/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.grails.core.cli

import groovy.transform.CompileStatic

import org.apache.grails.core.cli.compat.LegacyApplicationCommandAdapter
import org.grails.core.io.support.GrailsFactoriesLoader
import org.grails.io.support.FactoriesLoaderSupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * A registry of {@link org.apache.grails.core.cli.ApplicationCommand} instances
 *
 * @since 3.0
 */
@CompileStatic
@Singleton(strict = false)
class ApplicationContextCommandRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationContextCommandRegistry)

    private final Map<String, ApplicationCommand> commands = [:]
    private boolean legacyCommandWarningLogged

    ApplicationContextCommandRegistry() {
        for (ApplicationCommand cmd : GrailsFactoriesLoader.loadFactories(ApplicationCommand,
                ApplicationContextCommandRegistry.classLoader, GrailsFactoriesLoader.CLI_FACTORIES_RESOURCE_LOCATION)) {
            if (!commands.containsKey(cmd.name)) {
                commands[cmd.name] = cmd
            }
        }

        // If this is reflectively loaded from the delegating cli, we need to make sure the context class loader is also used to pull any commands that are loaded from the gradle classpath
        for (ApplicationCommand cmd : GrailsFactoriesLoader.loadFactories(ApplicationCommand,
                Thread.currentThread().contextClassLoader, GrailsFactoriesLoader.CLI_FACTORIES_RESOURCE_LOCATION)) {
            if (!commands.containsKey(cmd.name)) {
                commands[cmd.name] = cmd
            }
        }

        loadLegacyCommands(ApplicationContextCommandRegistry.classLoader)
        loadLegacyCommands(Thread.currentThread().contextClassLoader)
    }

    @SuppressWarnings('deprecation')
    private void loadLegacyCommands(ClassLoader classLoader) {
        // Instantiate each legacy command in isolation: a single stale Grails 7 command whose
        // no-arg constructor (or getName()) throws under Grails 8 must be skipped with a warning,
        // never abort the whole registry and take valid legacy and new-contract commands down with it.
        List<Class<grails.dev.commands.ApplicationCommand>> legacyClasses = GrailsFactoriesLoader.loadFactoryClasses(
                grails.dev.commands.ApplicationCommand, classLoader, FactoriesLoaderSupport.FACTORIES_RESOURCE_LOCATION)
        for (Class<grails.dev.commands.ApplicationCommand> legacyClass : legacyClasses) {
            try {
                grails.dev.commands.ApplicationCommand legacyCommand = legacyClass.getDeclaredConstructor().newInstance()
                ApplicationCommand command = new LegacyApplicationCommandAdapter(legacyCommand)
                String name = command.name
                if (commands.containsKey(name)) {
                    continue
                }
                commands[name] = command
                if (!legacyCommandWarningLogged) {
                    LOG.warn("Command '{}' from a Grails 7 plugin was loaded through the deprecated grails.dev.commands compatibility layer. Ask the plugin author to migrate to the org.apache.grails.core.cli command API and publish a -cli companion artifact; this compatibility path will be removed in a future major release.", name)
                    legacyCommandWarningLogged = true
                }
            }
            catch (Throwable e) {
                LOG.warn("Failed to load a Grails 7 legacy command from class '{}' through the deprecated grails.dev.commands compatibility layer; skipping it. Cause: {}",
                        legacyClass?.name, e.message)
            }
        }
    }

    Collection<ApplicationCommand> findCommands() {
        commands.values()
    }

    ApplicationCommand findCommand(String name) {
        commands[name]
    }
}

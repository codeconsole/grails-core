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
import groovy.util.logging.Slf4j

import org.grails.core.io.support.GrailsFactoriesLoader

/**
 * A registry of {@link org.apache.grails.core.cli.ApplicationCommand} instances
 *
 * @since 3.0
 */
@Slf4j
@CompileStatic
@Singleton(strict = false)
class ApplicationContextCommandRegistry {

    private final Map<String, ApplicationCommand> commands = [:]
    ApplicationContextCommandRegistry() {
        ClassLoader registryClassLoader = ApplicationContextCommandRegistry.classLoader
        ClassLoader contextClassLoader = Thread.currentThread().contextClassLoader

        for (ApplicationCommand cmd : GrailsFactoriesLoader.loadFactories(ApplicationCommand,
                registryClassLoader, GrailsFactoriesLoader.CLI_FACTORIES_RESOURCE_LOCATION)) {
            if (!commands.containsKey(cmd.name)) {
                commands[cmd.name] = cmd
            }
        }

        // If this is reflectively loaded from the delegating cli, we need to make sure the context class loader is
        // also used to pull any commands that are loaded from the gradle classpath. Only when it is a distinct
        // classloader: repeating the scan for the same classloader would re-instantiate every command (whose
        // constructor may have side effects) just to discard it on the name-collision check below.
        if (contextClassLoader != null && contextClassLoader != registryClassLoader) {
            for (ApplicationCommand cmd : GrailsFactoriesLoader.loadFactories(ApplicationCommand,
                    contextClassLoader, GrailsFactoriesLoader.CLI_FACTORIES_RESOURCE_LOCATION)) {
                if (!commands.containsKey(cmd.name)) {
                    commands[cmd.name] = cmd
                }
            }
        }

        loadCommandProviders(registryClassLoader, contextClassLoader)
    }

    private void loadCommandProviders(ClassLoader registryClassLoader, ClassLoader contextClassLoader) {
        Set<Class<? extends ApplicationCommandProvider>> providerClasses = new LinkedHashSet<>()
        providerClasses.addAll(GrailsFactoriesLoader.loadFactoryClasses(
                ApplicationCommandProvider, registryClassLoader, GrailsFactoriesLoader.CLI_FACTORIES_RESOURCE_LOCATION))
        if (contextClassLoader != null && contextClassLoader != registryClassLoader) {
            providerClasses.addAll(GrailsFactoriesLoader.loadFactoryClasses(
                    ApplicationCommandProvider, contextClassLoader, GrailsFactoriesLoader.CLI_FACTORIES_RESOURCE_LOCATION))
        }

        ApplicationCommandRegistrar registrar = new ApplicationCommandRegistrar() {
            @Override
            String register(ApplicationCommand command) {
                String name = command.name
                if (commands.containsKey(name)) {
                    return null
                }
                commands[name] = command
                name
            }
        }

        for (Class<? extends ApplicationCommandProvider> providerClass : providerClasses) {
            try {
                ApplicationCommandProvider provider = providerClass.getDeclaredConstructor().newInstance()
                provider.contributeCommands(registryClassLoader, contextClassLoader, registrar)
            }
            catch (Throwable e) {
                log.warn('Failed to load application commands from provider \'{}\'; skipping it.', providerClass.name, e)
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

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
package org.apache.grails.core.cli.compat

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import grails.dev.commands.ApplicationCommand as LegacyApplicationCommand
import org.apache.grails.core.cli.ApplicationCommand
import org.apache.grails.core.cli.ApplicationCommandProvider
import org.apache.grails.core.cli.ApplicationCommandRegistrar
import org.grails.core.io.support.GrailsFactoriesLoader
import org.grails.io.support.FactoriesLoaderSupport

/**
 * Loads commands implemented against the deprecated Grails 7 command contract.
 */
@Slf4j
@CompileStatic
class LegacyApplicationCommandProvider implements ApplicationCommandProvider {

    private boolean warningLogged

    @Override
    @SuppressWarnings('deprecation')
    void contributeCommands(
        ClassLoader registryClassLoader,
        ClassLoader contextClassLoader,
        ApplicationCommandRegistrar registrar) {
        Set<Class<? extends LegacyApplicationCommand>> legacyClasses = new LinkedHashSet<>()
        legacyClasses.addAll(GrailsFactoriesLoader.loadFactoryClasses(
                LegacyApplicationCommand, registryClassLoader, FactoriesLoaderSupport.FACTORIES_RESOURCE_LOCATION))
        if (contextClassLoader != null && contextClassLoader != registryClassLoader) {
            legacyClasses.addAll(GrailsFactoriesLoader.loadFactoryClasses(
                    LegacyApplicationCommand, contextClassLoader, FactoriesLoaderSupport.FACTORIES_RESOURCE_LOCATION))
        }

        for (Class<? extends LegacyApplicationCommand> legacyClass : legacyClasses) {
            try {
                LegacyApplicationCommand legacyCommand = legacyClass.getDeclaredConstructor().newInstance()
                ApplicationCommand command = new LegacyApplicationCommandAdapter(legacyCommand)
                if (registrar.register(command) && !warningLogged) {
                    log.warn("Command '{}' from a Grails 7 plugin was loaded through the deprecated grails.dev.commands compatibility layer. Ask the plugin author to migrate to the org.apache.grails.core.cli command API and publish a -cli companion artifact; this compatibility path will be removed in a future major release.", command.name)
                    warningLogged = true
                }
            }
            catch (Throwable e) {
                log.warn("Failed to load a Grails 7 legacy command from class '{}' through the deprecated grails.dev.commands compatibility layer; skipping it.",
                        legacyClass?.name, e)
            }
        }
    }
}

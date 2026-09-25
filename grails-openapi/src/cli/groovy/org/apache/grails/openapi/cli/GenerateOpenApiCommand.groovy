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
package org.apache.grails.openapi.cli

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import io.swagger.v3.oas.models.OpenAPI
import org.springframework.util.ClassUtils

import grails.openapi.GrailsOpenApiGenerator
import grails.openapi.OpenApiSelection
import grails.openapi.OpenApiSettings
import org.apache.grails.core.cli.ApplicationCommand
import org.apache.grails.core.cli.ExecutionContext
import org.grails.openapi.springdoc.GroupedOpenApiContributor

/**
 * Writes the application's OpenAPI description to files, so the description can be packaged,
 * served as a static file, reviewed in a change, or handed to a code generator without running
 * the application.
 *
 * <p>The default document is written to {@code openapi.yaml}, and each group - configured under
 * {@code grails.openapi.groups}, or declared to springdoc as a {@code GroupedOpenApi} - to
 * {@code openapi-<group>.yaml}. Where springdoc is configured, its method filters and customizers
 * are applied as they are to the documents it serves. The directory and format come
 * from {@code grails.openapi.output-directory} and {@code grails.openapi.output-format}, and can be
 * overridden with the {@code --output-directory} and {@code --format} options.</p>
 */
@Slf4j
@CompileStatic
class GenerateOpenApiCommand implements ApplicationCommand {

    private static final String SPRINGDOC_GROUP = 'org.springdoc.core.models.GroupedOpenApi'

    final String description = 'Writes the OpenAPI description of the application to files'

    @Override
    boolean handle(ExecutionContext executionContext) {
        GrailsOpenApiGenerator generator = applicationContext.getBeanProvider(GrailsOpenApiGenerator).getIfAvailable()
        if (generator == null) {
            log.error('Wrote no OpenAPI description: grails.openapi.enabled is false')
            return false
        }
        OpenApiSettings settings = generator.settings

        String format = (option(executionContext, 'format') ?: settings.outputFormat).toLowerCase(Locale.ENGLISH)
        if (!(format in ['yaml', 'json'])) {
            log.error('Unsupported OpenAPI format [{}]: use yaml or json', format)
            return false
        }
        File directory = outputDirectory(executionContext, option(executionContext, 'output-directory') ?: settings.outputDirectory)
        if (!directory.directory && !directory.mkdirs()) {
            log.error('Could not create the OpenAPI output directory [{}]', directory)
            return false
        }

        write(customized(generator.generate(defaultSelection(settings)), null), new File(directory, "openapi.${format}"), format)
        for (OpenApiSelection group : groups(settings)) {
            write(customized(generator.generate(group), group.group), new File(directory, "openapi-${group.group}.${format}"), format)
        }
        true
    }

    /**
     * The default document, with the method filters springdoc applies to it where springdoc is
     * configured, so the file describes what springdoc serves.
     */
    private OpenApiSelection defaultSelection(OpenApiSettings settings) {
        springdocPresent()
                ? GroupedOpenApiContributor.defaultSelection(applicationContext, settings.defaultSelection)
                : settings.defaultSelection
    }

    /**
     * The groups springdoc serves, which include those configured under
     * {@code grails.openapi.groups}, or those groups alone without springdoc.
     */
    private Collection<OpenApiSelection> groups(OpenApiSettings settings) {
        Map<String, OpenApiSelection> byName = [:]
        if (springdocPresent()) {
            GroupedOpenApiContributor.declaredGroups(applicationContext).each { OpenApiSelection group ->
                byName.putIfAbsent(group.group, group)
            }
        }
        settings.groups.each { OpenApiSelection group -> byName.putIfAbsent(group.group, group) }
        byName.values()
    }

    /**
     * The document with the customizers springdoc applies to the one it serves, where springdoc is
     * configured.
     */
    private OpenAPI customized(OpenAPI openApi, String group) {
        if (springdocPresent()) {
            GroupedOpenApiContributor.customize(applicationContext, group, openApi)
        }
        openApi
    }

    private static boolean springdocPresent() {
        ClassUtils.isPresent(SPRINGDOC_GROUP, GenerateOpenApiCommand.classLoader)
    }

    private static void write(OpenAPI openApi, File file, String format) {
        file.setText(GrailsOpenApiGenerator.serialize(openApi, format), 'UTF-8')
        println "Wrote ${file}"
    }

    private static File outputDirectory(ExecutionContext executionContext, String path) {
        File directory = new File(path)
        directory.absolute ? directory : new File(executionContext.baseDir ?: new File('.'), path)
    }

    private static String option(ExecutionContext executionContext, String name) {
        Object value = executionContext.commandLine?.optionValue(name)
        value instanceof CharSequence && value ? value.toString() : null
    }
}

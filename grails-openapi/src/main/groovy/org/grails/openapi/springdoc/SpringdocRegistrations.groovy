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
package org.grails.openapi.springdoc

import groovy.transform.CompileStatic

import org.springdoc.core.models.GroupedOpenApi
import org.springframework.beans.factory.BeanRegistry

import grails.openapi.GrailsOpenApiGenerator
import grails.openapi.OpenApiSelection
import grails.openapi.OpenApiSettings

/**
 * Registers what contributes the Grails description to the documents springdoc serves.
 */
@CompileStatic
class SpringdocRegistrations {

    static void register(BeanRegistry registry, OpenApiSettings settings, String generatorBeanName) {
        // A plain customizer is applied to springdoc's default document only; each group is given
        // its own by the contributor.
        registry.registerBean('grailsOpenApiCustomizer', GrailsOpenApiCustomizer) {
            it.supplier { context ->
                GrailsOpenApiGenerator generator = context.bean(generatorBeanName, GrailsOpenApiGenerator)
                new GrailsOpenApiCustomizer({ -> generator }, { -> settings.defaultSelection })
            }
        }
        registry.registerBean('grailsGroupedOpenApiContributor', GroupedOpenApiContributor) {
            it.infrastructure()
        }
        // springdoc serves the configured groups with the same criteria applied to its own
        // endpoints; the contributor adds the Grails ones.
        for (OpenApiSelection group : settings.groups) {
            registry.registerBean("grailsOpenApiGroup_${group.group}".toString(), GroupedOpenApi) {
                it.supplier { groupedOpenApi(group) }
            }
        }
    }

    private static GroupedOpenApi groupedOpenApi(OpenApiSelection group) {
        GroupedOpenApi.Builder builder = GroupedOpenApi.builder().group(group.group)
        if (group.displayName) {
            builder.displayName(group.displayName)
        }
        if (group.pathsToMatch) {
            builder.pathsToMatch(group.pathsToMatch as String[])
        }
        if (group.pathsToExclude) {
            builder.pathsToExclude(group.pathsToExclude as String[])
        }
        if (group.packagesToScan) {
            builder.packagesToScan(group.packagesToScan as String[])
        }
        if (group.packagesToExclude) {
            builder.packagesToExclude(group.packagesToExclude as String[])
        }
        if (group.producesToMatch) {
            builder.producesToMatch(group.producesToMatch as String[])
        }
        if (group.consumesToMatch) {
            builder.consumesToMatch(group.consumesToMatch as String[])
        }
        if (group.headersToMatch) {
            builder.headersToMatch(group.headersToMatch as String[])
        }
        // A group without criteria selects everything, which springdoc only accepts from a group
        // that carries a customizer; the contributor supplies the one that matters.
        builder.addOpenApiCustomizer { }
        builder.build()
    }
}

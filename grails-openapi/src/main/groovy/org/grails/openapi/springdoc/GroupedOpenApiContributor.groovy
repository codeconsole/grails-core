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

import org.springdoc.api.AbstractMultipleOpenApiResource
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.customizers.SpringDocCustomizers
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.beans.BeansException
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.ApplicationContext

import grails.openapi.GrailsOpenApiGenerator
import grails.openapi.OpenApiSelection

/**
 * Contributes the Grails description to every springdoc group.
 *
 * <p>springdoc gives a group only the customizers the group itself carries and the global ones,
 * and a global customizer cannot tell which group it is customizing. So each group - one this
 * module registers for {@code grails.openapi.groups}, or one the application declares - is given
 * its own customizer, selecting what the group's criteria select, before springdoc builds the
 * group's document.</p>
 *
 * <p>The Grails description is contributed ahead of every other customizer, in each group and in
 * the default document, so a customizer the application declares sees the Grails operations.</p>
 */
@CompileStatic
class GroupedOpenApiContributor implements BeanPostProcessor, BeanFactoryAware {

    private ListableBeanFactory beanFactory

    @Override
    void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = (ListableBeanFactory) beanFactory
    }

    @Override
    Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof AbstractMultipleOpenApiResource) {
            beanFactory.getBeansOfType(GroupedOpenApi).values().each { GroupedOpenApi group ->
                // Read as the document is built, once springdoc has given the group the global
                // method filters.
                group.addAllOpenApiCustomizer([new GrailsOpenApiCustomizer(
                        { -> beanFactory.getBean(GrailsOpenApiGenerator) },
                        { -> SpringdocSelections.groupSelection(group, SpringdocSelections.customizers(beanFactory)) })])
            }
        }
        bean
    }

    @Override
    Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof AbstractMultipleOpenApiResource) {
            // springdoc puts the global customizers ahead of a group's own as it prepares the group.
            beanFactory.getBeansOfType(GroupedOpenApi).values().each { GroupedOpenApi group ->
                contributeFirst(group.openApiCustomizers)
            }
        }
        else if (bean instanceof SpringDocCustomizers) {
            ((SpringDocCustomizers) bean).openApiCustomizers.ifPresent { Set<OpenApiCustomizer> customizers ->
                contributeFirst(customizers)
            }
        }
        bean
    }

    /**
     * Moves the Grails contribution ahead of the other customizers, in the set springdoc applies.
     */
    private static void contributeFirst(Set<OpenApiCustomizer> customizers) {
        List<OpenApiCustomizer> grails = customizers.findAll { OpenApiCustomizer it -> it instanceof GrailsOpenApiCustomizer }.toList()
        if (!grails) {
            return
        }
        List<OpenApiCustomizer> others = customizers.findAll { OpenApiCustomizer it -> !(it instanceof GrailsOpenApiCustomizer) }.toList()
        customizers.clear()
        customizers.addAll(grails)
        customizers.addAll(others)
    }

    /**
     * The groups an application declares to springdoc, so the {@code generate-open-api} command
     * writes a document for each of them too.
     */
    static List<OpenApiSelection> declaredGroups(ApplicationContext applicationContext) {
        applicationContext.getBeansOfType(GroupedOpenApi).values().collect { GroupedOpenApi group ->
            SpringdocSelections.groupSelection(group, SpringdocSelections.customizers(applicationContext))
        }
    }

    /**
     * The document an application generates without serving it: its default document, with
     * springdoc's method filters applied, where springdoc is configured.
     */
    static OpenApiSelection defaultSelection(ApplicationContext applicationContext, OpenApiSelection criteria) {
        SpringdocSelections.defaultSelection(criteria, SpringdocSelections.customizers(applicationContext))
    }

    /**
     * The criteria springdoc applies to the group's Spring MVC endpoints, applied to its Grails
     * endpoints too.
     */
    static OpenApiSelection selectionOf(GroupedOpenApi group) {
        new OpenApiSelection(
                group: group.group,
                displayName: group.displayName,
                pathsToMatch: group.pathsToMatch ?: [],
                pathsToExclude: group.pathsToExclude ?: [],
                packagesToScan: group.packagesToScan ?: [],
                packagesToExclude: group.packagesToExclude ?: [],
                producesToMatch: group.producesToMatch ?: [],
                consumesToMatch: group.consumesToMatch ?: [],
                headersToMatch: group.headersToMatch ?: [])
    }
}

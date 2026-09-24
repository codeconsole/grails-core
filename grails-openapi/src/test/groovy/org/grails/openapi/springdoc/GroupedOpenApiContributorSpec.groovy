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

import java.lang.reflect.Method

import io.swagger.v3.oas.models.OpenAPI
import org.springdoc.core.customizers.SpringDocCustomizers
import org.springdoc.core.filters.GlobalOpenApiMethodFilter
import org.springdoc.core.filters.OpenApiMethodFilter
import org.springdoc.core.models.GroupedOpenApi
import org.springdoc.webmvc.api.MultipleOpenApiWebMvcResource
import org.springframework.beans.factory.support.DefaultListableBeanFactory

import grails.openapi.GrailsOpenApiGenerator
import grails.openapi.OpenApiSelection
import grails.openapi.OpenApiFixture
import grails.openapi.WidgetController
import grails.openapi.Widget
import grails.openapi.Crate
import grails.openapi.namespaced.v1.GateController

import spock.lang.Specification

class GroupedOpenApiContributorSpec extends Specification {

    void 'contributes to each springdoc group what the group selects'() {
        given: 'a group declared as a bean, and one springdoc created from its properties'
        def beanFactory = new DefaultListableBeanFactory()
        beanFactory.registerSingleton('generator', generator())
        beanFactory.registerSingleton('gates', GroupedOpenApi.builder().group('gates').pathsToMatch('/gate/**').build())
        beanFactory.registerSingleton('widgets', GroupedOpenApi.builder().group('widgets')
                .packagesToScan('grails.openapi').pathsToExclude('/gate/**').build())
        def contributor = new GroupedOpenApiContributor()
        contributor.beanFactory = beanFactory

        when: 'springdoc prepares its grouped documents'
        contributor.postProcessBeforeInitialization(resource(beanFactory), 'multipleOpenApiResource')

        then:
        paths(beanFactory.getBean('gates', GroupedOpenApi)) == ['/gate'] as Set
        paths(beanFactory.getBean('widgets', GroupedOpenApi)) == ['/widgets', '/widgets/{id}'] as Set
    }

    void 'applies the method filters of a group, and the global ones, to the Grails actions'() {
        given:
        def beanFactory = new DefaultListableBeanFactory()
        beanFactory.registerSingleton('generator', generator())
        beanFactory.registerSingleton('springDocCustomizers',
                customizers(globalMethodFilters: [{ Method action -> action.name != 'delete' } as GlobalOpenApiMethodFilter]))
        beanFactory.registerSingleton('widgets', GroupedOpenApi.builder().group('widgets').pathsToMatch('/widgets/**')
                .addOpenApiMethodFilter { Method action -> action.name != 'save' }.build())
        def contributor = new GroupedOpenApiContributor()
        contributor.beanFactory = beanFactory

        when:
        contributor.postProcessBeforeInitialization(resource(beanFactory), 'multipleOpenApiResource')

        then:
        operations(document(beanFactory.getBean('widgets', GroupedOpenApi))) ==
                ['GET /widgets', 'GET /widgets/{id}', 'PUT /widgets/{id}', 'POST /widgets/{id}', 'PATCH /widgets/{id}'] as Set
    }

    void 'applies the method filters springdoc applies to its default document'() {
        given:
        def customizers = customizers(methodFilters: [{ Method action -> action.name != 'delete' } as OpenApiMethodFilter])

        when:
        def openApi = generator().generate(SpringdocSelections.defaultSelection(new OpenApiSelection(), customizers))

        then:
        openApi.paths['/widgets/{id}'].get
        openApi.paths['/widgets/{id}'].delete == null
    }

    void 'leaves every other bean alone'() {
        given:
        def contributor = new GroupedOpenApiContributor()
        contributor.beanFactory = new DefaultListableBeanFactory()
        def bean = new Object()

        expect:
        contributor.postProcessBeforeInitialization(bean, 'other').is(bean)
    }

    void 'reads the groups an application declares, so they are generated at build time too'() {
        given:
        def context = new org.springframework.context.support.GenericApplicationContext()
        context.beanFactory.registerSingleton('gates', GroupedOpenApi.builder().group('gates').displayName('Gates')
                .pathsToMatch('/gate/**').packagesToExclude('com.example').producesToMatch('application/json')
                .consumesToMatch('text/xml').headersToMatch('X-Api-Version=1').build())
        context.refresh()

        when:
        def groups = GroupedOpenApiContributor.declaredGroups(context)

        then:
        groups*.group == ['gates']
        groups[0].displayName == 'Gates'
        groups[0].pathsToMatch == ['/gate/**']
        groups[0].packagesToExclude == ['com.example']
        groups[0].producesToMatch == ['application/json']
        groups[0].consumesToMatch == ['text/xml']
        groups[0].headersToMatch == ['X-Api-Version=1']

        cleanup:
        context.close()
    }

    void 'generates a group with the global method filters at build time, where springdoc has not added them'() {
        given:
        def context = new org.springframework.context.support.GenericApplicationContext()
        context.beanFactory.registerSingleton('springDocCustomizers',
                customizers(globalMethodFilters: [{ Method action -> action.name != 'delete' } as GlobalOpenApiMethodFilter]))
        context.beanFactory.registerSingleton('widgets', GroupedOpenApi.builder().group('widgets')
                .pathsToMatch('/widgets/**').build())
        context.refresh()

        when:
        def openApi = generator().generate(GroupedOpenApiContributor.declaredGroups(context).first())

        then:
        openApi.paths['/widgets/{id}'].get
        openApi.paths['/widgets/{id}'].delete == null

        cleanup:
        context.close()
    }

    private static OpenAPI document(GroupedOpenApi group) {
        def openApi = new OpenAPI()
        group.openApiCustomizers.each { it.customise(openApi) }
        openApi
    }

    private static Set<String> operations(OpenAPI openApi) {
        openApi.paths.collectMany { String path, item ->
            item.readOperationsMap().keySet().collect { "${it} ${path}".toString() }
        } as Set<String>
    }

    private static SpringDocCustomizers customizers(Map<String, Collection> declared) {
        new SpringDocCustomizers(
                Optional.of((declared.openApiCustomizers ?: []) as LinkedHashSet),
                Optional.of((declared.operationCustomizers ?: []) as LinkedHashSet),
                Optional.empty(), Optional.empty(),
                Optional.of((declared.methodFilters ?: []) as LinkedHashSet),
                Optional.of((declared.globalOpenApiCustomizers ?: []) as LinkedHashSet),
                Optional.of((declared.globalOperationCustomizers ?: []) as LinkedHashSet),
                Optional.of((declared.globalMethodFilters ?: []) as LinkedHashSet),
                Optional.empty(), Optional.empty())
    }

    private static Set<String> paths(GroupedOpenApi group) {
        def openApi = new OpenAPI()
        group.openApiCustomizers.each { it.customise(openApi) }
        openApi.paths.keySet()
    }

    private static MultipleOpenApiWebMvcResource resource(DefaultListableBeanFactory beanFactory) {
        new MultipleOpenApiWebMvcResource(beanFactory.getBeansOfType(GroupedOpenApi).values().toList(),
                null, null, null, null, null, null, null)
    }

    private static GrailsOpenApiGenerator generator() {
        OpenApiFixture.generator(OpenApiFixture.holder {
            '/widgets'(resources: 'widget')
            '/gate'(controller: 'gate', action: 'index', namespace: 'v1')
        }, OpenApiFixture.application([WidgetController, GateController]), OpenApiFixture.context([Widget, Crate]))
    }
}

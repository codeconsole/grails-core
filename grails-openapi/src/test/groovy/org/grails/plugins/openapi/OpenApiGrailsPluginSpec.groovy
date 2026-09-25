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
package org.grails.plugins.openapi

import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.oas.models.OpenAPI
import org.springdoc.core.customizers.OpenApiBuilderCustomizer
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.beans.factory.support.BeanRegistryAdapter
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

import grails.artefact.Artefact
import grails.core.DefaultGrailsApplication
import grails.core.GrailsApplication
import grails.openapi.GrailsOpenApiGenerator
import grails.web.mapping.UrlMappingsHolder
import org.grails.support.MockApplicationContext
import org.grails.web.mapping.DefaultUrlMappingEvaluator
import org.grails.web.mapping.DefaultUrlMappingsHolder

import spock.lang.Specification

class OpenApiGrailsPluginSpec extends Specification {

    void 'registers the generator wired to the application URL mappings'() {
        given:
        def beanFactory = register()

        when:
        def openApi = beanFactory.getBean(OpenApiGrailsPlugin.GENERATOR_BEAN_NAME, GrailsOpenApiGenerator).generate()

        then:
        openApi.paths.containsKey('/books')
    }

    void 'contributes the description to the document springdoc serves'() {
        given:
        def beanFactory = register()
        def openApi = new OpenAPI()

        when:
        beanFactory.getBean('grailsOpenApiCustomizer', OpenApiCustomizer).customise(openApi)

        then:
        openApi.paths.containsKey('/books')
    }

    void 'serves each configured group through springdoc, with the same criteria'() {
        when:
        def beanFactory = register('grails.openapi.groups.catalog.paths-to-match': '/books/**',
                'grails.openapi.groups.catalog.display-name': 'Catalog',
                'grails.openapi.groups.catalog.produces-to-match': 'application/json',
                'grails.openapi.groups.catalog.consumes-to-match': 'application/json',
                'grails.openapi.groups.catalog.headers-to-match': 'X-Api-Version=1')
        def group = beanFactory.getBeansOfType(GroupedOpenApi).values().find { it.group == 'catalog' }

        then:
        group.pathsToMatch == ['/books/**']
        group.displayName == 'Catalog'
        group.producesToMatch == ['application/json']
        group.consumesToMatch == ['application/json']
        group.headersToMatch == ['X-Api-Version=1']
    }

    void 'registers with springdoc a model converter, and what records the types springdoc resolves as it builds a document'() {
        when:
        def beanFactory = register()

        then: 'springdoc registers the converters and the builder customizers the application declares'
        beanFactory.getBeansOfType(ModelConverter).size() == 1
        beanFactory.getBeansOfType(OpenApiBuilderCustomizer).size() == 1
    }

    void 'registers nothing when the document is disabled'() {
        when:
        def beanFactory = register('grails.openapi.enabled': false)

        then:
        !beanFactory.containsBean(OpenApiGrailsPlugin.GENERATOR_BEAN_NAME)
        !beanFactory.containsBean('grailsOpenApiCustomizer')
        beanFactory.getBeansOfType(ModelConverter).isEmpty()
        beanFactory.getBeansOfType(OpenApiBuilderCustomizer).isEmpty()
    }

    void 'declares a dependency on the URL mappings plugin'() {
        expect:
        new OpenApiGrailsPlugin().dependsOn.containsKey('urlMappings')
    }

    private static DefaultListableBeanFactory register(Map<String, Object> config = [:]) {
        def beanFactory = new DefaultListableBeanFactory()
        def application = new DefaultGrailsApplication(BookController).tap { it.initialise() }
        beanFactory.registerSingleton(GrailsApplication.APPLICATION_ID, application)
        beanFactory.registerSingleton('grailsUrlMappingsHolder', urlMappingsHolder(application) {
            '/books'(controller: 'book', action: 'index', method: 'GET')
        })
        def environment = new StandardEnvironment()
        environment.propertySources.addFirst(new MapPropertySource('test', config))
        def registrar = new OpenApiGrailsPlugin().beanRegistrar()
        new BeanRegistryAdapter(beanFactory, environment, registrar.class).register(registrar)
        beanFactory
    }

    private static UrlMappingsHolder urlMappingsHolder(GrailsApplication application, Closure mappings) {
        def ctx = new MockApplicationContext()
        ctx.registerMockBean(GrailsApplication.APPLICATION_ID, application)
        new DefaultUrlMappingsHolder(new DefaultUrlMappingEvaluator(ctx).evaluateMappings(mappings))
    }
}

@Artefact('Controller')
class BookController {
    def index() { }
    def show() { }
}

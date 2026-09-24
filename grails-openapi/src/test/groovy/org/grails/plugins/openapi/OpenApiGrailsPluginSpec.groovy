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

import io.swagger.v3.oas.models.OpenAPI
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.beans.factory.support.BeanRegistryAdapter
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

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
        def beanFactory = register('grails.openapi.groups.catalogue.paths-to-match': '/books/**',
                'grails.openapi.groups.catalogue.display-name': 'Catalogue')
        def group = beanFactory.getBeansOfType(GroupedOpenApi).values().find { it.group == 'catalogue' }

        then:
        group.pathsToMatch == ['/books/**']
        group.displayName == 'Catalogue'
    }

    void 'registers nothing when the document is disabled'() {
        when:
        def beanFactory = register('grails.openapi.enabled': false)

        then:
        !beanFactory.containsBean(OpenApiGrailsPlugin.GENERATOR_BEAN_NAME)
        !beanFactory.containsBean('grailsOpenApiCustomizer')
    }

    void 'declares a dependency on the URL mappings plugin'() {
        expect:
        new OpenApiGrailsPlugin().dependsOn.containsKey('urlMappings')
    }

    private static DefaultListableBeanFactory register(Map<String, Object> config = [:]) {
        def application = new DefaultGrailsApplication()
        def beanFactory = new DefaultListableBeanFactory()
        beanFactory.registerSingleton(GrailsApplication.APPLICATION_ID, application)
        beanFactory.registerSingleton('grailsUrlMappingsHolder', urlMappingsHolder(application))
        def environment = new StandardEnvironment()
        environment.propertySources.addFirst(new MapPropertySource('test', config))
        def registrar = new OpenApiGrailsPlugin().beanRegistrar()
        new BeanRegistryAdapter(beanFactory, environment, registrar.class).register(registrar)
        beanFactory
    }

    private static UrlMappingsHolder urlMappingsHolder(GrailsApplication application) {
        def ctx = new MockApplicationContext()
        ctx.registerMockBean(GrailsApplication.APPLICATION_ID, application)
        def evaluator = new DefaultUrlMappingEvaluator(ctx)
        new DefaultUrlMappingsHolder(evaluator.evaluateMappings {
            '/books'(controller: 'book', action: 'index', method: 'GET')
        })
    }
}

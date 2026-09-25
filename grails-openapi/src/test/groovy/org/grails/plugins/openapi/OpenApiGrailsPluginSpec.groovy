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

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.core.jackson.ModelResolver
import io.swagger.v3.oas.models.OpenAPI
import org.springdoc.core.converters.ModelConverterRegistrar
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.models.GroupedOpenApi
import org.springdoc.core.properties.SpringDocConfigProperties
import org.springframework.beans.factory.support.BeanRegistryAdapter
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.context.annotation.AnnotationConfigUtils
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

import grails.artefact.Artefact
import grails.core.DefaultGrailsApplication
import grails.core.GrailsApplication
import grails.openapi.GrailsOpenApiGenerator
import grails.validation.Validateable
import grails.web.mapping.UrlMappingsHolder
import org.grails.openapi.GrailsModelConverter
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

    void 'springdoc registers the Grails converter with its own as the application starts'() {
        given: 'swagger-core as a new JVM has it'
        def properties = new SpringDocConfigProperties()
        def converters = ModelConverters.getInstance(properties.openapi31)
        converters.removeConverter(GrailsModelConverter.INSTANCE)

        when: 'the plugin registers its beans'
        def beanFactory = register()

        then: 'swagger-core is left as it is, since an application processed ahead of time registers no beans as it starts'
        !converters.converters.contains(GrailsModelConverter.INSTANCE)

        when: 'springdoc registers the converters the application declares, before it resolves any type'
        new ModelConverterRegistrar(beanFactory.getBeansOfType(ModelConverter).values().toList(), properties)

        then:
        converters.converters.contains(GrailsModelConverter.INSTANCE)
    }

    void 'springdoc puts the Grails converter closest to swagger-core, so its converters and the application\'s see what it describes'() {
        given: 'an application context with a converter of its own, which springdoc is given every converter of'
        def context = new GenericApplicationContext()
        AnnotationConfigUtils.registerAnnotationConfigProcessors(context)
        context.registerBean('applicationConverter', ApplicationConverter)
        registerInto(context.defaultListableBeanFactory)
        context.registerBean(SpringdocConverters)
        context.refresh()
        def properties = new SpringDocConfigProperties()

        when: 'springdoc registers them in the order it is given them'
        List<ModelConverter> injected = context.getBean(SpringdocConverters).converters
        new ModelConverterRegistrar(injected, properties)
        List<ModelConverter> chain = ModelConverters.getInstance(properties.openapi31).converters
        int grails = chain.indexOf(GrailsModelConverter.INSTANCE)

        then: 'the Grails converter is given first, so it is added last'
        injected.first().is(GrailsModelConverter.INSTANCE)
        chain.findIndexOf { it instanceof ApplicationConverter } < grails
        chain[grails + 1] instanceof ModelResolver

        and: 'an application injecting a converter of its own is given its own'
        context.getBean(ModelConverter) instanceof ApplicationConverter

        cleanup:
        chain?.findAll { it instanceof ApplicationConverter }?.each { ModelConverters.getInstance(properties.openapi31).removeConverter(it) }
        context?.close()
    }

    void 'a type springdoc resolves for its own endpoints is described where what Grails declares of it cannot be read'() {
        given:
        def properties = new SpringDocConfigProperties()
        new ModelConverterRegistrar(register().getBeansOfType(ModelConverter).values().toList(), properties)

        when: 'its constraints fail to evaluate'
        def schemas = ModelConverters.getInstance(properties.openapi31).readAll(UnreadableConstraintsCommand)

        then: 'it is described as swagger-core resolves it'
        schemas['UnreadableConstraintsCommand'].properties.keySet() == ['name'] as Set
    }

    void 'registers nothing when the document is disabled'() {
        when:
        def beanFactory = register('grails.openapi.enabled': false)

        then:
        !beanFactory.containsBean(OpenApiGrailsPlugin.GENERATOR_BEAN_NAME)
        !beanFactory.containsBean('grailsOpenApiCustomizer')
        beanFactory.getBeansOfType(ModelConverter).isEmpty()
    }

    void 'declares a dependency on the URL mappings plugin'() {
        expect:
        new OpenApiGrailsPlugin().dependsOn.containsKey('urlMappings')
    }

    private static DefaultListableBeanFactory register(Map<String, Object> config = [:]) {
        registerInto(new DefaultListableBeanFactory(), config)
    }

    private static DefaultListableBeanFactory registerInto(DefaultListableBeanFactory beanFactory,
                                                           Map<String, Object> config = [:]) {
        def application = new DefaultGrailsApplication(BookController).tap { it.initialise() }
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

@Artefact('Controller')
class BookController {
    def index() { }
    def show() { }
}

class UnreadableConstraintsCommand implements Validateable {
    String name

    static Map getConstraintsMap() {
        throw new IllegalStateException('the constraints cannot be evaluated')
    }
}

/**
 * A converter an application declares, which passes every type on.
 */
class ApplicationConverter implements ModelConverter {

    @Override
    io.swagger.v3.oas.models.media.Schema resolve(AnnotatedType type, ModelConverterContext context,
                                                   Iterator<ModelConverter> chain) {
        chain.hasNext() ? chain.next().resolve(type, context, chain) : null
    }
}

/**
 * Takes the converters as springdoc's configuration takes them.
 */
class SpringdocConverters {

    final List<ModelConverter> converters

    SpringdocConverters(Optional<List<ModelConverter>> converters) {
        this.converters = converters.orElse([])
    }
}

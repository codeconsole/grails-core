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
package org.grails.plugins.web.interceptors

import org.springframework.beans.factory.BeanRegistrar
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.beans.factory.support.BeanRegistryAdapter
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.GenericBeanDefinition
import org.springframework.core.Ordered
import org.springframework.core.env.StandardEnvironment
import org.springframework.web.servlet.handler.MappedInterceptor

import grails.core.GrailsApplication
import grails.core.GrailsClass

import spock.lang.Specification

class InterceptorsGrailsPluginSpec extends Specification {

    static class TestInterceptor {
    }

    GrailsClass interceptorClass = Mock(GrailsClass) {
        getPropertyName() >> 'testInterceptor'
        getClazz() >> TestInterceptor
    }

    void "no interceptor beans are registered when there are no interceptor artefacts"() {
        given:
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory()
        GrailsApplication application = Mock(GrailsApplication) {
            getArtefacts(InterceptorArtefactHandler.TYPE) >> { new GrailsClass[0] }
        }

        when:
        applyRegistrar(beanFactory, application)

        then:
        beanFactory.beanDefinitionCount == 0
    }

    void "beanRegistrar registers the mapped interceptor around the named adapter bean"() {
        given:
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory()
        GrailsApplication application = Mock(GrailsApplication) {
            getArtefacts(InterceptorArtefactHandler.TYPE) >> { [interceptorClass] as GrailsClass[] }
        }

        when:
        applyRegistrar(beanFactory, application)

        then:
        beanFactory.getBeanDefinition('grailsInterceptorHandlerInterceptorAdapter').beanClassName ==
                GrailsInterceptorHandlerInterceptorAdapter.name
        beanFactory.containsBeanDefinition('interceptorBeanDefinitionsPostProcessor')

        and: 'the mapped interceptor wraps the adapter bean'
        MappedInterceptor mappedInterceptor = beanFactory.getBean('grailsInterceptorMappedInterceptor', MappedInterceptor)
        mappedInterceptor.interceptor.is(beanFactory.getBean('grailsInterceptorHandlerInterceptorAdapter',
                GrailsInterceptorHandlerInterceptorAdapter))
    }

    void "the definitions post-processor registers name-autowired interceptor beans"() {
        given:
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory()
        GrailsApplication application = Mock(GrailsApplication) {
            getArtefacts(InterceptorArtefactHandler.TYPE) >> { [interceptorClass] as GrailsClass[] }
        }
        InterceptorBeanDefinitionsPostProcessor postProcessor = new InterceptorBeanDefinitionsPostProcessor(application, true)

        when:
        postProcessor.postProcessBeanDefinitionRegistry(registry)
        AbstractBeanDefinition definition = (AbstractBeanDefinition) registry.getBeanDefinition('testInterceptor')

        then:
        definition.beanClassName == TestInterceptor.name
        definition.autowireMode == AbstractBeanDefinition.AUTOWIRE_BY_NAME
        definition.propertyValues.getPropertyValue('useJessionId').value == true
        postProcessor.order == Ordered.HIGHEST_PRECEDENCE
    }

    void "an existing interceptor bean definition wins"() {
        given:
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory()
        registry.registerBeanDefinition('testInterceptor', new GenericBeanDefinition(beanClass: String))
        GrailsApplication application = Mock(GrailsApplication) {
            getArtefacts(InterceptorArtefactHandler.TYPE) >> { [interceptorClass] as GrailsClass[] }
        }

        when:
        new InterceptorBeanDefinitionsPostProcessor(application, false).postProcessBeanDefinitionRegistry(registry)

        then:
        registry.getBeanDefinition('testInterceptor').beanClassName == String.name
    }

    private static void applyRegistrar(DefaultListableBeanFactory beanFactory, GrailsApplication application) {
        InterceptorsGrailsPlugin plugin = new InterceptorsGrailsPlugin(grailsApplication: application)
        BeanRegistrar registrar = plugin.beanRegistrar()
        new BeanRegistryAdapter(beanFactory, new StandardEnvironment(), registrar.getClass()).register(registrar)
    }
}

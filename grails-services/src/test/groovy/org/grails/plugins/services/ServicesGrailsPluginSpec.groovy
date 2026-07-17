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
package org.grails.plugins.services

import org.springframework.beans.factory.BeanRegistrar
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.beans.factory.support.BeanRegistryAdapter
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.GenericBeanDefinition
import org.springframework.core.Ordered
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

import grails.config.Settings
import grails.core.DefaultGrailsApplication
import grails.core.GrailsApplication
import grails.core.GrailsClass
import grails.core.GrailsServiceClass
import grails.plugins.GrailsPlugin
import grails.plugins.GrailsPluginManager
import org.grails.core.artefact.ServiceArtefactHandler
import org.grails.core.exceptions.GrailsConfigurationException

import spock.lang.Specification

class ServicesGrailsPluginSpec extends Specification {

    static class TestService {
    }

    GrailsServiceClass serviceClass = Mock(GrailsServiceClass) {
        getClazz() >> TestService
        getPropertyName() >> 'testService'
        getShortName() >> 'TestService'
        getPropertyValue('scope') >> null
        hasProperty('lazyInit') >> false
    }

    void "beanRegistrar registers the service infrastructure beans"() {
        given:
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory()

        when:
        applyRegistrar(beanFactory, new StandardEnvironment())

        then:
        beanFactory.containsBeanDefinition('serviceBeanDefinitionsPostProcessor')
        beanFactory.getBeanDefinition('serviceBeanAliasPostProcessor').beanClassName == ServiceBeanAliasPostProcessor.name
    }

    void "spring proxy-based transaction management is rejected"() {
        given:
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory()
        StandardEnvironment environment = new StandardEnvironment()
        environment.propertySources.addFirst(
                new MapPropertySource('test', [(Settings.SPRING_TRANSACTION_MANAGEMENT): 'true']))

        when:
        applyRegistrar(beanFactory, environment)

        then:
        thrown(GrailsConfigurationException)
    }

    void "the definitions post-processor registers name-autowired lazy service beans"() {
        given:
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory()
        GrailsApplication application = Mock(GrailsApplication) {
            getArtefacts(ServiceArtefactHandler.TYPE) >> { [serviceClass] as GrailsClass[] }
        }
        ServiceBeanDefinitionsPostProcessor postProcessor = new ServiceBeanDefinitionsPostProcessor(application, null)

        when:
        postProcessor.postProcessBeanDefinitionRegistry(registry)
        AbstractBeanDefinition definition = (AbstractBeanDefinition) registry.getBeanDefinition('testService')

        then:
        definition.beanClassName == TestService.name
        definition.autowireMode == AbstractBeanDefinition.AUTOWIRE_BY_NAME
        definition.lazyInit
        postProcessor.order == Ordered.HIGHEST_PRECEDENCE
    }

    void "services provided by a plugin are prefixed with the plugin name"() {
        given:
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory()
        GrailsApplication application = Mock(GrailsApplication) {
            getArtefacts(ServiceArtefactHandler.TYPE) >> { [serviceClass] as GrailsClass[] }
        }
        GrailsPlugin providingPlugin = Mock(GrailsPlugin) {
            getName() >> 'security'
        }
        GrailsPluginManager pluginManager = Mock(GrailsPluginManager) {
            getPluginForClass(TestService) >> providingPlugin
        }

        when:
        new ServiceBeanDefinitionsPostProcessor(application, pluginManager).postProcessBeanDefinitionRegistry(registry)

        then:
        registry.containsBeanDefinition('securityTestService')
        !registry.containsBeanDefinition('testService')
    }

    void "an existing service bean definition wins"() {
        given:
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory()
        registry.registerBeanDefinition('testService', new GenericBeanDefinition(beanClass: String))
        GrailsApplication application = Mock(GrailsApplication) {
            getArtefacts(ServiceArtefactHandler.TYPE) >> { [serviceClass] as GrailsClass[] }
        }

        when:
        new ServiceBeanDefinitionsPostProcessor(application, null).postProcessBeanDefinitionRegistry(registry)

        then:
        registry.getBeanDefinition('testService').beanClassName == String.name
    }

    private static void applyRegistrar(DefaultListableBeanFactory beanFactory, StandardEnvironment environment) {
        ServicesGrailsPlugin plugin = new ServicesGrailsPlugin(grailsApplication: new DefaultGrailsApplication())
        BeanRegistrar registrar = plugin.beanRegistrar()
        new BeanRegistryAdapter(beanFactory, environment, registrar.getClass()).register(registrar)
    }
}

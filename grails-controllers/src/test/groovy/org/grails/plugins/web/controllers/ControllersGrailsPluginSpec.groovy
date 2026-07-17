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
package org.grails.plugins.web.controllers

import java.lang.reflect.Field

import org.springframework.beans.factory.BeanRegistrar
import org.springframework.beans.factory.support.BeanRegistryAdapter
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.core.env.StandardEnvironment
import org.springframework.web.servlet.handler.SimpleMappingExceptionResolver

import grails.core.DefaultGrailsApplication
import org.grails.plugins.web.servlet.context.BootStrapClassRunner
import org.grails.web.errors.GrailsExceptionResolver
import org.grails.web.servlet.mvc.TokenResponseActionResultTransformer
import org.grails.web.servlet.view.CompositeViewResolver

import spock.lang.Specification

class ControllersGrailsPluginSpec extends Specification {

    DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory()

    void setup() {
        ControllersGrailsPlugin plugin = new ControllersGrailsPlugin(grailsApplication: new DefaultGrailsApplication())
        BeanRegistrar registrar = plugin.beanRegistrar()
        new BeanRegistryAdapter(beanFactory, new StandardEnvironment(), registrar.getClass()).register(registrar)
    }

    void "beanRegistrar registers the controllers infrastructure beans"() {
        expect:
        beanFactory.getBeanDefinition('bootStrapClassRunner').beanClassName == BootStrapClassRunner.name
        beanFactory.getBeanDefinition('tokenResponseActionResultTransformer').beanClassName == TokenResponseActionResultTransformer.name
        beanFactory.containsBeanDefinition('exceptionHandler')
        beanFactory.getBeanDefinition(CompositeViewResolver.BEAN_NAME).beanClassName == CompositeViewResolver.name
        beanFactory.containsBeanDefinition('controllerBeanDefinitionsPostProcessor')
    }

    void "the exception handler maps exceptions to the error view"() {
        when:
        GrailsExceptionResolver exceptionResolver = beanFactory.getBean('exceptionHandler', GrailsExceptionResolver)
        Field mappingsField = SimpleMappingExceptionResolver.getDeclaredField('exceptionMappings')
        mappingsField.accessible = true

        then:
        ((Properties) mappingsField.get(exceptionResolver)).getProperty('java.lang.Exception') == '/error'
    }

    void "the controller definitions post-processor is created for the plugin's application"() {
        expect:
        beanFactory.getBean('controllerBeanDefinitionsPostProcessor', ControllerBeanDefinitionsPostProcessor) != null
    }
}

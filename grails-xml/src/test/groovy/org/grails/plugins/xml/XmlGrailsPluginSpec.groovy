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
package org.grails.plugins.xml

import org.springframework.beans.factory.support.BeanRegistryAdapter
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.core.env.StandardEnvironment
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.Errors
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter

import grails.converters.XML
import grails.web.mime.MimeType
import org.grails.plugins.web.rest.render.DefaultRendererRegistry
import org.grails.web.converters.configuration.ObjectMarshallerRegisterer
import org.grails.web.converters.configuration.XmlConvertersConfigurationInitializer
import org.grails.web.converters.marshaller.xml.ValidationErrorsMarshaller
import org.grails.web.databinding.bindingsource.HalXmlDataBindingSourceCreator
import org.grails.web.databinding.bindingsource.XmlDataBindingSourceCreator

import spock.lang.Specification

class XmlGrailsPluginSpec extends Specification {

    def beanFactory = new DefaultListableBeanFactory()

    void setup() {
        def registrar = new XmlGrailsPlugin().beanRegistrar()
        new BeanRegistryAdapter(beanFactory, new StandardEnvironment(), registrar.getClass()).register(registrar)
    }

    void 'beanRegistrar registers optional XML infrastructure'() {
        expect:
        with(beanFactory) {
            getBeanDefinition('xmlErrorsMarshaller').beanClassName == ValidationErrorsMarshaller.name
            getBeanDefinition('xmlConvertersConfigurationInitializer').beanClassName ==
                    XmlConvertersConfigurationInitializer.name
            getBeanDefinition('xmlDataBindingSourceCreator').beanClassName == XmlDataBindingSourceCreator.name
            getBeanDefinition('halXmlDataBindingSourceCreator').beanClassName ==
                    HalXmlDataBindingSourceCreator.name
            getBeanDefinition('xmlRendererRegistrar').beanClassName == XmlRendererRegistrar.name
            containsBeanDefinition('errorsXmlMarshallerRegisterer')
            !containsBeanDefinition('grailsJacksonXmlHttpMessageConverter')
        }
    }

    void 'the errors marshaller registerer targets XML'() {
        when:
        def registerer = beanFactory.getBean('errorsXmlMarshallerRegisterer', ObjectMarshallerRegisterer)

        then:
        registerer.marshaller.is(beanFactory.getBean('xmlErrorsMarshaller', ValidationErrorsMarshaller))
        registerer.converterClass == XML
    }

    void 'the renderer registrar adds XML defaults to the core registry'() {
        given:
        def first = Stub(HttpMessageConverter)
        def second = Stub(HttpMessageConverter)
        def adapter = new RequestMappingHandlerAdapter(messageConverters: [first, second])
        def rendererRegistry = new DefaultRendererRegistry()
        rendererRegistry.initialize()
        def registrar = new XmlRendererRegistrar(
                rendererRegistry: rendererRegistry,
                requestMappingHandlerAdapter: adapter,
                encoding: 'ISO-8859-1'
        )

        when:
        registrar.afterPropertiesSet()

        then:
        with(rendererRegistry.findRenderer(MimeType.XML, new URL('https://grails.apache.org'))) {
            encoding == 'ISO-8859-1'
            springHttpMessageConverters == [first, second]
        }
        with(rendererRegistry.findContainerRenderer(
                MimeType.XML,
                Errors,
                new BeanPropertyBindingResult('value', 'target')
        )) {
            encoding == 'ISO-8859-1'
            springHttpMessageConverters == [first, second]
        }
    }
}

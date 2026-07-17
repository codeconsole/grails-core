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
package org.grails.plugins.web.mapping

import org.springframework.beans.factory.BeanRegistrar
import org.springframework.beans.factory.support.BeanRegistryAdapter
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.core.env.StandardEnvironment

import grails.core.GrailsApplication
import grails.core.GrailsClass
import org.grails.core.artefact.UrlMappingsArtefactHandler
import org.grails.web.mapping.UrlMappingsHolderFactoryBean

import spock.lang.Specification

class UrlMappingsGrailsPluginSpec extends Specification {

    void "beanRegistrar registers the url mappings beans"() {
        given: 'no url mappings artefacts exist'
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory()
        GrailsApplication application = Mock(GrailsApplication) {
            getArtefacts(UrlMappingsArtefactHandler.TYPE) >> { new GrailsClass[0] }
        }

        when:
        applyRegistrar(beanFactory, application)

        then: 'the default url mappings are contributed'
        1 * application.addArtefact(UrlMappingsArtefactHandler.TYPE, UrlMappingsGrailsPlugin.DefaultUrlMappings)

        and: 'the handler mapping and a lazy url mappings holder are registered'
        beanFactory.containsBeanDefinition('urlMappingsHandlerMapping')
        beanFactory.getBeanDefinition('grailsUrlMappingsHolder').lazyInit
        beanFactory.getBeanDefinition('grailsUrlMappingsHolder').beanClassName == UrlMappingsHolderFactoryBean.name
    }

    void "the default url mappings are not contributed when the application defines mappings"() {
        given:
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory()
        GrailsClass existingMappings = Mock(GrailsClass)
        GrailsApplication application = Mock(GrailsApplication) {
            getArtefacts(UrlMappingsArtefactHandler.TYPE) >> { [existingMappings] as GrailsClass[] }
        }

        when:
        applyRegistrar(beanFactory, application)

        then:
        0 * application.addArtefact(*_)
    }

    private static void applyRegistrar(DefaultListableBeanFactory beanFactory, GrailsApplication application) {
        UrlMappingsGrailsPlugin plugin = new UrlMappingsGrailsPlugin(grailsApplication: application)
        BeanRegistrar registrar = plugin.beanRegistrar()
        new BeanRegistryAdapter(beanFactory, new StandardEnvironment(), registrar.getClass()).register(registrar)
    }
}

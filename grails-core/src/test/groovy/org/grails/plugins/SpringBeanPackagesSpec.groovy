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

package org.grails.plugins

import org.springframework.context.support.GenericApplicationContext

import grails.core.DefaultGrailsApplication
import grails.core.GrailsApplication
import grails.spring.BeanBuilder

import org.grails.plugins.scan.ScannedComponent

import spock.lang.Specification

/**
 * {@code grails.spring.bean.packages} is documented as "List of packages to scan for
 * Spring beans". These assertions state that from an application's point of view: set
 * the property, get the beans.
 */
class SpringBeanPackagesSpec extends Specification {

    void 'a component in a configured package is registered as a bean'() {
        given:
        GrailsApplication application = new DefaultGrailsApplication()
        application.config.put('grails.spring.bean.packages', ['org.grails.plugins.scan'])

        when:
        def context = buildContext(application)

        then:
        context.getBeanNamesForType(ScannedComponent).length == 1
        context.getBean(ScannedComponent).greet() == 'scanned'

        cleanup:
        context.close()
    }

    void 'no scanning happens when the property is unset'() {
        given:
        GrailsApplication application = new DefaultGrailsApplication()

        when:
        def context = buildContext(application)

        then:
        context.getBeanNamesForType(ScannedComponent).length == 0

        cleanup:
        context.close()
    }

    private static def buildContext(GrailsApplication application) {
        // the core plugin's beans reference the grailsApplication bean, which the plugin
        // machinery contributes in a real context
        GenericApplicationContext parent = new GenericApplicationContext()
        parent.beanFactory.registerSingleton(GrailsApplication.APPLICATION_ID, application)
        parent.refresh()

        CoreGrailsPlugin plugin = new CoreGrailsPlugin()
        plugin.grailsApplication = application
        BeanBuilder builder = new BeanBuilder(parent)
        builder.beans plugin.doWithSpring()
        builder.createApplicationContext()
    }

}

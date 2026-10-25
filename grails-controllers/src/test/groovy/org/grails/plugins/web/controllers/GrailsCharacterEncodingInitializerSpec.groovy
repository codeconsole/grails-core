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

import org.springframework.boot.env.DefaultPropertiesPropertySource
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

import spock.lang.Specification

class GrailsCharacterEncodingInitializerSpec extends Specification {

    void 'translates the deprecated Grails properties into spring.servlet.encoding'() {
        given: 'an application that still sets the Grails properties'
        def environment = environmentWith('grails.filter.encoding': 'ISO-8859-1', 'grails.filter.forceEncoding': 'true')

        when: 'the application context is initialized'
        initialize(environment)

        then: 'Spring Boot reads the same settings from its own properties'
        environment.getProperty('spring.servlet.encoding.charset') == 'ISO-8859-1'
        environment.getProperty('spring.servlet.encoding.force') == 'true'
    }

    void 'lets a spring.servlet.encoding setting of the application win over a translated one'() {
        given: 'an application that sets both the Grails and the Spring Boot property'
        def environment = environmentWith('grails.filter.encoding': 'ISO-8859-1', 'spring.servlet.encoding.charset': 'UTF-16')

        when: 'the application context is initialized'
        initialize(environment)

        then: 'the translated value is contributed at the lowest precedence'
        environment.getProperty('spring.servlet.encoding.charset') == 'UTF-16'
    }

    void 'keeps a spring.servlet.encoding default property of the application'() {
        given: 'an application that sets the Spring Boot property as a default property and still has the Grails one'
        def environment = environmentWith('grails.filter.encoding': 'ISO-8859-1')
        DefaultPropertiesPropertySource.addOrMerge(['spring.servlet.encoding.charset': 'UTF-16'], environment.propertySources)

        when: 'the application context is initialized'
        initialize(environment)

        then: 'the deprecated value does not replace it'
        environment.getProperty('spring.servlet.encoding.charset') == 'UTF-16'
    }

    void 'contributes nothing when the deprecated properties are not set'() {
        given: 'an application that sets neither Grails property'
        def environment = new StandardEnvironment()

        when: 'the application context is initialized'
        initialize(environment)

        then: 'Spring Boot defaults apply'
        !environment.propertySources.contains(DefaultPropertiesPropertySource.NAME)
        environment.getProperty('spring.servlet.encoding.charset') == null
    }

    private static void initialize(StandardEnvironment environment) {
        def context = new GenericApplicationContext()
        context.environment = environment
        new GrailsCharacterEncodingInitializer().initialize(context)
    }

    private static StandardEnvironment environmentWith(Map<String, Object> properties) {
        new StandardEnvironment().tap {
            propertySources.addFirst(new MapPropertySource('application', properties))
        }
    }
}

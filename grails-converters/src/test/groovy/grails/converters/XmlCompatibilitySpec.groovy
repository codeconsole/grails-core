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
package grails.converters

import groovy.xml.XmlSlurper

import grails.core.DefaultGrailsApplication
import org.grails.web.converters.configuration.ConvertersConfigurationHolder
import org.grails.web.converters.configuration.ConvertersConfigurationInitializer

import spock.lang.Specification

class XmlCompatibilitySpec extends Specification {

    void setup() {
        new ConvertersConfigurationInitializer().tap {
            grailsApplication = new DefaultGrailsApplication()
            initialize()
        }
    }

    void cleanup() {
        ConvertersConfigurationHolder.clear()
    }

    void 'maps retain their entry names and nested collection shape'() {
        given:
        XML converter = new XML([
                title: 'Grails & Spring',
                editions: [7, 8],
                metadata: [active: true]
        ])

        when:
        def document = new XmlSlurper(false, false).parseText(converter.toString())

        then:
        document.name() == 'map'
        document.entry.find { it.@key == 'title' }.text() == 'Grails & Spring'
        document.entry.find { it.@key == 'editions' }.children()*.text() == ['7', '8']
        document.entry.find { it.@key == 'metadata' }.entry.find { it.@key == 'active' }.text() == 'true'
    }

    void 'registered marshallers retain priority and custom element output'() {
        given:
        XML.registerObjectMarshaller(XmlCompatibilityValue, 100) { XmlCompatibilityValue value, XML xml ->
            xml.attribute('code', value.code)
            xml.build {
                label(value.label)
            }
        }

        when:
        def document = new XmlSlurper(false, false).parseText(
                new XML(new XmlCompatibilityValue(code: 'g8', label: 'Grails 8')).toString()
        )

        then:
        document.name() == 'xmlCompatibilityValue'
        document.@code == 'g8'
        document.label.text() == 'Grails 8'
    }
}

class XmlCompatibilityValue {
    String code
    String label
}

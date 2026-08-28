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
package org.grails.plugins.web.rest.render.xml

import groovy.xml.XmlSlurper

import org.springframework.http.converter.xml.JacksonXmlHttpMessageConverter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext

import grails.web.mime.MimeType
import org.grails.plugins.web.rest.render.ServletRenderContext
import org.grails.web.servlet.mvc.GrailsWebRequest

import spock.lang.Specification

class SpringXmlRendererSpec extends Specification {

    void 'ordinary beans are written through the Spring XML message converter'() {
        given:
        def renderer = new DefaultXmlRenderer<XmlGreeting>(XmlGreeting)
        renderer.grailsJacksonXmlHttpMessageConverter = new JacksonXmlHttpMessageConverter()
        def response = new MockHttpServletResponse()
        def webRequest = new GrailsWebRequest(new MockHttpServletRequest(), response, new MockServletContext())
        def context = new ServletRenderContext(webRequest) {
            @Override
            MimeType getAcceptMimeType() {
                MimeType.XML
            }
        }

        when:
        renderer.render(new XmlGreeting(message: 'hello'), context)

        then:
        new XmlSlurper().parseText(response.contentAsString).message.text() == 'hello'
    }
}

class XmlGreeting {
    String message
}

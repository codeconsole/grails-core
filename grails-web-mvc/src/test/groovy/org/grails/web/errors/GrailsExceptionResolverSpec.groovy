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
package org.grails.web.errors

import grails.config.Config
import grails.core.GrailsApplication
import grails.web.mapping.UrlMappingsHolder
import grails.web.mapping.exceptions.UrlMappingException
import org.springframework.mock.web.MockHttpServletRequest
import spock.lang.Specification

import jakarta.servlet.http.HttpServletRequest

class GrailsExceptionResolverSpec extends Specification {

    def "exception not thrown if an UrlMappingException is thrown while trying to match a request uri with a UrlMappingInfo "() {
        given:
        GrailsExceptionResolver grailsExceptionResolver = new GrailsExceptionResolver()

        when:
        def urlMappingsHolder = Mock(UrlMappingsHolder)
        urlMappingsHolder.match(_ as String) >> { String uri ->
            throw new UrlMappingException('Unable to establish controller name to dispatch for')
        }
        HttpServletRequest request = new MockHttpServletRequest()
        Map params = grailsExceptionResolver.extractRequestParamsWithUrlMappingHolder(urlMappingsHolder, request)

        then:
        noExceptionThrown()
        params.isEmpty()
    }
    void "getRequestLogMessage masks excluded request parameters case-insensitively"() {
        given:
            def config = Mock(Config)
            config.getProperty('grails.exceptionresolver.logRequestParameters', Boolean, _) >> true
            config.getProperty('grails.exceptionresolver.params.exclude', List, _) >> [null, 'password', 'token']
            config.getProperty('grails.exceptionresolver.logAuditor', Boolean, false) >> false
            config.getProperty('grails.exceptionresolver.logRemoteAddr', Boolean, false) >> false
            config.getProperty('grails.exceptionresolver.logFullStackTraceOnFilter', Boolean, true) >> false
            config.getProperty('grails.exceptionresolver.logFullStackTrace', Boolean, false) >> false
            def grailsApp = Mock(GrailsApplication)
            grailsApp.getConfig() >> config
            def resolver = new GrailsExceptionResolver()
            resolver.grailsApplication = grailsApp
            def request = new MockHttpServletRequest('POST', '/login')
            request.addParameter('Password', 'secret')
            request.addParameter('apiToken', 'visible')
            request.addParameter('TOKEN', 'abc123')
            request.addParameter('username', 'sherlock')

        when:
            def msg = resolver.getRequestLogMessage('RuntimeException', request, 'boom')

        then:
            msg.contains('Password: ***')
            msg.contains('TOKEN: ***')
            msg.contains('username: sherlock')
            msg.contains('apiToken: visible')
            !msg.contains('Password: secret')
            !msg.contains('TOKEN: abc123')
    }

}

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
package org.grails.web.servlet.mvc

import jakarta.servlet.ServletContext
import jakarta.servlet.http.HttpServletRequestWrapper

import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.web.context.support.StaticWebApplicationContext

import org.grails.web.util.GrailsApplicationAttributes
import org.grails.web.util.HiddenHttpMethod

import spock.lang.Specification

class GrailsWebRequestSpec extends Specification {

    MockServletContext servletContext = new MockServletContext()

    void 'the application attributes are resolved once per servlet context, not once per request'() {
        given: 'a servlet context bound to an application context'
        def applicationContext = applicationContext()

        when: 'several requests are handled, as they would be at runtime'
        def first = newWebRequest()
        def second = newWebRequest()
        def third = newWebRequest()

        then: 'they share one attributes instance, so the beans it caches survive between requests'
        first.attributes.is(second.attributes)
        second.attributes.is(third.attributes)

        and: 'and it is the one resolved against the current application context'
        first.attributes.applicationContext.is(applicationContext)
    }

    void 'the application attributes are rebuilt when the application context is replaced'() {
        given: 'a request handled against one application context'
        applicationContext()
        def before = newWebRequest().attributes

        when: 'the context is replaced, as it is between tests or after a restart'
        def replacement = applicationContext()
        def after = newWebRequest().attributes

        then: 'a stale attributes instance is not served'
        !after.is(before)
        after.applicationContext.is(replacement)
    }

    void 'a null servlet context still yields usable attributes'() {
        expect: 'no caching is attempted, and construction does not blow up'
        new GrailsWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (ServletContext) null).attributes != null
    }

    void 'the current request is the request the web request was built with when nothing overrode it'() {
        given: 'a request already wrapped by a filter, as the outermost request usually is'
        def wrapped = new HttpServletRequestWrapper(new MockHttpServletRequest(servletContext))

        when: 'a web request is bound to it'
        def webRequest = new GrailsWebRequest(wrapped, new MockHttpServletResponse(), servletContext)

        then: 'the wrapper is what both accessors hand back'
        webRequest.getRequest().is(wrapped)
        webRequest.getCurrentRequest().is(wrapped)
    }

    void 'resolving a multipart request no longer substitutes the request Grails exposes'() {
        given: 'a web request bound to an ordinary request'
        def request = new MockHttpServletRequest(servletContext)
        def webRequest = new GrailsWebRequest(request, new MockHttpServletResponse(), servletContext)
        webRequest.params.put('name', 'unresolved')

        when: 'the dispatcher reports that multipart resolution has happened'
        webRequest.multipartRequestResolved()

        then: 'only the cached params are discarded - the request itself is untouched'
        webRequest.getRequest().is(request)
        webRequest.getCurrentRequest().is(request)
        !webRequest.params.containsKey('name')
    }

    void 'the current request accessor returns the bound request'() {
        given:
        def webRequest = newWebRequest()

        expect: 'no multipart substitution, so it is the request the filter bound'
        webRequest.currentRequest.is(webRequest.request)
    }

    void 'the current request accessor reports a dispatcher-resolved method override'() {
        given: 'the wrapper the dispatcher publishes when it resolves _method itself'
        def webRequest = newWebRequest()
        webRequest.request.method = 'POST'
        def wrapped = HiddenHttpMethod.wrap('DELETE', webRequest.request)

        when:
        webRequest.overriddenMethodRequest = wrapped

        then: 'an application sees the overridden method, as it does when the servlet filter applies it'
        webRequest.currentRequest.is(wrapped)
        webRequest.currentRequest.method == 'DELETE'

        and: 'while the bound request itself still reports what the client actually sent'
        webRequest.request.method == 'POST'
    }

    private GrailsWebRequest newWebRequest() {
        new GrailsWebRequest(new MockHttpServletRequest(servletContext), new MockHttpServletResponse(), servletContext)
    }

    private StaticWebApplicationContext applicationContext() {
        def applicationContext = new StaticWebApplicationContext()
        applicationContext.servletContext = servletContext
        applicationContext.refresh()
        servletContext.setAttribute(GrailsApplicationAttributes.APPLICATION_CONTEXT, applicationContext)
        applicationContext
    }
}

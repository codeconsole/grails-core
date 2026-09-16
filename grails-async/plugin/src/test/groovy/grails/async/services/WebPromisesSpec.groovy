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
package grails.async.services

import spock.lang.Specification

import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.async.WebAsyncUtils
import org.springframework.web.context.support.StaticWebApplicationContext
import org.springframework.core.env.MapPropertySource

import grails.async.Promises
import grails.async.decorator.PromiseDecorator
import grails.async.web.WebPromises
import grails.util.GrailsWebMockUtil
import org.grails.async.factory.future.CompletableFuturePromiseFactory
import org.grails.web.servlet.mvc.GrailsWebRequest
import org.grails.web.util.GrailsApplicationAttributes

class WebPromisesSpec extends Specification {

    void setup() {
        WebPromises.promiseFactory = null
    }

    void cleanup() {
        ((CompletableFuturePromiseFactory) WebPromises.promiseFactory).close()
        WebPromises.promiseFactory = null
        RequestContextHolder.resetRequestAttributes()
    }

    void 'Test web promises handling'() {

        setup:
            GrailsWebMockUtil.bindMockWebRequest()

        when: 'A promise is created'
            def webPromise = WebPromises.task {
                RequestContextHolder.currentRequestAttributes()
            }
            webPromise.get() != null

        then: 'Async was requested'
            def e = thrown(IllegalStateException)
            e.message.startsWith('Async support must be enabled')

        when: 'A normal promise is used'
            def promise = Promises.task { 'good' }

        then: 'No request is bound'
            promise.get() == 'good'

    }

    void 'multiple web tasks reuse Spring managed asynchronous processing'() {
        given:
        def servletContext = new MockServletContext()
        def response = new MockHttpServletResponse()
        def request = new MockHttpServletRequest(servletContext).tap {
            asyncSupported = true
        }
        RequestContextHolder.setRequestAttributes(new GrailsWebRequest(request, response, servletContext))

        when:
        def first = WebPromises.task { RequestContextHolder.currentRequestAttributes() }
        def second = WebPromises.task { RequestContextHolder.currentRequestAttributes() }

        then:
        first.get() instanceof GrailsWebRequest
        second.get() instanceof GrailsWebRequest
        request.asyncStarted
    }

    void 'explicit promise decorators are retained'() {
        given:
        PromiseDecorator decorator = { Closure original ->
            return { "decorated ${original.call()}" }
        }

        when:
        def promise = WebPromises.createPromise({ 'value' }, [decorator])

        then:
        promise.get() == 'decorated value'
    }

    void 'rejects new tasks after the asynchronous request completes'() {
        given:
        def servletContext = new MockServletContext()
        def request = new MockHttpServletRequest(servletContext)
        request.asyncSupported = true
        RequestContextHolder.setRequestAttributes(new GrailsWebRequest(request, new MockHttpServletResponse(), servletContext))
        WebPromises.task { 1 }.get()
        WebAsyncUtils.getAsyncManager(request).asyncWebRequest.onComplete(new jakarta.servlet.AsyncEvent(request.asyncContext))
        boolean executed = false

        when:
        WebPromises.task { executed = true }

        then:
        def failure = thrown(IllegalStateException)
        failure.message == 'Cannot start a task once asynchronous request processing has completed'
        !executed
    }

    void 'standalone web tasks propagate parameters and honor the MVC timeout'() {
        given:
        def context = new StaticWebApplicationContext()
        if (configured != null) {
            context.environment.propertySources.addFirst(new MapPropertySource('test', ['spring.mvc.async.request-timeout': configured]))
        }
        def servletContext = new MockServletContext()
        servletContext.setAttribute(GrailsApplicationAttributes.APPLICATION_CONTEXT, context)
        def request = new MockHttpServletRequest(servletContext)
        request.asyncSupported = true
        request.addParameter('title', 'Grails')
        def response = new MockHttpServletResponse()
        RequestContextHolder.setRequestAttributes(new GrailsWebRequest(request, response, servletContext))

        when:
        def result = WebPromises.task {
            def current = GrailsWebRequest.lookup()
            current.currentResponse.writer.write(current.params.title as String)
            return Thread.currentThread()
        }.get()

        then:
        !result.is(Thread.currentThread())
        response.contentAsString == 'Grails'
        request.asyncContext.timeout == expected

        cleanup:
        context.close()

        where:
        configured | expected
        '2s'       | 2000L
        '1500'     | 1500L
        null       | 10000L
    }
}

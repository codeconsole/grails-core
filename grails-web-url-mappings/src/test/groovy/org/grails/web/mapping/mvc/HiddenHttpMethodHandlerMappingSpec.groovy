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
package org.grails.web.mapping.mvc

import jakarta.servlet.http.HttpServletRequest

import org.springframework.mock.web.MockHttpServletRequest

import grails.artefact.Artefact
import grails.config.Settings
import grails.core.DefaultGrailsApplication
import grails.core.GrailsApplication
import grails.util.GrailsWebMockUtil
import grails.util.Holders
import grails.web.Action
import grails.web.mapping.AbstractUrlMappingsSpec
import grails.web.mapping.UrlMappingInfo
import grails.web.mapping.UrlMappingsHolder
import org.grails.config.PropertySourcesConfig
import org.grails.support.MockApplicationContext
import org.grails.web.mapping.DefaultUrlMappingEvaluator
import org.grails.web.mapping.DefaultUrlMappingsHolder
import org.grails.web.util.WebUtils

/**
 * With the hidden HTTP method filter disabled, the handler mapping resolves the '_method' parameter itself
 * while matching, so a browser form POST still reaches the PUT, PATCH and DELETE routes of a 'resources'
 * mapping -- without the request method being rewritten ahead of the dispatcher.
 */
class HiddenHttpMethodHandlerMappingSpec extends AbstractUrlMappingsSpec {

    void 'a form POST carrying _method reaches the DELETE route'() {
        given:
        def handler = bookHandlerMapping(true)

        when: 'a plain form POST to the member URL, naming DELETE'
        UrlMappingInfo info = match(handler, 'POST', '/books/1', 'DELETE')

        then:
        info.actionName == 'delete'
        info.controllerName == 'book'
    }

    void 'a form POST carrying _method reaches the PUT route'() {
        given:
        def handler = bookHandlerMapping(true)

        when:
        UrlMappingInfo info = match(handler, 'POST', '/books/1', 'PUT')

        then:
        info.actionName == 'update'
    }

    void 'a form POST carrying _method reaches the PATCH route'() {
        given:
        def handler = bookHandlerMapping(true)

        when:
        UrlMappingInfo info = match(handler, 'POST', '/books/1', 'PATCH')

        then:
        info.actionName == 'patch'
    }

    void 'the parameter is ignored while the filter is doing the rewriting'() {
        given: 'the default, where the servlet filter has already rewritten the method'
        def handler = bookHandlerMapping(false)

        when: 'a POST still carrying the parameter reaches the mapping unrewritten'
        UrlMappingInfo info = match(handler, 'POST', '/books/1', 'DELETE')

        then: 'nothing matches, because no POST route exists for the member URL'
        info == null
    }

    void 'only the methods a browser form cannot send are overridable'() {
        given: 'unlike the servlet filter, which applies any method name it is given'
        def handler = bookHandlerMapping(true)

        expect: 'the override is refused and the request stays a POST, so it can never become a read'
        match(handler, 'POST', '/books/1', 'GET').actionName == 'update'
        match(handler, 'POST', '/books/1', 'TRACE').actionName == 'update'

        and: 'and a GET to the member URL still reaches show, not anything the parameter asked for'
        match(handler, 'GET', '/books/1', 'DELETE').actionName == 'show'
    }

    void 'a POST to the member URL reaches update without any _method parameter'() {
        given: 'the shape AngularJS $resource and the clients modelled on it use to save an existing object'
        def handler = bookHandlerMapping(true)

        when:
        UrlMappingInfo info = match(handler, 'POST', '/books/1', null)

        then: 'RestfulController has permitted POST for update since #9926; this is the route for it'
        info.actionName == 'update'
        info.controllerName == 'book'
        info.id == '1'
    }

    void 'a forwarded POST does not resolve a hidden method parameter'() {
        given: 'a mapping that only accepts DELETE and a forwarded POST carrying _method=DELETE'
            def handler = deleteHandlerMapping()
            def request = request(
                    'POST',
                    '/books/1',
                    'DELETE',
                    WebUtils.FORWARD_REQUEST_URI_ATTRIBUTE
            )

        when: 'the handler mapping resolves the method for the forwarded request'
            def method = handler.callResolveHttpMethod(request)

        then: 'the forward remains a POST dispatch instead of selecting the DELETE route'
            method == 'POST'
    }

    void 'an included POST does not resolve a hidden method parameter'() {
        given: 'a mapping that only accepts DELETE and an included POST carrying _method=DELETE'
            def handler = deleteHandlerMapping()
            def request = request(
                    'POST',
                    '/books/1',
                    'DELETE',
                    WebUtils.INCLUDE_REQUEST_URI_ATTRIBUTE
            )

        when: 'the handler mapping resolves the method for the included request'
            def method = handler.callResolveHttpMethod(request)

        then: 'the include remains a POST dispatch instead of selecting the DELETE route'
            method == 'POST'
    }

    void 'a bare member POST does not introduce a new mutation route'() {
        given: 'an application with hidden method resolution moved into the dispatcher'
            def handler = bookHandlerMapping(true)

        expect: 'the generated resource mapping does not expose a new POST mutation route for members'
            match(handler, 'POST', '/books/1', null) == null
    }

    void 'the collection URL still routes a POST to save'() {
        given:
        def handler = bookHandlerMapping(true)

        expect: 'create and update stay separated by URL, exactly as $resource separates them'
        match(handler, 'POST', '/books', null).actionName == 'save'
    }

    void 'no POST route is added to the member URL while the filter is enabled'() {
        given: 'with the filter on, every path to update is rewritten to PUT before Spring Security sees it'
        def handler = bookHandlerMapping(false)

        expect: 'so the route is not generated, and the security posture of existing applications is unchanged'
        match(handler, 'POST', '/books/1', null) == null
    }

    void 'a real DELETE is unaffected'() {
        given:
        def handler = bookHandlerMapping(true)

        expect: 'an API client sending the real method routes as it always did'
        match(handler, 'DELETE', '/books/1', null).actionName == 'delete'
    }

    void cleanup() {
        // setConfig publishes to the static Holders, which would otherwise leak into sibling tests
        Holders.clear()
    }

    /**
     * @param filterDisabled whether the application has switched the hidden HTTP method filter off, which
     *                       both moves the override into the request path and generates the POST update route
     */
    private UrlMappingsHandlerMapping bookHandlerMapping(boolean filterDisabled) {
        def config = new PropertySourcesConfig()
        config.merge([(Settings.WEB_HIDDEN_METHOD_FILTER_ENABLED): !filterDisabled])

        def grailsApplication = new DefaultGrailsApplication(BookController)
        grailsApplication.config = config
        grailsApplication.initialise()

        def ctx = new MockApplicationContext()
        ctx.registerMockBean(GrailsApplication.APPLICATION_ID, grailsApplication)
        def evaluator = new DefaultUrlMappingEvaluator(ctx)
        def holder = new DefaultUrlMappingsHolder(evaluator.evaluateMappings {
            "/books"(resources: 'book')
        })

        def handler = new TestUrlMappingsHandlerMapping(new GrailsControllerUrlMappings(grailsApplication, holder))
        handler.resolveHiddenHttpMethod = filterDisabled
        handler
    }

    private TestUrlMappingsHandlerMapping deleteHandlerMapping() {
        def grailsApplication = new DefaultGrailsApplication(BookController).tap { initialise() }
        def holder = getUrlMappingsHolder {
            '/books/$id' {
                controller = 'book'
                action = 'delete'
                method = 'DELETE'
            }
        }
        def handler = new TestUrlMappingsHandlerMapping(new GrailsControllerUrlMappings(grailsApplication, holder))
        handler.resolveHiddenHttpMethod = true
        handler
    }

    private static HttpServletRequest request(String method, String uri, String override, String dispatchAttribute) {
        def webRequest = GrailsWebMockUtil.bindMockWebRequest()
        def request = webRequest.request as MockHttpServletRequest
        request.method = method
        request.requestURI = uri
        request.addParameter('_method', override)
        request.setAttribute(dispatchAttribute, uri)
        request
    }

    private static UrlMappingInfo match(UrlMappingsHandlerMapping handler, String method, String uri, String override,
                                        String dispatchAttribute = null) {
        def webRequest = GrailsWebMockUtil.bindMockWebRequest()
        def request = webRequest.request as MockHttpServletRequest
        request.method = method
        request.requestURI = uri
        if (override) {
            request.addParameter('_method', override)
        }
        if (dispatchAttribute) {
            request.setAttribute(dispatchAttribute, uri)
        }
        handler.getHandler(request)?.handler as UrlMappingInfo
    }
}

class TestUrlMappingsHandlerMapping extends UrlMappingsHandlerMapping {

    TestUrlMappingsHandlerMapping(UrlMappingsHolder urlMappingsHolder) {
        super(urlMappingsHolder)
    }

    String callResolveHttpMethod(HttpServletRequest request) {
        resolveHttpMethod(request)
    }
}

@Artefact('Controller')
class BookController {
    @Action def index() {}
    @Action def show() {}
    @Action def create() {}
    @Action def save() {}
    @Action def edit() {}
    @Action def update() {}
    @Action def patch() {}
    @Action def delete() {}
}

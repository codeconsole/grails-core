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

import grails.artefact.Artefact
import grails.core.DefaultGrailsApplication
import grails.util.GrailsWebMockUtil
import grails.web.Action
import grails.web.mapping.AbstractUrlMappingsSpec
import grails.web.mapping.UrlMappingInfo

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

        expect: 'a GET override is refused, so a POST cannot be turned into a read'
        match(handler, 'POST', '/books/1', 'GET') == null
        match(handler, 'POST', '/books/1', 'TRACE') == null
    }

    void 'a POST without the parameter is unaffected'() {
        given:
        def handler = bookHandlerMapping(true)

        expect: 'the collection URL still routes to save'
        match(handler, 'POST', '/books', null).actionName == 'save'

        and: 'and the member URL still matches nothing'
        match(handler, 'POST', '/books/1', null) == null
    }

    void 'a real DELETE is unaffected'() {
        given:
        def handler = bookHandlerMapping(true)

        expect: 'an API client sending the real method routes as it always did'
        match(handler, 'DELETE', '/books/1', null).actionName == 'delete'
    }

    private UrlMappingsHandlerMapping bookHandlerMapping(boolean resolveHiddenHttpMethod) {
        def grailsApplication = new DefaultGrailsApplication(BookController)
        grailsApplication.initialise()
        def holder = getUrlMappingsHolder {
            "/books"(resources: 'book')
        }
        def handler = new UrlMappingsHandlerMapping(new GrailsControllerUrlMappings(grailsApplication, holder))
        handler.resolveHiddenHttpMethod = resolveHiddenHttpMethod
        handler
    }

    private static UrlMappingInfo match(UrlMappingsHandlerMapping handler, String method, String uri, String override) {
        def webRequest = GrailsWebMockUtil.bindMockWebRequest()
        def request = webRequest.request
        request.method = method
        request.requestURI = uri
        if (override) {
            request.addParameter('_method', override)
        }
        handler.getHandler(request)?.handler as UrlMappingInfo
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

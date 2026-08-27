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
package org.grails.web.util

import org.springframework.mock.web.MockHttpServletRequest

import spock.lang.Specification
import spock.lang.Unroll

class HiddenHttpMethodSpec extends Specification {

    @Unroll
    void 'a POST naming #requested resolves to #expected'() {
        expect:
        HiddenHttpMethod.resolveOverride(post(requested)) == expected

        where:
        requested || expected
        'PUT'     || 'PUT'
        'PATCH'   || 'PATCH'
        'DELETE'  || 'DELETE'
        'delete'  || 'DELETE'
        'DeLeTe'  || 'DELETE'
    }

    @Unroll
    void 'a POST naming #requested is refused'() {
        expect: 'unlike the servlet filter, which applies any method name it is given'
        HiddenHttpMethod.resolveOverride(post(requested)) == null

        where:
        requested << ['GET', 'HEAD', 'OPTIONS', 'TRACE', 'POST', 'BOGUS', '', '   ']
    }

    void 'a POST without the parameter resolves to nothing'() {
        expect:
        HiddenHttpMethod.resolveOverride(new MockHttpServletRequest('POST', '/books/1')) == null
    }

    @Unroll
    void 'a #method request is never overridden'() {
        given:
        def request = new MockHttpServletRequest(method, '/books/1')
        request.addParameter('_method', 'DELETE')

        expect:
        HiddenHttpMethod.resolveOverride(request) == null

        where:
        method << ['GET', 'PUT', 'DELETE', 'HEAD']
    }

    void 'the header the servlet filter trusts is not read here'() {
        given: 'X-HTTP-Method-Override lets any client turn its own POST into a DELETE'
        def request = new MockHttpServletRequest('POST', '/books/1')
        request.addHeader('X-HTTP-Method-Override', 'DELETE')

        expect: 'only the form parameter is honoured'
        HiddenHttpMethod.resolveOverride(request) == null
    }

    void 'a wrapped request reports the overriding method and delegates everything else'() {
        given:
        def request = new MockHttpServletRequest('POST', '/books/1')
        request.addParameter('title', 'Red')

        when:
        def wrapped = HiddenHttpMethod.wrap('DELETE', request)

        then:
        wrapped.method == 'DELETE'
        wrapped.requestURI == '/books/1'
        wrapped.getParameter('title') == 'Red'
    }

    private static MockHttpServletRequest post(String requested) {
        def request = new MockHttpServletRequest('POST', '/books/1')
        request.addParameter('_method', requested)
        request
    }
}

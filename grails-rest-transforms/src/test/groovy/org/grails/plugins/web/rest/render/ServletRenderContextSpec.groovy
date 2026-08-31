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
package org.grails.plugins.web.rest.render

import spock.lang.Specification

import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext

import org.grails.web.servlet.mvc.GrailsWebRequest
import org.grails.web.util.HiddenHttpMethod

class ServletRenderContextSpec extends Specification {

    void 'the render context uses the method selected by the dispatcher'() {
        given: 'a request that has been overridden by the dispatcher to be a DELETE'
            def request = new MockHttpServletRequest('POST', '/books/1').tap {
                addParameter('_method', 'DELETE')
            }
            def response = new MockHttpServletResponse()
            def webRequest = new GrailsWebRequest(request, response, new MockServletContext())
            request.setAttribute(HiddenHttpMethod.OVERRIDDEN_METHOD_ATTRIBUTE, 'DELETE')

        when: 'the render context is created from the web request'
            def renderContext = new ServletRenderContext(webRequest)

        then: 'the render context reports the overridden method'
            renderContext.httpMethod == HttpMethod.DELETE
    }
}

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

import jakarta.servlet.http.HttpServletRequestWrapper

import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.mock.web.MockMultipartHttpServletRequest

import spock.lang.Issue
import spock.lang.Specification

/**
 * @Author Sudhir Nimavat
 */
class WebUtilsSpec extends Specification {

	@Issue("https://github.com/apache/grails-core/issues/10545")
	def testToQueryString() {
		given:
		Map params = ["name":"sudhir-nimavat", "address.zip":"12345"]

		when:
		String result = WebUtils.toQueryString(params)

		then:
		result.startsWith("?")
		def tokens = result[1..-1].split('&')
		tokens.find({ it == "name=sudhir-nimavat"}) != null
		tokens.find({ it == "address.zip=12345"}) != null
	}

    void 'resolveMultipartRequest returns the request itself when it is already a multipart request'() {
        given:
        def request = multipartRequest()

        expect:
        WebUtils.resolveMultipartRequest(request).is(request)
    }

    void 'resolveMultipartRequest unwraps a multipart request nested inside later request wrappers'() {
        given: 'the wrapper chain a request picks up from filters running after multipart resolution'
        def multipartRequest = multipartRequest()
        def outerRequest = new HttpServletRequestWrapper(new HttpServletRequestWrapper(multipartRequest))

        when:
        def resolved = WebUtils.resolveMultipartRequest(outerRequest)

        then: 'the multipart request is found without the outer wrappers being discarded'
        resolved.is(multipartRequest)
        resolved.getFile('file').originalFilename == 'test.txt'
    }

    void 'resolveMultipartRequest falls back to the published attribute when the multipart request cannot be unwrapped'() {
        given: 'the shape produced when the DispatcherServlet resolves a request Grails already bound'
        def request = new MockHttpServletRequest()
        def multipartRequest = multipartRequest()
        request.setAttribute(WebUtils.MULTIPART_HTTP_SERVLET_REQUEST_ATTRIBUTE, multipartRequest)

        expect:
        WebUtils.resolveMultipartRequest(request).is(multipartRequest)
    }

    void 'resolveMultipartRequest returns null for an ordinary request'() {
        expect:
        WebUtils.resolveMultipartRequest(new MockHttpServletRequest()) == null
    }

    void 'isMultipartContentType detects a multipart content type regardless of case or parameters'() {
        given:
        def request = new MockHttpServletRequest()
        request.contentType = contentType

        expect:
        WebUtils.isMultipartContentType(request) == expected

        where:
        contentType                                  || expected
        'multipart/form-data; boundary=test'         || true
        'MULTIPART/FORM-DATA'                        || true
        'multipart/mixed'                            || true
        'application/x-www-form-urlencoded'          || false
        null                                         || false
    }

    private static MockMultipartHttpServletRequest multipartRequest() {
        def request = new MockMultipartHttpServletRequest()
        request.contentType = 'multipart/form-data; boundary=test'
        request.addFile(new MockMultipartFile('file', 'test.txt', 'text/plain', 'content'.bytes))
        request
    }
}

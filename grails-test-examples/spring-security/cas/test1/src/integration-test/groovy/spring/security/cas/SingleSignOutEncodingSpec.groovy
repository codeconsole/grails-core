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
package spring.security.cas

import spock.lang.Issue
import spock.lang.Specification

import grails.testing.mixin.integration.Integration
import org.apache.grails.testing.http.client.HttpClientSupport
import spring.security.cas.test.Application

/**
 * The CAS single sign-out filter, on by default, reads the {@code logoutRequest} parameter of every POST. Once a
 * filter reads a parameter, the container has decoded the body, so the encoding filter has to run before it.
 */
@Integration(applicationClass = Application)
@Issue('https://github.com/apache/grails-core/issues/15921')
class SingleSignOutEncodingSpec extends Specification implements HttpClientSupport {

    void 'decodes a form post as UTF-8 although the single sign-out filter reads its parameters'() {
        when: 'a form is posted without a charset'
        def response = httpPost('/echo/form', 'text=Caf%C3%A9+%E2%80%93+%F0%9F%8D%82', 'application/x-www-form-urlencoded')

        then: 'the controller sees the text as it was typed'
        response.assertStatus(200)
        response.body() == 'Café – 🍂'
    }
}

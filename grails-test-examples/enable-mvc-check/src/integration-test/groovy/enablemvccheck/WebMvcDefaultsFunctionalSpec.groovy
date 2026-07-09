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
package enablemvccheck

import java.net.http.HttpRequest

import spock.lang.Specification
import spock.lang.Tag
import spock.lang.Unroll

import grails.testing.mixin.integration.Integration
import org.apache.grails.testing.http.client.HttpClientSupport

/**
 * Verifies the HTTP behavior of a Grails application whose Application class declares
 * {@code @EnableWebMvc} (this app declares it explicitly; Grails 7 auto-injected it).
 * The annotation suppresses Spring Boot's {@code WebMvcAutoConfiguration}, so:
 * <ul>
 *   <li>form-encoded bodies of {@code DELETE} requests are NOT parsed into request
 *       parameters (no form-content filter is registered; {@code PUT} and {@code PATCH}
 *       bodies are parsed by Grails itself in {@code GrailsParameterMap} regardless of
 *       the annotation, so they serve as controls)</li>
 *   <li>classpath resources such as {@code classpath:/public/*} are NOT served at the
 *       context root (no Boot catch-all static-resource handler)</li>
 *   <li>a static {@code index.html} is NOT served for the unmapped root path
 *       (no Boot {@code WelcomePageHandlerMapping})</li>
 * </ul>
 *
 * These assertions must hold whether {@code @EnableWebMvc} is auto-injected by the
 * framework (Grails 7) or declared explicitly by the application (Grails 8 onwards):
 * an application that opts in keeps exactly the previous behavior. Removing the
 * annotation from {@code Application} makes every non-control feature method here
 * fail, which is the documented Grails 8 behavior change.
 */
@Integration
@Tag('http-client')
class WebMvcDefaultsFunctionalSpec extends Specification implements HttpClientSupport {

    private static final String FORM_CONTENT_TYPE = 'application/x-www-form-urlencoded'
    private static final String FORM_BODY = 'title=The%20Stand&pages=1153'

    void 'a form-encoded DELETE body is not parsed into request parameters'() {
        when: 'a form-encoded body is sent with DELETE'
        def response = sendHttpRequest(newHttpRequestWith('/formContent/echo') {
            header('Content-Type', FORM_CONTENT_TYPE)
            method('DELETE', HttpRequest.BodyPublishers.ofString(FORM_BODY))
        })

        then: 'the action executes but the form fields are not visible as request parameters'
        response.assertStatus(200)
        def json = response.json()
        json.method == 'DELETE'
        json.title == null
        json.pages == null
    }

    @Unroll
    void 'a form-encoded #httpMethod body is parsed by Grails itself (control, unaffected by @EnableWebMvc)'() {
        when: 'a form-encoded body is sent with a method Grails parses in GrailsParameterMap'
        def response = sendHttpRequest(newHttpRequestWith('/formContent/echo') {
            header('Content-Type', FORM_CONTENT_TYPE)
            method(httpMethod, HttpRequest.BodyPublishers.ofString(FORM_BODY))
        })

        then: 'the form fields are visible as request parameters with or without a form-content filter'
        response.assertStatus(200)
        def json = response.json()
        json.method == httpMethod
        json.title == 'The Stand'
        json.pages == '1153'

        where:
        httpMethod << ['PUT', 'PATCH']
    }

    void 'a form-encoded POST body is parsed into request parameters (control, unaffected by @EnableWebMvc)'() {
        when:
        def response = httpPostForm('/formContent/echo', [title: 'The Stand', pages: 1153])

        then:
        response.assertStatus(200)
        def json = response.json()
        json.method == 'POST'
        json.title == 'The Stand'
        json.pages == '1153'
    }

    void 'a classpath:/public resource is not served at the context root'() {
        when: 'a static classpath resource that no URL mapping serves is requested at the root'
        def response = http('/boot-default-mapping.txt')

        then: 'the request falls through to URL-mapping error handling'
        response.assertStatus(404)
    }

    void 'no static index.html welcome page is served for the unmapped root path'() {
        when: 'the unmapped root path is requested'
        def response = http('/')

        then: 'the request falls through to URL-mapping error handling'
        response.assertStatus(404)
    }

    void 'locale resolution follows the Accept-Language header and the lang request parameter is ignored'() {
        // @EnableWebMvc registers an AcceptHeaderLocaleResolver ahead of auto-configuration, so
        // grails-i18n's SessionLocaleResolver backs off and Grails' ?lang= switching has no effect.
        // Without the annotation the session-based resolver is active and ?lang=de returns "de".
        // This intentionally differs from Grails 7 (where the i18n plugin overrode the annotation's
        // resolver via bean-definition overriding): framework beans now back off cleanly instead of
        // overriding. See upgrade notes section 31.4.
        when:
        def response = http(['Accept-Language': 'fr-FR'], '/locale/echo?lang=de')

        then:
        response.assertStatus(200)
        response.json().locale == 'fr_FR'
    }
}

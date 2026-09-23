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

package org.grails.plugins.web.controllers

import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.web.header.HeaderWriterFilter
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter

import spock.lang.Specification
import spock.lang.Unroll

class GrailsSecurityHeadersAutoConfigurationSpec extends Specification {

    private static final List<String> DEFAULT_HEADER_NAMES =
            ['X-Content-Type-Options', 'X-Frame-Options', 'Referrer-Policy', 'X-XSS-Protection']

    void 'default servlet web auto-configuration registers the security headers filter'() {
        expect:
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GrailsSecurityHeadersAutoConfiguration))
                .run { context ->
                    assert context.getBeanNamesForType(GrailsSecurityHeadersFilter).length == 1
                    assert context.getBean('grailsSecurityHeadersFilter') instanceof FilterRegistrationBean
                }
    }

    void 'security headers auto-configuration does not run for non-web applications'() {
        expect:
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GrailsSecurityHeadersAutoConfiguration))
                .run { context ->
                    assert context.getBeanNamesForType(GrailsSecurityHeadersFilter).length == 0
                }
    }

    void 'security headers auto-configuration can be disabled'() {
        expect:
        new WebApplicationContextRunner()
                .withPropertyValues('grails.security.headers.enabled=false')
                .withConfiguration(AutoConfigurations.of(GrailsSecurityHeadersAutoConfiguration))
                .run { context ->
                    assert context.getBeanNamesForType(GrailsSecurityHeadersFilter).length == 0
                }
    }

    void 'security headers auto-configuration stays active when Spring Security header writing is on the classpath'() {
        expect: 'the real HeaderWriterFilter class is on this spec\'s test classpath'
        HeaderWriterFilter != null
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GrailsSecurityHeadersAutoConfiguration))
                .run { context ->
                    assert context.getBeanNamesForType(GrailsSecurityHeadersFilter).length == 1
                }
    }

    void 'application-defined security headers filter makes the auto-configured filter back off'() {
        given:
        def userFilter = new GrailsSecurityHeadersFilter(new GrailsSecurityHeadersProperties())

        expect:
        new WebApplicationContextRunner()
                .withBean(GrailsSecurityHeadersFilter) { userFilter }
                .withConfiguration(AutoConfigurations.of(GrailsSecurityHeadersAutoConfiguration))
                .run { context ->
                    assert context.getBean(GrailsSecurityHeadersFilter).is(userFilter)
                    assert context.getBeanNamesForType(GrailsSecurityHeadersFilter).length == 1
                }
    }

    void 'application-defined security headers registration makes the raw filter back off'() {
        given:
        def userRegistration = new FilterRegistrationBean()

        expect:
        new WebApplicationContextRunner()
                .withBean('grailsSecurityHeadersFilter', FilterRegistrationBean) { userRegistration }
                .withConfiguration(AutoConfigurations.of(GrailsSecurityHeadersAutoConfiguration))
                .run { context ->
                    assert context.getBean('grailsSecurityHeadersFilter').is(userRegistration)
                    assert context.getBeanNamesForType(GrailsSecurityHeadersFilter).length == 0
                }
    }

    void 'default filter writes browser hardening headers and skips disabled optional headers'() {
        given:
        def request = new MockHttpServletRequest('GET', '/')
        def response = new MockHttpServletResponse()
        def filter = new GrailsSecurityHeadersFilter(new GrailsSecurityHeadersProperties())

        when:
        filter.doFilter(request, response, new MockFilterChain())

        then:
        response.getHeader('X-Content-Type-Options') == 'nosniff'
        response.getHeader('X-Frame-Options') == 'SAMEORIGIN'
        response.getHeader('Referrer-Policy') == 'strict-origin-when-cross-origin'
        response.getHeader('X-XSS-Protection') == '0'
        response.getHeader('Strict-Transport-Security') == null
        response.getHeader('Content-Security-Policy') == null
    }

    void 'default filter writes browser hardening headers for error dispatches'() {
        given:
        def request = new MockHttpServletRequest('GET', '/')
        request.dispatcherType = DispatcherType.ERROR
        def response = new MockHttpServletResponse()
        def filter = new GrailsSecurityHeadersFilter(new GrailsSecurityHeadersProperties())

        when:
        filter.doFilter(request, response, new MockFilterChain())

        then:
        response.getHeader('X-Content-Type-Options') == 'nosniff'
        response.getHeader('X-Frame-Options') == 'SAMEORIGIN'
        response.getHeader('Referrer-Policy') == 'strict-origin-when-cross-origin'
        response.getHeader('X-XSS-Protection') == '0'
    }

    void 'filter applies configured overrides and optional headers'() {
        given:
        def request = new MockHttpServletRequest('GET', '/')
        request.secure = true
        def response = new MockHttpServletResponse()
        def properties = new GrailsSecurityHeadersProperties()
        properties.frameOptions.value = 'DENY'
        properties.referrerPolicy.value = 'no-referrer-when-downgrade'
        properties.hsts.enabled = true
        properties.hsts.value = 'max-age=63072000; includeSubDomains'
        properties.contentSecurityPolicy.enabled = true
        properties.contentSecurityPolicy.value = "default-src 'self'"

        when:
        new GrailsSecurityHeadersFilter(properties).doFilter(request, response, new MockFilterChain())

        then:
        response.getHeader('X-Frame-Options') == 'DENY'
        response.getHeader('Referrer-Policy') == 'no-referrer-when-downgrade'
        response.getHeader('Strict-Transport-Security') == 'max-age=63072000; includeSubDomains'
        response.getHeader('Content-Security-Policy') == "default-src 'self'"
    }

    void 'filter respects per-header disable switches and existing response headers'() {
        given:
        def request = new MockHttpServletRequest('GET', '/')
        def response = new MockHttpServletResponse()
        response.setHeader('X-Frame-Options', 'DENY')
        def properties = new GrailsSecurityHeadersProperties()
        properties.contentTypeOptions.enabled = false
        properties.xssProtection.enabled = false

        when:
        new GrailsSecurityHeadersFilter(properties).doFilter(request, response, new MockFilterChain())

        then:
        response.getHeader('X-Content-Type-Options') == null
        response.getHeader('X-XSS-Protection') == null
        response.getHeader('X-Frame-Options') == 'DENY'
        response.getHeader('Referrer-Policy') == 'strict-origin-when-cross-origin'
    }

    void 'headers set downstream of the filter win over the Grails defaults'() {
        given:
        def request = new MockHttpServletRequest('GET', '/')
        def response = new MockHttpServletResponse()
        FilterChain downstream = { downstreamRequest, downstreamResponse ->
            downstreamResponse.setHeader('X-Frame-Options', 'DENY')
            downstreamResponse.setHeader('Referrer-Policy', 'no-referrer')
        } as FilterChain

        when:
        new GrailsSecurityHeadersFilter(new GrailsSecurityHeadersProperties()).doFilter(request, response, downstream)

        then:
        response.getHeader('X-Frame-Options') == 'DENY'
        response.getHeader('Referrer-Policy') == 'no-referrer'
        response.getHeader('X-Content-Type-Options') == 'nosniff'
        response.getHeader('X-XSS-Protection') == '0'
    }

    void 'Spring Security header writers running after the filter win and Grails fills the rest'() {
        given: 'Spring Security writes X-Frame-Options: DENY at commit time, as its HeaderWriterFilter does in a real chain'
        def request = new MockHttpServletRequest('GET', '/')
        def response = new MockHttpServletResponse()
        def springSecurity = new HeaderWriterFilter([
                new XFrameOptionsHeaderWriter(XFrameOptionsHeaderWriter.XFrameOptionsMode.DENY)
        ])
        def servlet = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse res) {
                res.writer.write('ok')
            }
        }

        when:
        new GrailsSecurityHeadersFilter(new GrailsSecurityHeadersProperties())
                .doFilter(request, response, new MockFilterChain(servlet, springSecurity))

        then:
        response.getHeader('X-Frame-Options') == 'DENY'
        response.getHeader('X-Content-Type-Options') == 'nosniff'
        response.getHeader('Referrer-Policy') == 'strict-origin-when-cross-origin'
        response.getHeader('X-XSS-Protection') == '0'
        response.contentAsString == 'ok'
    }

    void 'filter writes security headers before a downstream redirect commits the response'() {
        given:
        def request = new MockHttpServletRequest('GET', '/')
        def response = new MockHttpServletResponse()
        FilterChain downstream = { downstreamRequest, downstreamResponse ->
            downstreamResponse.sendRedirect('/target')
        } as FilterChain

        when:
        new GrailsSecurityHeadersFilter(new GrailsSecurityHeadersProperties()).doFilter(request, response, downstream)

        then:
        response.committed
        response.redirectedUrl == '/target'
        DEFAULT_HEADER_NAMES.every { response.getHeader(it) != null }
    }

    void 'filter writes security headers before a downstream sendError commits the response'() {
        given:
        def request = new MockHttpServletRequest('GET', '/')
        def response = new MockHttpServletResponse()
        FilterChain downstream = { downstreamRequest, downstreamResponse ->
            downstreamResponse.sendError(404, 'missing')
        } as FilterChain

        when:
        new GrailsSecurityHeadersFilter(new GrailsSecurityHeadersProperties()).doFilter(request, response, downstream)

        then:
        response.committed
        response.status == 404
        DEFAULT_HEADER_NAMES.every { response.getHeader(it) != null }
    }

    void 'filter writes security headers before a streaming downstream flushes the response'() {
        given:
        def request = new MockHttpServletRequest('GET', '/')
        def response = new MockHttpServletResponse()
        boolean headersPresentAtFlush = false
        FilterChain downstream = { downstreamRequest, downstreamResponse ->
            def out = downstreamResponse.outputStream
            out.write('chunk'.bytes)
            out.flush()
            headersPresentAtFlush = DEFAULT_HEADER_NAMES.every { response.getHeader(it) != null }
            out.write('more'.bytes)
        } as FilterChain

        when:
        new GrailsSecurityHeadersFilter(new GrailsSecurityHeadersProperties()).doFilter(request, response, downstream)

        then:
        response.committed
        headersPresentAtFlush
        response.contentAsString == 'chunkmore'
    }

    void 'filter writes security headers once the declared content length has been written'() {
        given:
        def request = new MockHttpServletRequest('GET', '/')
        def response = new MockHttpServletResponse()
        boolean headersPresentBeforeLastByte = false
        boolean headersPresentAfterLastByte = false
        FilterChain downstream = { downstreamRequest, downstreamResponse ->
            downstreamResponse.setContentLength(5)
            def writer = downstreamResponse.writer
            writer.write('hell')
            headersPresentBeforeLastByte = DEFAULT_HEADER_NAMES.any { response.getHeader(it) != null }
            writer.write('o')
            headersPresentAfterLastByte = DEFAULT_HEADER_NAMES.every { response.getHeader(it) != null }
        } as FilterChain

        when:
        new GrailsSecurityHeadersFilter(new GrailsSecurityHeadersProperties()).doFilter(request, response, downstream)

        then:
        !headersPresentBeforeLastByte
        headersPresentAfterLastByte
    }

    @Unroll
    void 'HSTS honors the forwarded scheme behind a TLS-terminating proxy: #headerName=#headerValue -> #expected'() {
        given:
        def request = new MockHttpServletRequest('GET', '/')
        request.secure = false
        request.addHeader(headerName, headerValue)
        def response = new MockHttpServletResponse()
        def properties = new GrailsSecurityHeadersProperties()
        properties.hsts.enabled = true

        when:
        new GrailsSecurityHeadersFilter(properties).doFilter(request, response, new MockFilterChain())

        then:
        (response.getHeader('Strict-Transport-Security') != null) == expected

        where:
        headerName          | headerValue                                  || expected
        'X-Forwarded-Proto' | 'https'                                      || true
        'X-Forwarded-Proto' | 'HTTPS'                                      || true
        'X-Forwarded-Proto' | 'https, http'                                || true
        'X-Forwarded-Proto' | 'http'                                       || false
        'X-Forwarded-Proto' | 'http, https'                                || false
        'Forwarded'         | 'for=192.0.2.60;proto=https;by=203.0.113.43' || true
        'Forwarded'         | 'for=192.0.2.60;proto="https"'               || true
        'Forwarded'         | 'for=192.0.2.60;proto=http, proto=https'     || false
        'Forwarded'         | 'for=192.0.2.60'                             || false
        'X-Forwarded-For'   | '192.0.2.60'                                 || false
    }

    void 'HSTS is not sent on an insecure request with no forwarded scheme'() {
        given:
        def request = new MockHttpServletRequest('GET', '/')
        request.secure = false
        def response = new MockHttpServletResponse()
        def properties = new GrailsSecurityHeadersProperties()
        properties.hsts.enabled = true

        when:
        new GrailsSecurityHeadersFilter(properties).doFilter(request, response, new MockFilterChain())

        then:
        response.getHeader('Strict-Transport-Security') == null
    }

    @Unroll
    void 'a request relayed through a reverse proxy (#headerName) suppresses the defaults but keeps explicit headers'() {
        given:
        def request = new MockHttpServletRequest('GET', '/')
        request.addHeader(headerName, 'proxy-value')
        def response = new MockHttpServletResponse()
        def properties = new GrailsSecurityHeadersProperties()
        properties.frameOptions.value = 'DENY'

        when:
        new GrailsSecurityHeadersFilter(properties).doFilter(request, response, new MockFilterChain())

        then:
        response.getHeader('X-Frame-Options') == 'DENY'
        response.getHeader('X-Content-Type-Options') == null
        response.getHeader('Referrer-Policy') == null
        response.getHeader('X-XSS-Protection') == null

        where:
        headerName << GrailsSecurityHeadersFilter.REVERSE_PROXY_REQUEST_HEADERS
    }

    void 'defaults=always applies the defaults even when a reverse proxy is detected'() {
        given:
        def request = new MockHttpServletRequest('GET', '/')
        request.addHeader('X-Forwarded-For', '192.0.2.60')
        def response = new MockHttpServletResponse()
        def properties = new GrailsSecurityHeadersProperties()
        properties.defaults = GrailsSecurityHeadersProperties.Defaults.ALWAYS

        when:
        new GrailsSecurityHeadersFilter(properties).doFilter(request, response, new MockFilterChain())

        then:
        DEFAULT_HEADER_NAMES.every { response.getHeader(it) != null }
    }

    void 'defaults=never sends only explicitly configured headers even without a proxy'() {
        given:
        def request = new MockHttpServletRequest('GET', '/')
        def response = new MockHttpServletResponse()
        def properties = new GrailsSecurityHeadersProperties()
        properties.defaults = GrailsSecurityHeadersProperties.Defaults.NEVER
        properties.referrerPolicy.enabled = true

        when:
        new GrailsSecurityHeadersFilter(properties).doFilter(request, response, new MockFilterChain())

        then:
        response.getHeader('Referrer-Policy') == 'strict-origin-when-cross-origin'
        response.getHeader('X-Content-Type-Options') == null
        response.getHeader('X-Frame-Options') == null
        response.getHeader('X-XSS-Protection') == null
    }

    void 'a filter told the deployment is behind a reverse proxy suppresses defaults without per-request signals'() {
        given:
        def request = new MockHttpServletRequest('GET', '/')
        def response = new MockHttpServletResponse()
        def properties = new GrailsSecurityHeadersProperties()
        properties.contentSecurityPolicy.enabled = true
        properties.contentSecurityPolicy.value = "default-src 'self'"

        when:
        new GrailsSecurityHeadersFilter(properties, true).doFilter(request, response, new MockFilterChain())

        then:
        response.getHeader('Content-Security-Policy') == "default-src 'self'"
        DEFAULT_HEADER_NAMES.every { response.getHeader(it) == null }
    }

    void 'headers are not marked explicit until the application configures them'() {
        given:
        def properties = new GrailsSecurityHeadersProperties()

        expect:
        !properties.contentTypeOptions.explicit
        !properties.frameOptions.explicit
        !properties.referrerPolicy.explicit
        !properties.xssProtection.explicit
        !properties.hsts.explicit
        !properties.contentSecurityPolicy.explicit

        when:
        properties.hsts.enabled = true
        properties.frameOptions.value = 'DENY'

        then:
        properties.hsts.explicit
        properties.frameOptions.explicit
        !properties.referrerPolicy.explicit
    }

    @Unroll
    void 'auto-configured filter treats every request as proxied when #property is set'() {
        expect:
        new WebApplicationContextRunner()
                .withPropertyValues(property, 'grails.security.headers.frame-options.value=DENY')
                .withConfiguration(AutoConfigurations.of(GrailsSecurityHeadersAutoConfiguration))
                .run { context ->
                    def response = new MockHttpServletResponse()
                    context.getBean(GrailsSecurityHeadersFilter)
                            .doFilter(new MockHttpServletRequest('GET', '/'), response, new MockFilterChain())
                    assert response.getHeader('X-Frame-Options') == 'DENY'
                    assert response.getHeader('X-Content-Type-Options') == null
                    assert response.getHeader('Referrer-Policy') == null
                    assert response.getHeader('X-XSS-Protection') == null
                }

        where:
        property << ['server.forward-headers-strategy=framework',
                     'server.forward-headers-strategy=native',
                     'spring.main.cloud-platform=kubernetes']
    }

    @Unroll
    void 'auto-configured filter applies defaults to a direct request when #description'() {
        expect:
        new WebApplicationContextRunner()
                .withPropertyValues(properties as String[])
                .withConfiguration(AutoConfigurations.of(GrailsSecurityHeadersAutoConfiguration))
                .run { context ->
                    def response = new MockHttpServletResponse()
                    context.getBean(GrailsSecurityHeadersFilter)
                            .doFilter(new MockHttpServletRequest('GET', '/'), response, new MockFilterChain())
                    assert DEFAULT_HEADER_NAMES.every { response.getHeader(it) != null }
                }

        where:
        description                           | properties
        'nothing proxy-related is configured' | []
        'forward-headers-strategy is none'    | ['server.forward-headers-strategy=none']
        'cloud platform is none'              | ['spring.main.cloud-platform=none']
    }

    void 'headers bound from application configuration are explicit and survive proxy detection'() {
        expect:
        new WebApplicationContextRunner()
                .withPropertyValues('grails.security.headers.hsts.enabled=true',
                        'grails.security.headers.content-security-policy.enabled=true',
                        "grails.security.headers.content-security-policy.value=default-src 'self'")
                .withConfiguration(AutoConfigurations.of(GrailsSecurityHeadersAutoConfiguration))
                .run { context ->
                    def request = new MockHttpServletRequest('GET', '/')
                    request.addHeader('X-Forwarded-Proto', 'https')
                    request.addHeader('X-Forwarded-For', '192.0.2.60')
                    def response = new MockHttpServletResponse()
                    context.getBean(GrailsSecurityHeadersFilter).doFilter(request, response, new MockFilterChain())
                    assert response.getHeader('Strict-Transport-Security') == 'max-age=31536000'
                    assert response.getHeader('Content-Security-Policy') == "default-src 'self'"
                    assert DEFAULT_HEADER_NAMES.every { response.getHeader(it) == null }
                }
    }

    void 'the defaults mode binds from application configuration'() {
        expect:
        new WebApplicationContextRunner()
                .withPropertyValues('grails.security.headers.defaults=always')
                .withConfiguration(AutoConfigurations.of(GrailsSecurityHeadersAutoConfiguration))
                .run { context ->
                    assert context.getBean(GrailsSecurityHeadersProperties).defaults ==
                            GrailsSecurityHeadersProperties.Defaults.ALWAYS
                    def request = new MockHttpServletRequest('GET', '/')
                    request.addHeader('Via', '1.1 proxy')
                    def response = new MockHttpServletResponse()
                    context.getBean(GrailsSecurityHeadersFilter).doFilter(request, response, new MockFilterChain())
                    assert DEFAULT_HEADER_NAMES.every { response.getHeader(it) != null }
                }
    }
}

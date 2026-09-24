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

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpFilter
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import org.apache.catalina.Context
import org.apache.catalina.startup.Tomcat
import org.apache.tomcat.util.descriptor.web.FilterDef
import org.apache.tomcat.util.descriptor.web.FilterMap

import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Runs {@link GrailsSecurityHeadersFilter} in front of servlets on embedded Tomcat. The Spring
 * mocks accept header writes after the response has committed, so only a real container can
 * show that the headers are on the wire for every commit path: the body outgrowing the
 * response buffer, a declared Content-Length reached through println, redirects, errors,
 * and a filter that serves the response itself without continuing the chain.
 */
class GrailsSecurityHeadersFilterTomcatSpec extends Specification {

    private static final List<String> DEFAULT_HEADER_NAMES =
            ['X-Content-Type-Options', 'X-Frame-Options', 'Referrer-Policy', 'X-XSS-Protection']

    private static final int LINE_SEPARATOR_LENGTH = System.lineSeparator().length()

    @Shared
    @AutoCleanup('stop')
    Tomcat tomcat

    @Shared
    int port

    @Shared
    HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    void setupSpec() {
        tomcat = new Tomcat()
        tomcat.baseDir = Files.createTempDirectory('security-headers-tomcat').toString()
        tomcat.setPort(0)
        tomcat.connector
        Context context = tomcat.addContext('', Files.createTempDirectory('security-headers-docbase').toString())

        Tomcat.addServlet(context, 'smallStream', new BodyServlet(100, false))
        context.addServletMappingDecoded('/small-stream', 'smallStream')
        Tomcat.addServlet(context, 'largeStream', new BodyServlet(50 * 1024, false))
        context.addServletMappingDecoded('/large-stream', 'largeStream')
        Tomcat.addServlet(context, 'largeWriter', new BodyServlet(50 * 1024, true))
        context.addServletMappingDecoded('/large-writer', 'largeWriter')
        Tomcat.addServlet(context, 'println', new PrintlnServlet())
        context.addServletMappingDecoded('/println', 'println')
        Tomcat.addServlet(context, 'redirect', new RedirectServlet())
        context.addServletMappingDecoded('/redirect', 'redirect')
        Tomcat.addServlet(context, 'error', new ErrorServlet())
        context.addServletMappingDecoded('/error', 'error')
        Tomcat.addServlet(context, 'unreachable', new BodyServlet(1, false))
        context.addServletMappingDecoded('/assets/*', 'unreachable')

        addFilter(context, 'grailsSecurityHeadersFilter',
                new GrailsSecurityHeadersFilter(new GrailsSecurityHeadersProperties()), '/*')
        addFilter(context, 'assetFilter', new ServeAssetWithoutChainFilter(), '/assets/*')

        tomcat.start()
        port = tomcat.connector.localPort
    }

    private static void addFilter(Context context, String name, jakarta.servlet.Filter filter, String pattern) {
        def filterDef = new FilterDef(filterName: name, filter: filter)
        context.addFilterDef(filterDef)
        def filterMap = new FilterMap(filterName: name)
        filterMap.addURLPatternDecoded(pattern)
        filterMap.setDispatcher(DispatcherType.REQUEST.name())
        filterMap.setDispatcher(DispatcherType.ERROR.name())
        context.addFilterMap(filterMap)
    }

    private HttpResponse<byte[]> get(String path) {
        client.send(HttpRequest.newBuilder(URI.create("http://localhost:${port}${path}")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray())
    }

    @Unroll
    void 'headers are on the wire for #path (#description)'() {
        when:
        def response = get(path)

        then:
        response.statusCode() == status
        DEFAULT_HEADER_NAMES.every { response.headers().firstValue(it).present }
        response.headers().firstValue('X-Content-Type-Options').get() == 'nosniff'
        response.headers().firstValue('X-Frame-Options').get() == 'SAMEORIGIN'
        bodyLength == null || response.body().length == bodyLength

        where:
        path            | description                                             | status | bodyLength
        '/small-stream' | 'body below the response buffer'                        | 200    | 100
        '/large-stream' | 'output stream body outgrowing the buffer, no flush'    | 200    | 50 * 1024
        '/large-writer' | 'writer body outgrowing the buffer, no flush'           | 200    | 50 * 1024
        '/println'      | 'Content-Length reached by println'                     | 200    | 2 + LINE_SEPARATOR_LENGTH
        '/redirect'     | 'redirect committed inside the chain'                   | 302    | null
        '/error'        | 'sendError committed inside the chain'                  | 404    | null
        '/assets/a.js'  | 'served by an inner filter that never continues the chain' | 200 | 14
    }

    void 'a header set by the servlet wins over the Grails default'() {
        when:
        def response = get('/println')

        then:
        response.headers().firstValue('Referrer-Policy').get() == 'no-referrer'
    }

    private static class BodyServlet extends HttpServlet {

        private final int size
        private final boolean useWriter

        BodyServlet(int size, boolean useWriter) {
            this.size = size
            this.useWriter = useWriter
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            response.contentType = 'text/plain'
            if (useWriter) {
                response.writer.write('x' * size)
            }
            else {
                response.outputStream.write(('x' * size).bytes)
            }
        }
    }

    private static class PrintlnServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            response.contentType = 'text/plain'
            response.setHeader('Referrer-Policy', 'no-referrer')
            response.setContentLength(2 + System.lineSeparator().length())
            response.writer.println('hi')
        }
    }

    private static class RedirectServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            response.sendRedirect('/small-stream')
        }
    }

    private static class ErrorServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            response.sendError(404, 'missing')
        }
    }

    /**
     * Mirrors the asset-pipeline filter: on a hit it streams the asset and flushes without
     * ever calling {@code chain.doFilter}.
     */
    private static class ServeAssetWithoutChainFilter extends HttpFilter {

        @Override
        protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
            response.contentType = 'application/javascript'
            response.outputStream.write('console.log(1)'.bytes)
            response.flushBuffer()
        }
    }
}

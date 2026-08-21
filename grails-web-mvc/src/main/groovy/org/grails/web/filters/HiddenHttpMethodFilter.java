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
package org.grails.web.filters;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import org.grails.web.util.WebUtils;

/**
 * Based off the Spring implementation, but also supports the X-HTTP-Method-Override HTTP header.
 *
 * @see org.springframework.web.filter.HiddenHttpMethodFilter
 *
 * @author Graeme Rocher
 * @since 1.2
 */
public class HiddenHttpMethodFilter extends OncePerRequestFilter {

    /** Default method parameter: <code>_method</code> */
    public static final String DEFAULT_METHOD_PARAM = "_method";

    private String methodParam = DEFAULT_METHOD_PARAM;
    public static final String HEADER_X_HTTP_METHOD_OVERRIDE = "X-HTTP-Method-Override";

    private long maxMultipartRequestSize = -1L;

    /**
     * Set the parameter name to look for HTTP methods.
     * @see #DEFAULT_METHOD_PARAM
     */
    public void setMethodParam(String methodParam) {
        Assert.hasText(methodParam, "'methodParam' must not be empty");
        this.methodParam = methodParam;
    }

    /**
     * Set the configured multipart request size limit, so a request already declared larger than
     * that limit (via its {@code Content-Length} header) never has its parameters read here. Reading
     * parameters on a multipart request makes the container parse it, which fails for a request
     * that breaches this limit; skipping the read avoids attempting a parse this filter already
     * knows the container will reject.
     * <p>
     * A negative value (the default) disables the check, so the parameter is always read.
     */
    public void setMaxMultipartRequestSize(long maxMultipartRequestSize) {
        this.maxMultipartRequestSize = maxMultipartRequestSize;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if ("POST".equalsIgnoreCase(request.getMethod())) {
            String httpMethod = getHttpMethodOverride(request);
            if (StringUtils.hasLength(httpMethod)) {
                filterChain.doFilter(new HttpMethodRequestWrapper(httpMethod, request), response);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    protected String getHttpMethodOverride(HttpServletRequest request) {
        // g:uploadForm(method: 'PUT') posts the override as a multipart part, so this read makes the
        // container parse the parts - and fail when they breach the upload limits. A request already
        // declared (via Content-Length) larger than the configured limit skips the read outright, so
        // the container is never asked to parse a body it will reject. Anything the size check can't
        // rule out up front (chunked transfer, no Content-Length) still goes through the tolerant read,
        // so the failure surfaces during dispatch rather than aborting the filter chain, where no
        // HandlerExceptionResolver could see it. See WebUtils.readParameter.
        String httpMethod = exceedsMaxMultipartRequestSize(request) ? null : WebUtils.readParameter(request, methodParam);

        if (httpMethod == null) {
            httpMethod = request.getHeader(HEADER_X_HTTP_METHOD_OVERRIDE);
        }
        return httpMethod == null ? null : httpMethod.toUpperCase();
    }

    private boolean exceedsMaxMultipartRequestSize(HttpServletRequest request) {
        if (maxMultipartRequestSize < 0) {
            return false;
        }
        String contentType = request.getContentType();
        boolean multipart = contentType != null && contentType.regionMatches(true, 0, "multipart/", 0, "multipart/".length());
        return multipart && request.getContentLengthLong() > maxMultipartRequestSize;
    }

    /**
     * Simple {@link HttpServletRequest} wrapper that returns the supplied method for
     * {@link HttpServletRequest#getMethod()}.
     */
    protected static class HttpMethodRequestWrapper extends HttpServletRequestWrapper {

        private final String method;

        public HttpMethodRequestWrapper(String method, HttpServletRequest request) {
            super(request);
            this.method = method;
        }

        @Override
        public String getMethod() {
            return method;
        }
    }
}

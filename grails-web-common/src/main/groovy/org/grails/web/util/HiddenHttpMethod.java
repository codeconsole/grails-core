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
package org.grails.web.util;

import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import org.springframework.core.env.PropertyResolver;
import org.springframework.http.HttpMethod;

import grails.config.Settings;

/**
 * Resolves the hidden HTTP method override a browser form requests through a {@code _method} parameter.
 *
 * <p>Browsers submit only {@code GET} and {@code POST}, so a form that needs to reach a {@code PUT},
 * {@code PATCH} or {@code DELETE} route names the method in a request parameter instead. This is the same
 * convention {@code org.grails.web.filters.HiddenHttpMethodFilter} implements, applied inside the dispatcher
 * rather than ahead of it — deliberately narrower than the filter, which accepts any method name and also
 * trusts an {@code X-HTTP-Method-Override} header.
 *
 * @since 8.0
 */
public final class HiddenHttpMethod {

    /** Default method parameter: <code>_method</code> */
    public static final String DEFAULT_METHOD_PARAM = "_method";

    /** Spring Boot's equivalent of {@link Settings#WEB_HIDDEN_METHOD_FILTER_ENABLED}, also false by default. */
    public static final String SPRING_FILTER_ENABLED = "spring.mvc.hiddenmethod.filter.enabled";

    /**
     * The only methods a form may ask for: the three a browser cannot submit itself. Matches the set
     * Spring's own {@code HiddenHttpMethodFilter} permits, so a POST can never be turned into a GET.
     */
    private static final List<String> OVERRIDABLE_METHODS =
            List.of(HttpMethod.PUT.name(), HttpMethod.PATCH.name(), HttpMethod.DELETE.name());

    private HiddenHttpMethod() {
    }

    /**
     * Whether a servlet filter rewrites the request method, rather than it being resolved inside the
     * dispatcher. True when either this application or Spring Boot has asked for a filter.
     * <p>
     * Whenever this returns true a filter really is on the chain: Grails contributes its own whenever Boot's
     * is absent, which is the case for an application declaring {@code @EnableWebMvc}, since that backs off
     * {@code WebMvcAutoConfiguration} and with it the filter it would have registered. Callers can therefore
     * rely on this without inspecting the context for a filter bean.
     *
     * @param properties the environment or configuration to read
     * @return true when a servlet filter performs the override
     */
    public static boolean isServletFilterMode(PropertyResolver properties) {
        return properties.getProperty(Settings.WEB_HIDDEN_METHOD_FILTER_ENABLED, Boolean.class, Boolean.FALSE) ||
                properties.getProperty(SPRING_FILTER_ENABLED, Boolean.class, Boolean.FALSE);
    }

    /**
     * The method this request asks to be treated as, or {@code null} when it asks for nothing: it is not a
     * POST, carries no {@code _method} parameter, or names a method that may not be requested this way.
     *
     * @param request the current request
     * @return the overriding method name in upper case, or {@code null}
     */
    public static String resolveOverride(HttpServletRequest request) {
        if (!HttpMethod.POST.name().equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        String requested = request.getParameter(DEFAULT_METHOD_PARAM);
        if (requested == null || requested.isEmpty()) {
            return null;
        }
        String candidate = requested.toUpperCase(Locale.ENGLISH);
        return OVERRIDABLE_METHODS.contains(candidate) ? candidate : null;
    }

    /**
     * Wraps a request so that {@link HttpServletRequest#getMethod()} reports the overriding method, leaving
     * everything else delegating to the wrapped request.
     *
     * @param method the overriding method name
     * @param request the request to wrap
     * @return the wrapped request
     */
    public static HttpServletRequest wrap(String method, HttpServletRequest request) {
        return new HttpMethodRequestWrapper(method, request);
    }

    private static final class HttpMethodRequestWrapper extends HttpServletRequestWrapper {

        private final String method;

        private HttpMethodRequestWrapper(String method, HttpServletRequest request) {
            super(request);
            this.method = method;
        }

        @Override
        public String getMethod() {
            return this.method;
        }
    }
}

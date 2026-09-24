/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.grails.plugins.web.controllers;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies baseline browser hardening response headers.
 *
 * <p>Headers are written immediately before the response commits (or when the filter
 * chain returns, whichever comes first) and only when nothing further down the chain
 * has already set them, so a controller, an interceptor, another filter or Spring
 * Security's header writers always win over the Grails defaults.</p>
 *
 * <p>When {@code grails.security.headers.defaults} is {@code auto} and the request is
 * detected as having come through a reverse proxy, the built-in default values are
 * suppressed and only headers the application configured explicitly are applied, for
 * deployments where the proxy owns these headers and the application cannot see what it
 * adds. See {@link GrailsSecurityHeadersProperties.Defaults} for the controlling
 * setting.</p>
 *
 * @since 8.0
 */
public class GrailsSecurityHeadersFilter extends OncePerRequestFilter {

    /**
     * Request headers whose presence indicates the request was relayed by a reverse
     * proxy or load balancer.
     */
    public static final List<String> REVERSE_PROXY_REQUEST_HEADERS = List.of(
            "Forwarded", "X-Forwarded-For", "X-Forwarded-Proto", "X-Forwarded-Host", "Via", "X-Real-IP");

    private static final Logger logger = LoggerFactory.getLogger(GrailsSecurityHeadersFilter.class);

    private static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";

    private static final String FORWARDED = "Forwarded";

    private static final String HTTPS = "https";

    private final GrailsSecurityHeadersProperties properties;

    private final boolean reverseProxyConfigured;

    private final AtomicBoolean reverseProxyLogged = new AtomicBoolean();

    public GrailsSecurityHeadersFilter(GrailsSecurityHeadersProperties properties) {
        this(properties, false);
    }

    /**
     * @param properties the header configuration
     * @param reverseProxyConfigured whether the deployment is known to sit behind a reverse
     * proxy from configuration alone (for example a forwarded-headers strategy or an active
     * cloud platform), independent of any per-request signal
     */
    public GrailsSecurityHeadersFilter(GrailsSecurityHeadersProperties properties, boolean reverseProxyConfigured) {
        this.properties = properties;
        this.reverseProxyConfigured = reverseProxyConfigured;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        boolean applyDefaults = shouldApplyDefaults(request);
        boolean secure = isSecure(request);
        SecurityHeadersResponseWrapper wrapped = new SecurityHeadersResponseWrapper(response,
                () -> writeHeaders(response, applyDefaults, secure));
        try {
            filterChain.doFilter(request, wrapped);
        }
        finally {
            wrapped.beforeCommit();
        }
    }

    private void writeHeaders(HttpServletResponse response, boolean applyDefaults, boolean secure) {
        applyHeader(response, "X-Content-Type-Options", properties.getContentTypeOptions(), applyDefaults);
        applyHeader(response, "X-Frame-Options", properties.getFrameOptions(), applyDefaults);
        applyHeader(response, "Referrer-Policy", properties.getReferrerPolicy(), applyDefaults);
        applyHeader(response, "X-XSS-Protection", properties.getXssProtection(), applyDefaults);
        if (secure) {
            applyHeader(response, "Strict-Transport-Security", properties.getHsts(), applyDefaults);
        }
        applyHeader(response, "Content-Security-Policy", properties.getContentSecurityPolicy(), applyDefaults);
    }

    private boolean shouldApplyDefaults(HttpServletRequest request) {
        return switch (properties.getDefaults()) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> !isBehindReverseProxy(request);
        };
    }

    private boolean isBehindReverseProxy(HttpServletRequest request) {
        String signal = this.reverseProxyConfigured ? "server configuration" : detectReverseProxyHeader(request);
        if (signal == null) {
            return false;
        }
        if (this.reverseProxyLogged.compareAndSet(false, true)) {
            logger.info("Reverse proxy detected via {} and grails.security.headers.defaults is 'auto': the default " +
                    "Grails security headers are not applied and only explicitly configured " +
                    "grails.security.headers.* values are sent. Set grails.security.headers.defaults to 'always' " +
                    "to apply the defaults behind the proxy, or to 'never' to rely solely on explicit " +
                    "configuration.", signal);
        }
        return true;
    }

    private static String detectReverseProxyHeader(HttpServletRequest request) {
        for (String name : REVERSE_PROXY_REQUEST_HEADERS) {
            if (StringUtils.hasText(request.getHeader(name))) {
                return name + " request header";
            }
        }
        return null;
    }

    /**
     * Whether the client connection is secure. Falls back to the forwarded scheme when the
     * container itself saw plain HTTP, which is the case behind a TLS-terminating proxy that
     * has not been configured through {@code server.forward-headers-strategy}. Trusting the
     * forwarded scheme is safe for HSTS: user agents ignore a
     * {@code Strict-Transport-Security} header received over a non-secure transport
     * (RFC 6797, section 8.1), so a spoofed header on a plain connection has no effect.
     */
    private static boolean isSecure(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String forwardedProto = firstToken(request.getHeader(X_FORWARDED_PROTO), ',');
        if (forwardedProto != null) {
            return HTTPS.equalsIgnoreCase(forwardedProto);
        }
        return HTTPS.equalsIgnoreCase(forwardedProtoParameter(request.getHeader(FORWARDED)));
    }

    /**
     * Extracts the {@code proto} parameter of the first RFC 7239 {@code Forwarded} element.
     */
    private static String forwardedProtoParameter(String forwarded) {
        String firstElement = firstToken(forwarded, ',');
        if (firstElement == null) {
            return null;
        }
        for (String pair : firstElement.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "proto".equals(pair.substring(0, eq).trim().toLowerCase(Locale.ROOT))) {
                return unquote(pair.substring(eq + 1).trim());
            }
        }
        return null;
    }

    private static String firstToken(String value, char separator) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        int end = value.indexOf(separator);
        String token = (end < 0 ? value : value.substring(0, end)).trim();
        return token.isEmpty() ? null : token;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static void applyHeader(HttpServletResponse response, String name,
            GrailsSecurityHeadersProperties.Header header, boolean applyDefaults) {
        if (header == null || !header.isEnabled() || !StringUtils.hasText(header.getValue())) {
            return;
        }
        if (!applyDefaults && !header.isExplicit()) {
            return;
        }
        if (!response.containsHeader(name)) {
            response.setHeader(name, header.getValue());
        }
    }
}

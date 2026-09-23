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
package org.grails.plugins.web.controllers;

import java.util.EnumSet;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import org.grails.web.config.http.GrailsFilters;

/**
 * Registers {@link GrailsSecurityHeadersFilter} to apply baseline browser-hardening
 * response headers. The filter writes at response commit time and only fills headers
 * that are still absent, so it coexists with Spring Security's header writers and any
 * other filter or controller that sets these headers itself.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty(name = "grails.security.headers.enabled", matchIfMissing = true)
@EnableConfigurationProperties(GrailsSecurityHeadersProperties.class)
public class GrailsSecurityHeadersAutoConfiguration {

    static final String FORWARD_HEADERS_STRATEGY = "server.forward-headers-strategy";

    @Bean
    @ConditionalOnMissingBean(value = GrailsSecurityHeadersFilter.class, name = "grailsSecurityHeadersFilter")
    public GrailsSecurityHeadersFilter securityHeadersFilter(GrailsSecurityHeadersProperties properties,
            Environment environment) {
        return new GrailsSecurityHeadersFilter(properties, isReverseProxyConfigured(environment));
    }

    /**
     * Whether the deployment declares itself to be behind a reverse proxy: either a
     * forwarded-headers strategy is configured (in which case the forwarding filter or
     * valve strips the forwarded request headers before this filter could see them), or
     * Spring Boot detected a cloud platform, where ingress through a proxy is the norm.
     */
    static boolean isReverseProxyConfigured(Environment environment) {
        String strategy = environment.getProperty(FORWARD_HEADERS_STRATEGY);
        if (strategy != null && !"none".equalsIgnoreCase(strategy.trim())) {
            return true;
        }
        CloudPlatform platform = CloudPlatform.getActive(environment);
        return platform != null && platform != CloudPlatform.NONE;
    }

    @Bean
    @ConditionalOnMissingBean(name = "grailsSecurityHeadersFilter")
    public FilterRegistrationBean<GrailsSecurityHeadersFilter> grailsSecurityHeadersFilter(
            GrailsSecurityHeadersFilter securityHeadersFilter) {
        FilterRegistrationBean<GrailsSecurityHeadersFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(securityHeadersFilter);
        registrationBean.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD,
                DispatcherType.INCLUDE, DispatcherType.ERROR));
        registrationBean.addUrlPatterns("/*");
        registrationBean.setOrder(GrailsFilters.LAST.getOrder());
        return registrationBean;
    }
}

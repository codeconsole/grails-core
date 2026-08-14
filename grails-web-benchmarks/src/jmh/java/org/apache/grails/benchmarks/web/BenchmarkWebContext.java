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
package org.apache.grails.benchmarks.web;

import jakarta.servlet.ServletContext;

import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.StaticWebApplicationContext;

import grails.core.DefaultGrailsApplication;
import grails.core.GrailsApplication;
import org.grails.web.util.GrailsApplicationAttributes;

/**
 * Shared fixture for the request-processing benchmarks.
 *
 * <p>A {@link StaticWebApplicationContext} is registered into the {@link MockServletContext}
 * under the attribute Grails looks the context up by, so that
 * {@code DefaultGrailsApplicationAttributes} resolves a real {@code ApplicationContext} and the
 * benchmarks exercise the normal code path instead of the "no application context" error path.</p>
 */
public final class BenchmarkWebContext {

    private BenchmarkWebContext() {
    }

    /**
     * @return a {@link MockServletContext} with a refreshed web application context - containing a
     * {@link GrailsApplication} - bound to it the way a running Grails application would bind one
     */
    public static MockServletContext newServletContext() {
        MockServletContext servletContext = new MockServletContext();

        StaticWebApplicationContext applicationContext = new StaticWebApplicationContext();
        applicationContext.setServletContext(servletContext);
        applicationContext.refresh();
        applicationContext.getBeanFactory().registerSingleton(GrailsApplication.APPLICATION_ID, new DefaultGrailsApplication());

        servletContext.setAttribute(GrailsApplicationAttributes.APPLICATION_CONTEXT, applicationContext);
        servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, applicationContext);
        return servletContext;
    }

    /**
     * @param servletContext the servlet context created by {@link #newServletContext()}
     * @return the web application context bound to the given servlet context
     */
    public static WebApplicationContext applicationContext(ServletContext servletContext) {
        return (WebApplicationContext) servletContext.getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE);
    }
}

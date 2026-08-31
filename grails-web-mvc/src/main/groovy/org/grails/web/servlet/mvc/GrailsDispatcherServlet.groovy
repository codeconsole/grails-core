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
package org.grails.web.servlet.mvc

import groovy.transform.CompileStatic

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import jakarta.servlet.ServletContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import org.springframework.context.ApplicationContext
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.context.ServletContextAware
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.multipart.MultipartException
import org.springframework.web.servlet.DispatcherServlet

import grails.util.Holders
import org.grails.web.context.ServletEnvironmentGrailsApplicationDiscoveryStrategy
import org.grails.web.util.HiddenHttpMethod
import org.grails.web.util.WebUtils

/**
 * Simple extension to the Spring {@link DispatcherServlet} implementation that makes sure a {@link GrailsWebRequest} is bound
 *
 * @author Graeme Rocher
 * @since 3.0
 */
@CompileStatic
class GrailsDispatcherServlet extends DispatcherServlet implements ServletContextAware {

    private volatile ObservationRegistry observationRegistry

    GrailsDispatcherServlet() {
    }

    GrailsDispatcherServlet(WebApplicationContext webApplicationContext) {
        super(webApplicationContext)
    }

    @Override
    protected ServletRequestAttributes buildRequestAttributes(HttpServletRequest request, HttpServletResponse response, RequestAttributes previousAttributes) {
        if (previousAttributes == null || !(previousAttributes instanceof GrailsWebRequest)) {
            return buildGrailsWebRequest(request, response)
        }
        else {
            GrailsWebRequest webRequest = (GrailsWebRequest) previousAttributes
            if (webRequest.isActive()) {
                return webRequest
            }
            else {
                return buildGrailsWebRequest(request, response)
            }
        }
    }

    protected GrailsWebRequest buildGrailsWebRequest(HttpServletRequest request, HttpServletResponse response) {
        def webRequest = new GrailsWebRequest(request, response, request.getServletContext())
        webRequest.informParameterCreationListeners()
        return webRequest
    }

    /**
     * Whether a POST carrying a "_method" parameter should be treated as the method it names. Set when the
     * hidden HTTP method filter is disabled, so browser forms keep working while the override moves inside
     * the dispatcher.
     */
    boolean resolveHiddenHttpMethod = false

    @Override
    protected HttpServletRequest checkMultipart(HttpServletRequest request) throws MultipartException {
        HttpServletRequest currentRequest = request
        boolean shouldProcessMultiPart = !WebUtils.isError(request) && !WebUtils.isForwardOrInclude(request)
        if (shouldProcessMultiPart) {
            HttpServletRequest processedRequest = super.checkMultipart(request)
            if (!processedRequest.is(request)) {
                // The GrailsWebRequest was bound by GrailsWebRequestFilter, so the request it holds sits
                // below this wrapper and cannot reach it by unwrapping. Publish it so params and
                // request.getFile(..) can find it, then hand it to the dispatch like Spring MVC expects.
                currentRequest = processedRequest
                request.setAttribute(WebUtils.MULTIPART_HTTP_SERVLET_REQUEST_ATTRIBUTE, processedRequest)
                GrailsWebRequest.lookup(request)?.multipartRequestResolved()
            }
        }

        // Resolve the hidden method override here rather than in a servlet filter. Running after multipart
        // resolution means a multipart body is parsed once, by the dispatcher, instead of being forced open
        // by a getParameter() call ahead of it; running after the filter chain means Spring Security and any
        // other filter still see the request's real POST method.
        //
        // The override is recorded as a request attribute and the wrapper handed to the dispatch. What
        // routes on it reads the attribute -- URL mapping resolution and allowedMethods, through
        // HiddenHttpMethod.effectiveMethod. The request an application holds keeps reporting POST, which is
        // the method on the wire, the method Spring Security saw and the method the access log records.
        // Requests without an override are returned untouched, leaving multipart handling exactly as it was.
        if (resolveHiddenHttpMethod && shouldProcessMultiPart) {
            String override = HiddenHttpMethod.resolveOverride(currentRequest)
            if (override != null) {
                // The request Grails exposes is always the outermost one, so the override is published as
                // an attribute rather than by substituting a wrapper for it. HiddenHttpMethod.effectiveMethod
                // reads it, which is how allowedMethods sees the same method the URL mappings matched on.
                request.setAttribute(HiddenHttpMethod.OVERRIDDEN_METHOD_ATTRIBUTE, override)
                return HiddenHttpMethod.wrap(override, currentRequest)
            }
        }
        return currentRequest
    }

    /**
     * Wraps the view-render phase in a {@code grails.render} span — parent of the GSP render spans, so
     * "render excluding GSP" is {@code grails.render} minus its GSP children.
     */
    @Override
    protected void render(ModelAndView mv, HttpServletRequest request, HttpServletResponse response) throws Exception {
        def observationRegistry = resolveObservationRegistry()
        if (observationRegistry == null || observationRegistry.isNoop()) {
            super.render(mv, request, response)
            return
        }
        def view = (mv != null && mv.viewName) ? mv.viewName : 'none'
        def observation = Observation.createNotStarted('grails.render', observationRegistry)
                .contextualName('grails.render ' + view)
                .lowCardinalityKeyValue('grails.view', view)
                .start()
        def observationScope = observation.openScope()
        try {
            super.render(mv, request, response)
        }
        catch (Throwable t) {
            observation.error(t)
            throw t
        }
        finally {
            observationScope.close()
            observation.stop()
        }
    }

    private ObservationRegistry resolveObservationRegistry() {
        def registry = this.observationRegistry
        if (registry == null) {
            def wac = getWebApplicationContext()
            if (wac == null) {
                // context not ready — return NOOP without caching so a later call re-resolves
                return ObservationRegistry.NOOP
            }
            registry = wac.getBeanProvider(ObservationRegistry).getIfAvailable({ -> ObservationRegistry.NOOP })
            this.observationRegistry = registry
        }
        registry
    }

    @Override
    void setServletContext(ServletContext servletContext) {
        Holders.setServletContext(servletContext)
        Holders.addApplicationDiscoveryStrategy(new ServletEnvironmentGrailsApplicationDiscoveryStrategy(servletContext))
    }

    @Override
    void setApplicationContext(ApplicationContext applicationContext) {
        if (applicationContext instanceof WebApplicationContext) {
            WebApplicationContext wac = (WebApplicationContext) applicationContext
            Holders.setServletContext(wac.servletContext)
            Holders.addApplicationDiscoveryStrategy(new ServletEnvironmentGrailsApplicationDiscoveryStrategy(wac.servletContext, applicationContext))

        }
        super.setApplicationContext(applicationContext)
    }
}

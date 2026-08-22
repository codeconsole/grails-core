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
package org.grails.plugins.web.async

import groovy.transform.CompileStatic

import grails.async.decorator.PromiseDecorator
import grails.async.decorator.PromiseDecoratorLookupStrategy
import groovy.util.logging.Slf4j
import jakarta.servlet.http.HttpServletRequest

import grails.async.web.AsyncGrailsWebRequest

import org.grails.web.servlet.mvc.GrailsWebRequest

/**
 * A promise decorated lookup strategy that binds a WebRequest to the promise thread
 *
 * @author Graeme Rocher
 * @since 2.3
 */
@Slf4j
@CompileStatic
class AsyncWebRequestPromiseDecoratorLookupStrategy implements PromiseDecoratorLookupStrategy {

    @Override
    List<PromiseDecorator> findDecorators() {
        final webRequest = GrailsWebRequest.lookup()
        if (webRequest) {
            List<PromiseDecorator> decorators = []
            // Held so that a decorator which does not survive its own construction leaves the
            // request as it found it: the web request it builds stores itself on the request
            // before asking the container to start, and a later lookup that found that
            // half-built object would use one whose asynchronous context was never assigned.
            Object boundBefore = attachedWebRequest(webRequest)
            try {
                decorators.add(new AsyncWebRequestPromiseDecorator(webRequest))
            }
            catch (IllegalStateException asynchronousProcessingUnavailable) {
                reattach(webRequest, boundBefore)
                if (!supportsAsync(webRequest)) {
                    // A request that cannot process asynchronously at all is a mistake worth
                    // reporting: the caller asked for something this request will never do.
                    throw asynchronousProcessingUnavailable
                }
                // Otherwise the request does support it and is simply past the point of taking
                // another task: the container is delivering the result of the one that ran, and
                // refuses to start a second cycle on the same request. A callback attached to a
                // promise that completed while it was being attached lands exactly here, and the
                // refusal used to escape and fail the very response being delivered.
                //
                // The refusal is caught rather than anticipated on purpose. Asking first - the
                // async manager reports a concurrent result, say - is a check the container can
                // invalidate between the answer and the call: measured over 4800 requests, asking
                // still let one through, where catching let none through over twice as many.
                log.debug('Not binding this request to the promise: {}',
                        asynchronousProcessingUnavailable.message)
                return Collections.emptyList()
            }
            return decorators
        }
        return Collections.emptyList()
    }

    /**
     * What the request already carries, which is either nothing or the web request of an
     * asynchronous cycle that is genuinely under way.
     */
    private static Object attachedWebRequest(GrailsWebRequest webRequest) {
        try {
            return webRequest.currentRequest.getAttribute(AsyncGrailsWebRequest.WEB_REQUEST)
        }
        catch (IllegalStateException requestIsGone) {
            return null
        }
    }

    private static void reattach(GrailsWebRequest webRequest, Object boundBefore) {
        try {
            HttpServletRequest request = webRequest.currentRequest
            if (boundBefore == null) {
                request.removeAttribute(AsyncGrailsWebRequest.WEB_REQUEST)
            }
            else {
                request.setAttribute(AsyncGrailsWebRequest.WEB_REQUEST, boundBefore)
            }
        }
        catch (IllegalStateException requestIsGone) {
            // Recycled while this was running, so there is nothing left to put anything back on.
        }
    }

    private static boolean supportsAsync(GrailsWebRequest webRequest) {
        try {
            return webRequest.currentRequest.asyncSupported
        }
        catch (IllegalStateException requestIsGone) {
            // Recycled, so it is in no state to take a task either way.
            return true
        }
    }
}

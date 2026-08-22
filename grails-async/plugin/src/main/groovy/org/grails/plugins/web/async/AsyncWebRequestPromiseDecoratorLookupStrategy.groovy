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
import org.grails.web.servlet.mvc.GrailsWebRequest

/**
 * A promise decorated lookup strategy that binds a WebRequest to the promise thread
 *
 * @author Graeme Rocher
 * @since 2.3
 */
@CompileStatic
class AsyncWebRequestPromiseDecoratorLookupStrategy implements PromiseDecoratorLookupStrategy {

    @Override
    List<PromiseDecorator> findDecorators() {
        final webRequest = GrailsWebRequest.lookup()
        if (webRequest) {
            List<PromiseDecorator> decorators = []
            try {
                decorators.add(new AsyncWebRequestPromiseDecorator(webRequest))
            }
            catch (IllegalStateException asynchronousProcessingUnavailable) {
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
                return Collections.emptyList()
            }
            return decorators
        }
        return Collections.emptyList()
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

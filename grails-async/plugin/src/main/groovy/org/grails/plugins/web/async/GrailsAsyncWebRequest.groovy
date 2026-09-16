/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.grails.plugins.web.async

import groovy.transform.CompileStatic

import org.springframework.boot.convert.DurationStyle
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest

import org.grails.web.servlet.mvc.GrailsWebRequest

/**
 * Applies the same timeout policy to eager and controller-result async requests.
 */
@CompileStatic
class GrailsAsyncWebRequest {

    private static final String COMPLETED = GrailsAsyncWebRequest.name + '.COMPLETED'

    static boolean isComplete(GrailsWebRequest request) {
        return request.currentRequest.getAttribute(COMPLETED) == Boolean.TRUE
    }

    static StandardServletAsyncWebRequest create(GrailsWebRequest request) {
        def asyncRequest = new StandardServletAsyncWebRequest(request.currentRequest, request.currentResponse)
        // WebAsyncManager removes itself on completion, so retain the guard on the request.
        asyncRequest.addCompletionHandler { request.currentRequest.setAttribute(COMPLETED, Boolean.TRUE) }
        String timeout = request.applicationContext?.environment?.getProperty('spring.mvc.async.request-timeout')
        if (timeout != null) {
            asyncRequest.timeout = DurationStyle.detectAndParse(timeout).toMillis()
        }
        return asyncRequest
    }
}

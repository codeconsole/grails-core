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
package grails.openapi

import groovy.transform.CompileStatic

/**
 * The shape of the actions {@link grails.rest.RestfulController} declares, used to describe a
 * controller reached through the default {@code "/$controller/$action?/$id?"} mapping rather than
 * through a mapping that names it.
 *
 * @author Scott Murphy Heiberg
 * @since 8.0
 */
@CompileStatic
class RestfulControllerActions {

    /**
     * Whether an action addresses a single resource, and so takes the optional id segment.
     */
    private static final Set<String> ID_ACTIONS = ['show', 'edit', 'update', 'patch', 'delete'].toSet().asImmutable()

    /**
     * The method each action answers when the controller does not declare otherwise. Mirrors the
     * {@code allowedMethods} RestfulController declares; a subclass that overrides it is read
     * directly instead.
     */
    private static final Map<String, String> DEFAULT_METHODS = [
            save: 'POST',
            update: 'PUT',
            patch: 'PATCH',
            delete: 'DELETE',
    ].asImmutable()

    static boolean takesId(String actionName) {
        actionName in ID_ACTIONS
    }

    /**
     * @param actionName the action to describe
     * @param allowedMethods the controller's declared {@code allowedMethods}, if any
     * @return the HTTP method the action answers, defaulting to GET
     */
    static String httpMethod(String actionName, Object allowedMethods) {
        Object declared = (allowedMethods instanceof Map) ? ((Map) allowedMethods).get(actionName) : null
        if (declared instanceof CharSequence) {
            return declared.toString().toUpperCase(Locale.ENGLISH)
        }
        if (declared instanceof Collection && declared) {
            // A single OpenAPI operation cannot describe several methods, so the first declared
            // method is documented: RestfulController lists the RESTful one first.
            return ((Collection) declared).first().toString().toUpperCase(Locale.ENGLISH)
        }
        DEFAULT_METHODS.getOrDefault(actionName, 'GET')
    }
}

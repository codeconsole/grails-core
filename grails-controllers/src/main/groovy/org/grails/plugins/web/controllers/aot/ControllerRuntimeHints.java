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
package org.grails.plugins.web.controllers.aot;

import org.jspecify.annotations.Nullable;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Registers the controller API a request is dispatched through.
 *
 * <p>A controller action reaches these through Groovy's dynamic dispatch, which reads a type's
 * declared methods to choose an overload. An ahead-of-time image keeps only the members something
 * asks for, so without these hints the methods are absent and dispatch fails at the point of use --
 * on the request that first takes that path, rather than at start-up.</p>
 *
 * <p>Recording this here rather than leaving it to a tracing agent matters because the agent only
 * ever sees the paths a developer happened to exercise: the method check below is reached only by
 * POST, PUT and DELETE, so a walk of an application's pages never records it and the failure
 * appears the first time someone submits a form.</p>
 *
 * @since 8.0
 */
public class ControllerRuntimeHints implements RuntimeHintsRegistrar {

    /**
     * Types Groovy dispatches on while handling a request. Named as strings, and registered only
     * when present, so this stays correct for an application that does not use every plugin.
     */
    private static final String[] DISPATCHED_TYPES = {
        "grails.artefact.Controller",
        "grails.artefact.controller.support.AllowedMethodsHelper",
        "grails.artefact.controller.support.RequestForwarder",
        "grails.artefact.controller.support.ResponseRedirector",
        "grails.artefact.controller.support.ResponseRenderer",
        "grails.artefact.controller.RestResponder",
        // a view asking who is logged in reaches the request's principal, and Groovy makes that
        // call on the interface rather than the implementation the container supplies
        "java.security.Principal"
    };

    @Override
    public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
        for (String type : DISPATCHED_TYPES) {
            hints.reflection().registerTypeIfPresent(classLoader, type,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.INVOKE_PUBLIC_METHODS,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        }
    }
}

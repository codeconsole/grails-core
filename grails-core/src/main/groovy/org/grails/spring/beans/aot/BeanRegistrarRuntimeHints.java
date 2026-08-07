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
package org.grails.spring.beans.aot;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.lang.Nullable;

/**
 * Registers the registry a plugin declares its beans against.
 *
 * <p>A plugin declares them in a closure, and Groovy resolves every call in a closure where the call
 * is written rather than when the closure is compiled. So each call on the registry is made
 * reflectively, and an image keeps a method for that only when something has asked it to.</p>
 *
 * <p>Nothing does: the registry belongs to Spring, an application never names it, and the closures
 * that call it are registered as closures rather than for what they call. The image then refuses the
 * first call and the context does not start, reporting a method of an interface that appears nowhere
 * in the application -- a plugin declaring a bean with a specification, which most of them do.</p>
 *
 * <p>The types are named here rather than scanned for, because they belong to Spring and are few.</p>
 *
 * @since 8.0
 */
public class BeanRegistrarRuntimeHints implements RuntimeHintsRegistrar {

    /** The registry, what hands a plugin to it, and the types its calls pass through. */
    private static final Class<?>[] TYPES = {
        BeanRegistrar.class,
        BeanRegistry.class,
        BeanRegistry.Spec.class,
        BeanRegistry.SupplierContext.class
    };

    @Override
    public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
        for (Class<?> type : TYPES) {
            hints.reflection().registerType(type, MemberCategory.INVOKE_DECLARED_METHODS);
        }
    }
}

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

import jakarta.servlet.http.HttpServletRequest;

/**
 * Reads a property off an {@code HttpServletRequest} the way application Groovy code does.
 *
 * <p>Implemented in Groovy so the reads compile to real dynamic call sites; reached from the
 * Java benchmark through this interface because {@code compileJmhGroovy} runs after
 * {@code compileJmhJava}.</p>
 */
public interface RequestPropertyReader {

    /**
     * @return {@code request.someAttribute} - an unknown property, which falls through the metaclass
     * to {@code HttpServletRequestExtension} and ends up as an attribute read
     */
    Object readUnknownProperty(HttpServletRequest request);

    /**
     * @return {@code request.method} - a property backed by a real getter on the request
     */
    Object readGetterBackedProperty(HttpServletRequest request);

    /**
     * @return {@code request.getAttribute('someAttribute')} - the explicit, non-dynamic equivalent
     * of {@link #readUnknownProperty}, called from Groovy
     */
    Object readAttributeDirectly(HttpServletRequest request);
}

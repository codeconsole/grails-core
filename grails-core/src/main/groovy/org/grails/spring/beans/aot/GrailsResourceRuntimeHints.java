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

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.lang.Nullable;

/**
 * The resources a Grails application reads by name at run time, which an image cannot prove are
 * reached.
 *
 * <p>An image includes what it can prove is used. A compiled asset is asked for by request path and
 * a message bundle by locale, so nothing in the code names either, and an image built without them
 * starts and then serves every page with a missing stylesheet and an untranslated string.</p>
 *
 * <p>Registered as hints rather than passed as {@code -H:IncludeResources}: that option is
 * experimental and GraalVM now warns it will require unlocking, while hints are the supported way
 * and are what Spring writes the image's resource configuration from.</p>
 *
 * @since 8.0
 */
public class GrailsResourceRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
        // Compiled assets, which asset-pipeline serves from the classpath by the path asked for.
        hints.resources().registerPattern("assets/*");
        hints.resources().registerPattern("assets/**");

        // Message bundles, chosen by the locale of the request. At the root of the classpath and,
        // for a plugin that ships its own, below it.
        hints.resources().registerPattern("*.properties");
        hints.resources().registerPattern("**/*.properties");
    }

}

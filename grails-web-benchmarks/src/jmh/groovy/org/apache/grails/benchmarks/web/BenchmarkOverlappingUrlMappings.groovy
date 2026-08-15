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
package org.apache.grails.benchmarks.web

/**
 * A mapping set in which several patterns deliberately overlap on the same URI, so that
 * {@code matchAll} returns a multi-element candidate array.
 *
 * <p>{@code BenchmarkUrlMappings} is the realistic set and produces one or two candidates for a
 * typical URI; this one exists to show how the per-candidate work in
 * {@code collectControllerMappings} scales, which a two-candidate measurement alone cannot.</p>
 */
class BenchmarkOverlappingUrlMappings implements UrlMappingsDefinition {

    @Override
    Closure<?> mappings() {
        return { ->
            '/api/books'(resources: 'book')

            // Double quotes: the DSL relies on GString interpolation to turn $token into a
            // capturing wildcard, so these patterns must not be single quoted.
            "/api/books/$id"(controller: 'book', action: 'show')
            "/api/$section/$id"(controller: 'book', action: 'show')
            "/$controller/$action?/$id?(.$format)?"()
        }
    }
}

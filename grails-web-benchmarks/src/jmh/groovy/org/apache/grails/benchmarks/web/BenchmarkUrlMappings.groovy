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
 * A mapping set sized and shaped like a mid-size application's {@code UrlMappings.groovy}:
 * a handful of static URLs, five REST resource blocks, some multi-token dynamic URLs, a couple of
 * method-scoped mappings, the catch-all default mapping, and the error mappings.
 */
class BenchmarkUrlMappings implements UrlMappingsDefinition {

    @Override
    Closure<?> mappings() {
        return { ->
            '/'(controller: 'application', action: 'index')
            '/login'(controller: 'auth', action: 'login')
            '/logout'(controller: 'auth', action: 'logout')
            '/health'(controller: 'health', action: 'index')

            '/api/books'(resources: 'book')
            '/api/authors'(resources: 'author')
            '/api/publishers'(resources: 'publisher')
            '/api/orders'(resources: 'order')
            '/api/customers'(resources: 'customer')

            // Double quotes: the DSL relies on GString interpolation to turn $token into a
            // capturing wildcard, so these patterns must not be single quoted.
            "/store/$category/$subcategory?"(controller: 'store', action: 'browse')
            "/blog/$year/$month?/$day?/$slug?"(controller: 'blog', action: 'show')
            "/report/$id/download"(controller: 'report', action: 'download')

            get '/search'(controller: 'search', action: 'index')
            post '/feedback'(controller: 'feedback', action: 'save')

            "/$controller/$action?/$id?(.$format)?"()

            '500'(view: '/error')
            '404'(view: '/notFound')
        }
    }
}

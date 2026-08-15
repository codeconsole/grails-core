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

import groovy.transform.CompileStatic

import grails.artefact.Interceptor

/**
 * Interceptors for {@code InterceptorChainBenchmark}.
 *
 * <p>Three distinct classes, so that a chain of three is as polymorphic at the adapter's call sites
 * as a real application's chain is. Each matches every request and leaves {@code before()} and
 * {@code after()} at the trait defaults, because what is being measured is the adapter's per
 * interceptor per phase overhead, not the body of anybody's interceptor.</p>
 */
@CompileStatic
class BenchmarkInterceptors implements InterceptorFactory {

    @Override
    Interceptor[] matchingInterceptors(int count) {
        List<Interceptor> created = []
        if (count >= 1) {
            created << new BenchmarkAuditInterceptor()
        }
        if (count >= 2) {
            created << new BenchmarkSecurityInterceptor()
        }
        if (count >= 3) {
            created << new BenchmarkTimingInterceptor()
        }
        if (created.size() != count) {
            throw new IllegalArgumentException("The fixture only defines 3 interceptor classes, asked for ${count}")
        }
        created.each { Interceptor interceptor -> interceptor.matchAll() }
        created as Interceptor[]
    }
}

@CompileStatic
class BenchmarkAuditInterceptor implements Interceptor {
}

@CompileStatic
class BenchmarkSecurityInterceptor implements Interceptor {
}

@CompileStatic
class BenchmarkTimingInterceptor implements Interceptor {
}

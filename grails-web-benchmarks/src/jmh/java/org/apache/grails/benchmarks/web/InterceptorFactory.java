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

import grails.artefact.Interceptor;

/**
 * Supplies {@link Interceptor} instances to a benchmark.
 *
 * <p>{@code Interceptor} is a Groovy trait, so an implementation has to be written in Groovy, and
 * {@code compileJmhGroovy} runs after {@code compileJmhJava}. The Java benchmark therefore reaches
 * its Groovy interceptors through this interface and {@code Class.forName}, the same way
 * {@code UrlMappingBenchmark} reaches its mappings.</p>
 */
public interface InterceptorFactory {

    /**
     * @param count how many interceptors to create, at most as many as there are distinct
     * interceptor classes in the fixture
     * @return that many interceptors, each of distinct class, each matching every request
     */
    Interceptor[] matchingInterceptors(int count);
}

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

import java.util.List;
import java.util.concurrent.TimeUnit;

import groovy.lang.Closure;

import jakarta.servlet.ServletContext;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import org.springframework.context.ApplicationContext;

import grails.web.mapping.UrlMapping;
import grails.web.mapping.UrlMappingInfo;
import org.grails.web.mapping.DefaultUrlMappingEvaluator;
import org.grails.web.mapping.DefaultUrlMappingsHolder;

/**
 * Measures {@code DefaultUrlMappingsHolder.match(uri)} against a realistic mapping set
 * (see {@code BenchmarkUrlMappings}).
 *
 * <p>{@code match} memoises results in a Caffeine cache holding at most 1000 URIs, so the hot and
 * the cold paths behave very differently and both are measured. The cold benchmarks rotate through
 * a pool several times larger than the cache so that the cache is dominated by misses, and
 * therefore report the cost of actually running the URI through the mapping patterns.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
public class UrlMappingBenchmark {

    /** Comfortably larger than the holder's 1000 entry match cache. */
    private static final int URI_POOL_SIZE = 4096;

    private static final int URI_POOL_MASK = URI_POOL_SIZE - 1;

    private DefaultUrlMappingsHolder urlMappingsHolder;

    private String[] restfulUris;

    private String[] defaultMappingUris;

    private int cursor;

    @Setup(Level.Trial)
    public void setUp() throws ReflectiveOperationException {
        ServletContext servletContext = BenchmarkWebContext.newServletContext();
        ApplicationContext applicationContext = BenchmarkWebContext.applicationContext(servletContext);

        UrlMappingsDefinition definition = (UrlMappingsDefinition) Class
                .forName("org.apache.grails.benchmarks.web.BenchmarkUrlMappings")
                .getDeclaredConstructor()
                .newInstance();
        Closure<?> mappings = definition.mappings();

        DefaultUrlMappingEvaluator evaluator = new DefaultUrlMappingEvaluator(applicationContext);
        List<UrlMapping> evaluated = evaluator.evaluateMappings(mappings);
        urlMappingsHolder = new DefaultUrlMappingsHolder(evaluated);

        restfulUris = new String[URI_POOL_SIZE];
        defaultMappingUris = new String[URI_POOL_SIZE];
        for (int i = 0; i < URI_POOL_SIZE; i++) {
            restfulUris[i] = "/api/books/" + i;
            defaultMappingUris[i] = "/widget/show/" + i;
        }

        if (urlMappingsHolder.match("/api/books/42") == null) {
            throw new IllegalStateException("Benchmark fixture is wrong: /api/books/42 does not match any mapping");
        }
        if (urlMappingsHolder.match("/widget/show/42") == null) {
            throw new IllegalStateException("Benchmark fixture is wrong: /widget/show/42 does not match any mapping");
        }
    }

    /** The steady-state production path for a URL that has been seen before. */
    @Benchmark
    public UrlMappingInfo matchCachedHit() {
        return urlMappingsHolder.match("/api/books/42");
    }

    /** A URL that resolves through a REST resources block, with the match cache mostly missing. */
    @Benchmark
    public UrlMappingInfo matchRestfulUriCacheMiss() {
        return urlMappingsHolder.match(restfulUris[cursor++ & URI_POOL_MASK]);
    }

    /** A URL that only the catch-all default mapping can serve, with the match cache mostly missing. */
    @Benchmark
    public UrlMappingInfo matchDefaultMappingUriCacheMiss() {
        return urlMappingsHolder.match(defaultMappingUris[cursor++ & URI_POOL_MASK]);
    }
}

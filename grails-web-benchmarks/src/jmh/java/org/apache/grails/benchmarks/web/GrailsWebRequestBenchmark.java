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

import java.util.concurrent.TimeUnit;

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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;

import grails.web.servlet.mvc.GrailsParameterMap;
import org.grails.web.servlet.mvc.GrailsWebRequest;

/**
 * Measures the per-request cost of binding a Grails request.
 *
 * <ul>
 *   <li>{@link #construct()} - {@code new GrailsWebRequest(request, response, servletContext)},
 *       which reflectively instantiates {@code DefaultGrailsApplicationAttributes} on every
 *       request through a cached {@code Constructor}.</li>
 *   <li>{@link #paramsOnFreshRequest()} - construction plus the first {@code getParams()}, i.e.
 *       what a controller action actually pays the first time it touches {@code params}.</li>
 *   <li>{@link #paramsCached()} - the memoised {@code getParams()} fast path.</li>
 *   <li>{@link #paramsRebuilt()} - {@code resetParams()} plus {@code getParams()}, which isolates
 *       the deep clone of the already-built {@code GrailsParameterMap}.</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
public class GrailsWebRequestBenchmark {

    private ServletContext servletContext;

    private MockHttpServletRequest request;

    private MockHttpServletResponse response;

    private GrailsWebRequest webRequest;

    @Setup(Level.Trial)
    public void setUp() {
        servletContext = BenchmarkWebContext.newServletContext();
        request = new MockHttpServletRequest(servletContext, "GET", "/book/show/42");
        request.setContextPath("");
        // A query string that is representative of a real controller request: a handful of flat
        // parameters plus a nested one, so that GrailsParameterMap builds (and later deep clones)
        // a nested map rather than a flat one.
        request.addParameter("q", "groovy");
        request.addParameter("format", "json");
        request.addParameter("offset", "0");
        request.addParameter("max", "20");
        request.addParameter("sort", "dateCreated");
        request.addParameter("order", "desc");
        request.addParameter("author.name", "Rocher");
        request.addParameter("author.email", "rocher@example.com");
        response = new MockHttpServletResponse();

        webRequest = new GrailsWebRequest(request, response, servletContext);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Benchmark
    public GrailsWebRequest construct() {
        return new GrailsWebRequest(request, response, servletContext);
    }

    @Benchmark
    public GrailsParameterMap paramsOnFreshRequest() {
        return new GrailsWebRequest(request, response, servletContext).getParams();
    }

    @Benchmark
    public GrailsParameterMap paramsCached() {
        return webRequest.getParams();
    }

    @Benchmark
    public GrailsParameterMap paramsRebuilt() {
        webRequest.resetParams();
        return webRequest.getParams();
    }
}

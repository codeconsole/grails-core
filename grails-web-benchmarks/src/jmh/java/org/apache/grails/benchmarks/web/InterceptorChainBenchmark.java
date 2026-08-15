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

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

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

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.servlet.ModelAndView;

import grails.artefact.Interceptor;
import org.grails.plugins.web.interceptors.GrailsInterceptorHandlerInterceptorAdapter;

/**
 * Measures the {@code preHandle} + {@code postHandle} pair of
 * {@link GrailsInterceptorHandlerInterceptorAdapter}, which every request with at least one
 * interceptor bean runs through twice - once before the handler and once after.
 *
 * <p>The dominant production configuration is a no-op {@code ObservationRegistry}: an application
 * that has not enabled metrics or tracing gets {@code ObservationRegistry.NOOP}, and even one that
 * has, gets a registry that is no-op until a handler is registered. The observing variants are
 * measured too, but the no-op numbers are the ones that describe most applications.</p>
 *
 * <p>The interceptors themselves leave {@code before()} and {@code after()} at their trait defaults,
 * so the score is the adapter's own per-interceptor per-phase cost - matcher evaluation, list
 * bookkeeping, and whatever the adapter allocates to dispatch the callback - and not the cost of
 * anybody's interceptor body.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
public class InterceptorChainBenchmark {

    private MockHttpServletRequest request;

    private MockHttpServletResponse response;

    private ModelAndView modelAndView;

    private Object handler;

    private GrailsInterceptorHandlerInterceptorAdapter oneNoOp;

    private GrailsInterceptorHandlerInterceptorAdapter threeNoOp;

    private GrailsInterceptorHandlerInterceptorAdapter oneObserving;

    private GrailsInterceptorHandlerInterceptorAdapter threeObserving;

    @Setup(Level.Trial)
    public void setUp() throws ReflectiveOperationException {
        MockServletContext servletContext = BenchmarkWebContext.newServletContext();
        this.request = new MockHttpServletRequest(servletContext, "GET", "/book/show/42");
        this.response = new MockHttpServletResponse();
        this.modelAndView = new ModelAndView("/book/show");
        this.handler = new Object();

        InterceptorFactory factory = (InterceptorFactory) Class
                .forName("org.apache.grails.benchmarks.web.BenchmarkInterceptors")
                .getDeclaredConstructor()
                .newInstance();

        this.oneNoOp = newAdapter(factory, 1, ObservationRegistry.NOOP);
        this.threeNoOp = newAdapter(factory, 3, ObservationRegistry.NOOP);
        this.oneObserving = newAdapter(factory, 1, newObservingRegistry());
        this.threeObserving = newAdapter(factory, 3, newObservingRegistry());

        // Fail loudly rather than silently measuring a chain that matches nothing.
        requireMatched(this.oneNoOp, 1);
        requireMatched(this.threeNoOp, 3);
        requireMatched(this.oneObserving, 1);
        requireMatched(this.threeObserving, 3);
    }

    private GrailsInterceptorHandlerInterceptorAdapter newAdapter(InterceptorFactory factory, int count, ObservationRegistry registry) {
        GrailsInterceptorHandlerInterceptorAdapter adapter = new GrailsInterceptorHandlerInterceptorAdapter();
        Interceptor[] interceptors = factory.matchingInterceptors(count);
        adapter.setInterceptors(interceptors);
        adapter.setObservationRegistry(registry);
        return adapter;
    }

    /**
     * @return a registry that is not no-op, because a handler is registered on it - the shape an
     * application running Micrometer tracing or metrics has
     */
    private static ObservationRegistry newObservingRegistry() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        if (registry.isNoop()) {
            throw new IllegalStateException("Benchmark fixture is wrong: the observing registry reports itself as no-op");
        }
        return registry;
    }

    private void requireMatched(GrailsInterceptorHandlerInterceptorAdapter adapter, int expected) {
        try {
            adapter.preHandle(this.request, this.response, this.handler);
        }
        catch (Exception e) {
            throw new IllegalStateException("Benchmark fixture is wrong: preHandle threw", e);
        }
        Object matched = this.request.getAttribute("org.grails.web.MATCHED_INTERCEPTORS");
        int size = matched instanceof java.util.List ? ((java.util.List<?>) matched).size() : -1;
        if (size != expected) {
            throw new IllegalStateException("Benchmark fixture is wrong: expected " + expected + " matched interceptors but got " + size);
        }
    }

    /** One matched interceptor, no-op observation registry - the dominant production shape. */
    @Benchmark
    public boolean oneInterceptorNoOpRegistry() throws Exception {
        boolean proceed = this.oneNoOp.preHandle(this.request, this.response, this.handler);
        this.oneNoOp.postHandle(this.request, this.response, this.handler, this.modelAndView);
        return proceed;
    }

    /** Three matched interceptors, no-op observation registry. */
    @Benchmark
    public boolean threeInterceptorsNoOpRegistry() throws Exception {
        boolean proceed = this.threeNoOp.preHandle(this.request, this.response, this.handler);
        this.threeNoOp.postHandle(this.request, this.response, this.handler, this.modelAndView);
        return proceed;
    }

    /** One matched interceptor, with a registry that actually records observations. */
    @Benchmark
    public boolean oneInterceptorObservingRegistry() throws Exception {
        boolean proceed = this.oneObserving.preHandle(this.request, this.response, this.handler);
        this.oneObserving.postHandle(this.request, this.response, this.handler, this.modelAndView);
        return proceed;
    }

    /** Three matched interceptors, with a registry that actually records observations. */
    @Benchmark
    public boolean threeInterceptorsObservingRegistry() throws Exception {
        boolean proceed = this.threeObserving.preHandle(this.request, this.response, this.handler);
        this.threeObserving.postHandle(this.request, this.response, this.handler, this.modelAndView);
        return proceed;
    }
}

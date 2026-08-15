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
import groovy.lang.GroovyClassLoader;

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
import org.springframework.web.context.WebApplicationContext;

import grails.core.DefaultGrailsApplication;
import grails.core.GrailsApplication;
import grails.util.GrailsWebMockUtil;
import grails.web.mapping.UrlMapping;
import grails.web.mapping.UrlMappingInfo;
import grails.web.mapping.UrlMappings;
import org.grails.web.mapping.DefaultUrlMappingEvaluator;
import org.grails.web.mapping.DefaultUrlMappingsHolder;
import org.grails.web.mapping.mvc.GrailsControllerUrlMappings;

/**
 * Measures {@code GrailsControllerUrlMappings.matchAll}, which is what
 * {@code UrlMappingsHandlerMapping.getHandlerInternal} calls on every request.
 *
 * <p>The delegate's own {@code matchAll} memoises its result in a Caffeine cache, but the wrapper
 * around it - {@code AbstractGrailsControllerUrlMappings.collectControllerMappings} - is not cached
 * and runs in full every time. For each candidate it calls {@code webRequest.resetParams()} (which
 * clones the parameter map), {@code info.configure(webRequest)}, and allocates a {@code
 * ControllerKey}. Each benchmark here calls a single URI repeatedly, so the delegate cache always
 * hits and what is measured is the uncached wrapper.</p>
 *
 * <h2>Where this is not faithful to production</h2>
 * <ul>
 *   <li>The requests carry no query string, so {@code resetParams()} clones a
 *   {@code GrailsParameterMap} built over an empty servlet parameter map. A real request with
 *   parameters clones more, so this is a lower bound.</li>
 *   <li>The application registers a handful of controllers rather than a full application's worth.
 *   The lookup is a {@code ConcurrentHashMap} get, so this affects the number very little, but it
 *   is not zero.</li>
 *   <li>No {@code UrlConverter} is registered. The converter is only consulted when controllers are
 *   registered, not per request, so this does not affect the measured path.</li>
 *   <li>{@code fourCandidates} uses a deliberately overlapping mapping set
 *   ({@code BenchmarkOverlappingUrlMappings}), not a realistic one. The realistic set produces one
 *   or two candidates for a typical URI - which is what {@code oneCandidate} and
 *   {@code twoCandidates} measure. Setup prints the candidate count each benchmark actually
 *   collects, so the numbers can be read per candidate.</li>
 *   <li>{@code UrlMappingsHandlerMapping} then repeats {@code resetParams()} and
 *   {@code info.configure(webRequest)} on the winning candidate after this method returns. That
 *   repeat is not included here.</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
public class ControllerMappingCollectionBenchmark {

    private static final String CONTROLLER_SOURCES = """
            @grails.artefact.Artefact('Controller')
            class BenchmarkBookController {
                def index() { null }
                def show() { null }
                def create() { null }
                def save() { null }
                def edit() { null }
                def update() { null }
                def patch() { null }
                def delete() { null }
            }

            @grails.artefact.Artefact('Controller')
            class BenchmarkBlogController {
                def show() { null }
            }

            @grails.artefact.Artefact('Controller')
            class BenchmarkStoreController {
                def browse() { null }
            }

            @grails.artefact.Artefact('Controller')
            class BenchmarkHealthController {
                def index() { null }
            }
            """;

    /** Matched by the {@code resources: 'book'} show mapping, and by the catch-all default mapping. */
    private static final String TWO_CANDIDATE_URI = "/api/books/42";

    /** Five segments, so only the multi-token blog mapping can serve it. */
    private static final String ONE_CANDIDATE_URI = "/blog/2026/08/14/grails-8-is-fast";

    /** Served by several deliberately overlapping mappings in {@code BenchmarkOverlappingUrlMappings}. */
    private static final String FOUR_CANDIDATE_URI = "/api/books/42";

    private UrlMappings realisticMappings;

    private UrlMappings overlappingMappings;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        MockServletContext servletContext = BenchmarkWebContext.newServletContext();
        WebApplicationContext applicationContext = BenchmarkWebContext.applicationContext(servletContext);

        GroovyClassLoader classLoader = BenchmarkControllerCompiler.newTransformingClassLoader();
        classLoader.parseClass(CONTROLLER_SOURCES, "BenchmarkMappingControllers.groovy");
        Class<?>[] controllers = new Class<?>[] {
                classLoader.loadClass("BenchmarkBookController"),
                classLoader.loadClass("BenchmarkBlogController"),
                classLoader.loadClass("BenchmarkStoreController"),
                classLoader.loadClass("BenchmarkHealthController")
        };

        DefaultGrailsApplication grailsApplication = new DefaultGrailsApplication(controllers);
        grailsApplication.setApplicationContext(applicationContext);
        grailsApplication.initialise();

        this.realisticMappings = newControllerUrlMappings(grailsApplication, applicationContext, "org.apache.grails.benchmarks.web.BenchmarkUrlMappings");
        this.overlappingMappings = newControllerUrlMappings(grailsApplication, applicationContext, "org.apache.grails.benchmarks.web.BenchmarkOverlappingUrlMappings");

        // The bound request is what makes collectControllerMappings take its expensive branch:
        // GrailsWebRequest.lookup() has to return non-null for resetParams()/configure() to run,
        // which is always the case in production.
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext, "GET", TWO_CANDIDATE_URI);
        GrailsWebMockUtil.bindMockWebRequest(applicationContext, request, new MockHttpServletResponse());

        report("oneCandidate", this.realisticMappings, ONE_CANDIDATE_URI, 1);
        report("twoCandidates", this.realisticMappings, TWO_CANDIDATE_URI, 2);
        report("fourCandidates", this.overlappingMappings, FOUR_CANDIDATE_URI, 4);
    }

    private UrlMappings newControllerUrlMappings(GrailsApplication grailsApplication, WebApplicationContext applicationContext,
            String definitionClassName) throws ReflectiveOperationException {
        UrlMappingsDefinition definition = (UrlMappingsDefinition) Class
                .forName(definitionClassName)
                .getDeclaredConstructor()
                .newInstance();
        Closure<?> mappings = definition.mappings();
        DefaultUrlMappingEvaluator evaluator = new DefaultUrlMappingEvaluator(applicationContext);
        List<UrlMapping> evaluated = evaluator.evaluateMappings(mappings);
        DefaultUrlMappingsHolder delegate = new DefaultUrlMappingsHolder(evaluated);
        return new GrailsControllerUrlMappings(grailsApplication, delegate);
    }

    /**
     * Prints how many candidates the delegate produces for a URI and how many survive collection,
     * and fails if the delegate count is not the number the benchmark claims to measure.
     */
    private void report(String name, UrlMappings mappings, String uri, int expectedCandidates) {
        UrlMappingInfo[] rawCandidates = ((GrailsControllerUrlMappings) mappings)
                .getUrlMappingsHolderDelegate().matchAll(uri, "GET", UrlMapping.ANY_VERSION);
        UrlMappingInfo[] collected = mappings.matchAll(uri, "GET", UrlMapping.ANY_VERSION);
        System.out.println("[fixture] " + name + " uri=" + uri
                + " candidates=" + rawCandidates.length + " collected=" + collected.length);
        if (rawCandidates.length != expectedCandidates) {
            throw new IllegalStateException("Benchmark fixture is wrong: " + name + " expected "
                    + expectedCandidates + " candidates for " + uri + " but the mappings produced " + rawCandidates.length);
        }
    }

    /** A URI only one mapping can serve, so the wrapper does one candidate's worth of work. */
    @Benchmark
    public UrlMappingInfo[] oneCandidate() {
        return this.realisticMappings.matchAll(ONE_CANDIDATE_URI, "GET", UrlMapping.ANY_VERSION);
    }

    /** A REST URI served by a resources mapping and by the catch-all default mapping. */
    @Benchmark
    public UrlMappingInfo[] twoCandidates() {
        return this.realisticMappings.matchAll(TWO_CANDIDATE_URI, "GET", UrlMapping.ANY_VERSION);
    }

    /** Four overlapping candidates, to show how the uncached per-candidate work scales. */
    @Benchmark
    public UrlMappingInfo[] fourCandidates() {
        return this.overlappingMappings.matchAll(FOUR_CANDIDATE_URI, "GET", UrlMapping.ANY_VERSION);
    }
}

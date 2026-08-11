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
package org.grails.web.taglib

import java.nio.file.Files
import java.nio.file.Path

import grails.testing.web.taglib.TagLibUnitTest
import groovy.text.Template
import org.grails.gsp.GroovyPagesTemplateEngine
import org.grails.taglib.index.TagLibraryIndex
import org.grails.plugins.web.taglib.ApplicationTagLib
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification

/**
 * What compiling a tag call into an invocation is worth, separately from removing the metaclass work
 * that used to surround every call.
 *
 * <p>Both are measured against the same framework, so the metaclass writes are already gone from both
 * sides. What varies is only whether a call was compiled into an invocation, which is what a build can
 * still turn off per namespace. That isolates the part of the change whose value was never measured
 * on its own.
 *
 * <p>Off unless asked for, since a timing run is neither quick nor a pass/fail assertion:
 *
 * <pre>
 * GRAILS_TAGLIB_BENCH=true ./gradlew :grails-gsp:test \
 *     --tests '*TagDispatchBenchmarkSpec' --rerun-tasks -i
 * </pre>
 *
 * <p>Gated on the environment rather than a system property because a forked test process inherits
 * the environment, where this build bridges only a few named properties into it.
 */
@Requires({ System.getenv('GRAILS_TAGLIB_BENCH') })
class TagDispatchBenchmarkSpec extends Specification implements TagLibUnitTest<ApplicationTagLib> {

    /**
     * Tag calls per render. Fewer than the 400-call page the pull request measured, because that many
     * expressions in one page exceed the size a single Groovy method may compile to. Results are
     * reported per call, so the count only has to be large enough to dominate per-render overhead.
     */
    private static final int CALLS_PER_RENDER = 50

    private static final int WARMUP_RENDERS = intFromEnv('GRAILS_TAGLIB_BENCH_WARMUP', 300)
    private static final int MEASURED_RENDERS = intFromEnv('GRAILS_TAGLIB_BENCH_RENDERS', 2000)
    /** Alternated rather than run end to end, so a machine warming up cannot favour one side. */
    private static final int ROUNDS = intFromEnv('GRAILS_TAGLIB_BENCH_ROUNDS', 7)

    void 'a page renders its tags faster once they are compiled into invocations'() {
        given: 'each page compiled once, so what is timed is rendering rather than compiling'
        GroovyPagesTemplateEngine engine = applicationContext.getBean(GroovyPagesTemplateEngine)
        Template dispatched = engine.createTemplate(page(false), 'benchDispatched')
        Template compiled = engine.createTemplate(page(true), 'benchCompiled')

        when:
        List<Double> dynamicRuns = []
        List<Double> staticRuns = []
        WARMUP_RENDERS.times {
            renderOnce(dispatched)
            renderOnce(compiled)
        }
        ROUNDS.times {
            dynamicRuns << timePerCall(dispatched)
            staticRuns << timePerCall(compiled)
        }

        then:
        report('dispatched', dynamicRuns)
        report('compiled', staticRuns)
        double dynamicMedian = median(dynamicRuns)
        double staticMedian = median(staticRuns)
        println String.format('  change            %+.1f%% per tag call',
                ((staticMedian - dynamicMedian) / dynamicMedian) * 100.0d)
        println "  (${CALLS_PER_RENDER} calls per render, ${MEASURED_RENDERS} renders per round, " +
                "${ROUNDS} alternated rounds)"

        and: 'reported rather than asserted: a timing is evidence, not a contract'
        dynamicMedian > 0.0d && staticMedian > 0.0d
    }

    void 'a tag library calls other tags faster once those calls are compiled into invocations'() {
        given: 'two tag libraries alike but for whether the build let their calls be compiled'
        Class<?> dispatchedTagLib = compileCaller('BenchDispatchedTagLib', 'benchdispatched', true)
        Class<?> compiledTagLib = compileCaller('BenchCompiledTagLib', 'benchcompiled', false)
        mockTagLib(dispatchedTagLib)
        mockTagLib(compiledTagLib)

        and: 'each reached through a page compiled once, so only the tag calls within differ'
        GroovyPagesTemplateEngine engine = applicationContext.getBean(GroovyPagesTemplateEngine)
        Template dispatched = engine.createTemplate('<benchdispatched:callsTags/>', 'benchCallerDispatched')
        Template compiled = engine.createTemplate('<benchcompiled:callsTags/>', 'benchCallerCompiled')

        when:
        List<Double> dispatchedRuns = []
        List<Double> compiledRuns = []
        WARMUP_RENDERS.times {
            renderOnce(dispatched)
            renderOnce(compiled)
        }
        ROUNDS.times {
            dispatchedRuns << timePerCall(dispatched)
            compiledRuns << timePerCall(compiled)
        }

        then:
        println '  -- calls written inside a tag library --'
        report('dispatched', dispatchedRuns)
        report('compiled', compiledRuns)
        double dispatchedMedian = median(dispatchedRuns)
        double compiledMedian = median(compiledRuns)
        println String.format('  change            %+.1f%% per tag call',
                ((compiledMedian - dispatchedMedian) / dispatchedMedian) * 100.0d)

        and:
        dispatchedMedian > 0.0d && compiledMedian > 0.0d
    }

    /**
     * Compiles a tag library whose tag calls the framework's own tag library, either left to dispatch
     * or compiled into invocations depending on what the build declared.
     *
     * @param declaredDynamic whether the build declared the called namespace as filled in at runtime,
     *        which is what turns compile-time resolution off for it
     */
    private Class<?> compileCaller(String className, String namespace, boolean declaredDynamic) {
        StringBuilder body = new StringBuilder()
        CALLS_PER_RENDER.times { int i ->
            body.append("            out << g.createLink(controller: 'book', action: 'show', id: ${i})\n")
        }
        String source = """
            import grails.gsp.TagLib
            @TagLib
            class ${className} {
                static namespace = '${namespace}'
                def callsTags(Map attrs) {
${body}
                }
            }
        """
        ClassLoader parent = getClass().classLoader
        if (declaredDynamic) {
            Path settings = Files.createTempDirectory(className)
            Path indexDir = Files.createDirectories(settings.resolve(TagLibraryIndex.INDEX_LOCATION))
            indexDir.resolve('compile-settings.properties').toFile().text = 'dynamicTagNamespaces=g\n'
            parent = new URLClassLoader([settings.toUri().toURL()] as URL[], parent)
        }
        new GroovyClassLoader(parent).parseClass(source, className + '.groovy')
    }

    private double timePerCall(Template template) {
        long start = System.nanoTime()
        MEASURED_RENDERS.times {
            renderOnce(template)
        }
        long elapsed = System.nanoTime() - start
        elapsed / (double) (MEASURED_RENDERS * CALLS_PER_RENDER)
    }

    private static void renderOnce(Template template) {
        StringWriter out = new StringWriter()
        template.make().writeTo(out)
    }

    private static void report(String label, List<Double> runs) {
        println String.format('  %-16s median %7.1f ns/call   min %7.1f   max %7.1f',
                label, median(runs), runs.min(), runs.max())
    }

    private static int intFromEnv(String name, int fallback) {
        String value = System.getenv(name)
        value ? value as int : fallback
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.sort(false)
        int middle = (sorted.size() / 2) as int
        sorted.size() % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2.0d
    }

    /**
     * @param compileStatic whether the page gives up dynamic resolution, which is what allows its tag
     *        expressions to be compiled into invocations
     */
    private static String page(boolean compileStatic) {
        StringBuilder markup = new StringBuilder()
        if (compileStatic) {
            markup.append('<%@ page compileStatic="true" %>')
        }
        CALLS_PER_RENDER.times { int i ->
            markup.append("\${g.createLink(controller: 'book', action: 'show', id: ${i})}")
        }
        markup.toString()
    }
}

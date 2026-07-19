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
package grails.util

import org.grails.exceptions.reporting.DefaultStackTraceFilterer
import org.grails.exceptions.reporting.StackTraceFilterer
import spock.lang.Specification

import java.lang.reflect.Field

/**
 * Verifies that {@link GrailsUtil#initializeStackFilterer(StackTraceFilterer)} installs the given
 * filterer for {@link GrailsUtil#deepSanitize}, {@link GrailsUtil#sanitizeRootCause} and
 * {@link GrailsUtil#printSanitizedStackTrace}, and that the pre-initialization fallback (a
 * {@link DefaultStackTraceFilterer}) is used until then. Config-driven resolution (the configured
 * class + {@code logFullStackTraceOnFilter}) now happens in
 * {@code org.apache.grails.core.GrailsBootstrapRegistryInitializer}, covered separately by
 * {@code GrailsBootstrapRegistryInitializerSpec}.
 */
class GrailsUtilStackFiltererSpec extends Specification {

    StackTraceFilterer previous

    def setup() {
        previous = currentFilterer()
        setFilterer(fallbackFilterer())
    }

    def cleanup() {
        setFilterer(previous)
    }

    def 'deepSanitize does not throw before initializeStackFilterer is called'() {
        when:
        GrailsUtil.deepSanitize(new RuntimeException('boom'))

        then:
        noExceptionThrown()
    }

    def 'initializeStackFilterer is a no-op when filterer is null'() {
        when:
        GrailsUtil.initializeStackFilterer(null)

        then:
        currentFilterer().is(fallbackFilterer())
    }

    def 'initializeStackFilterer installs the given filterer'() {
        given:
        def filterer = new RecordingStackTraceFilterer()

        when:
        GrailsUtil.initializeStackFilterer(filterer)
        GrailsUtil.deepSanitize(new RuntimeException('boom'))

        then:
        currentFilterer().is(filterer)
        filterer.recursiveCalls == 1
    }

    def 'last initializeStackFilterer call wins when invoked more than once'() {
        given:
        def first = new RecordingStackTraceFilterer()
        def second = new RecordingStackTraceFilterer()

        when:
        GrailsUtil.initializeStackFilterer(first)
        GrailsUtil.initializeStackFilterer(second)

        then:
        currentFilterer().is(second)
    }

    def 'installed DefaultStackTraceFilterer honours logFullStackTraceOnFilter=false'() {
        given: 'captured System.err'
        def originalErr = System.err
        def baos = new ByteArrayOutputStream()
        System.setErr(new PrintStream(baos, true))

        and: 'a filterer with the side-effect emission disabled'
        def quietFilterer = new DefaultStackTraceFilterer()
        quietFilterer.logFullStackTraceOnFilter = false

        when:
        GrailsUtil.initializeStackFilterer(quietFilterer)
        GrailsUtil.deepSanitize(exceptionWithApplicationFrame())

        then: "no 'Full Stack Trace:' entry is emitted"
        System.err.flush()
        !baos.toString().contains(StackTraceFilterer.FULL_STACK_TRACE_MESSAGE)

        cleanup:
        System.setErr(originalErr)
    }

    def 'installed DefaultStackTraceFilterer emits Full Stack Trace by default'() {
        given: 'captured System.err'
        def originalErr = System.err
        def baos = new ByteArrayOutputStream()
        System.setErr(new PrintStream(baos, true))

        and: 'a filterer with the default (enabled) side-effect emission'
        def loudFilterer = new DefaultStackTraceFilterer()

        when:
        GrailsUtil.initializeStackFilterer(loudFilterer)
        GrailsUtil.deepSanitize(exceptionWithApplicationFrame())

        then: "a 'Full Stack Trace:' entry is emitted -- the positive control proving the negative case above is meaningful"
        System.err.flush()
        baos.toString().contains(StackTraceFilterer.FULL_STACK_TRACE_MESSAGE)

        cleanup:
        System.setErr(originalErr)
    }

    private static RuntimeException exceptionWithApplicationFrame() {
        def exception = new RuntimeException('boom')
        exception.stackTrace = [
                new StackTraceElement('test.FooController', 'show', 'FooController.groovy', 6),
                new StackTraceElement('java.lang.reflect.Method', 'invoke', 'Method.java', 580)
        ] as StackTraceElement[]
        exception
    }

    private static StackTraceFilterer currentFilterer() {
        filtererField().get(null) as StackTraceFilterer
    }

    private static void setFilterer(StackTraceFilterer filterer) {
        filtererField().set(null, filterer)
    }

    private static StackTraceFilterer fallbackFilterer() {
        Field field = GrailsUtil.getDeclaredField('FALLBACK_FILTERER')
        field.accessible = true
        field.get(null) as StackTraceFilterer
    }

    private static Field filtererField() {
        Field field = GrailsUtil.getDeclaredField('stackFilterer')
        field.accessible = true
        field
    }

    static class RecordingStackTraceFilterer implements StackTraceFilterer {
        int singleCalls = 0
        int recursiveCalls = 0

        Throwable filter(Throwable source) { singleCalls++; source }
        Throwable filter(Throwable source, boolean recursive) { recursiveCalls++; source }
        void addInternalPackage(String name) {}
        void setCutOffPackage(String cutOffPackage) {}
        void setShouldFilter(boolean shouldFilter) {}
    }
}

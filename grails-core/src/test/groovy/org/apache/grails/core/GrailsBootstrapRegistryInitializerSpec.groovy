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
package org.apache.grails.core

import grails.config.Settings
import grails.util.GrailsUtil
import org.grails.exceptions.reporting.DefaultStackTraceFilterer
import org.grails.exceptions.reporting.StackTraceFilterer
import org.springframework.boot.bootstrap.DefaultBootstrapContext
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.env.MapPropertySource
import spock.lang.Specification

import java.lang.reflect.Field

/**
 * Verifies that {@link GrailsBootstrapRegistryInitializer} resolves a {@link StackTraceFilterer} from
 * the {@code ApplicationContext} environment -- honouring
 * {@link Settings#SETTING_LOGGING_STACKTRACE_FILTER_CLASS} and
 * {@link Settings#SETTING_LOG_FULL_STACKTRACE_ON_FILTER} -- promotes it as a singleton bean under
 * {@link StackTraceFilterer#BEAN_NAME}, and installs it in {@link GrailsUtil} before the context
 * refreshes. Exercised through the public {@link org.springframework.boot.bootstrap.BootstrapRegistryInitializer#initialize}
 * entry point, the same way Spring Boot invokes it during a real bootstrap.
 */
class GrailsBootstrapRegistryInitializerSpec extends Specification {

    StackTraceFilterer previousFilterer

    def setup() {
        previousFilterer = currentFilterer()
    }

    def cleanup() {
        setFilterer(previousFilterer)
    }

    def 'promotes a default StackTraceFilterer bean and installs it in GrailsUtil when no config is set'() {
        given:
        def context = contextWithProperties([:])

        when:
        closeBootstrapContext(context)

        then:
        def bean = context.getBeanFactory().getBean(StackTraceFilterer.BEAN_NAME, StackTraceFilterer)
        bean instanceof DefaultStackTraceFilterer
        currentFilterer().is(bean)
    }

    def 'promotes the class configured via grails.logging.stackTraceFiltererClass'() {
        given:
        def context = contextWithProperties([
                (Settings.SETTING_LOGGING_STACKTRACE_FILTER_CLASS): RecordingStackTraceFilterer.name
        ])

        when:
        closeBootstrapContext(context)

        then:
        def bean = context.getBeanFactory().getBean(StackTraceFilterer.BEAN_NAME, StackTraceFilterer)
        bean instanceof RecordingStackTraceFilterer
        currentFilterer().is(bean)
    }

    def 'falls back to the default filterer when the configured class cannot be loaded'() {
        given:
        def context = contextWithProperties([
                (Settings.SETTING_LOGGING_STACKTRACE_FILTER_CLASS): 'not.a.real.ClassName'
        ])

        when:
        closeBootstrapContext(context)

        then:
        def bean = context.getBeanFactory().getBean(StackTraceFilterer.BEAN_NAME, StackTraceFilterer)
        bean instanceof DefaultStackTraceFilterer
    }

    def 'falls back to the default filterer when the configured class does not implement StackTraceFilterer'() {
        given:
        def context = contextWithProperties([
                (Settings.SETTING_LOGGING_STACKTRACE_FILTER_CLASS): String.name
        ])

        when:
        closeBootstrapContext(context)

        then:
        def bean = context.getBeanFactory().getBean(StackTraceFilterer.BEAN_NAME, StackTraceFilterer)
        bean instanceof DefaultStackTraceFilterer
    }

    def 'propagates logFullStackTraceOnFilter=false to the promoted DefaultStackTraceFilterer'() {
        given:
        def context = contextWithProperties([
                (Settings.SETTING_LOG_FULL_STACKTRACE_ON_FILTER): 'false'
        ])
        def originalErr = System.err
        def baos = new ByteArrayOutputStream()
        System.setErr(new PrintStream(baos, true))

        when:
        closeBootstrapContext(context)
        GrailsUtil.deepSanitize(exceptionWithApplicationFrame())

        then: "no 'Full Stack Trace:' entry is emitted"
        System.err.flush()
        !baos.toString().contains(StackTraceFilterer.FULL_STACK_TRACE_MESSAGE)

        cleanup:
        System.setErr(originalErr)
    }

    def 'defaults logFullStackTraceOnFilter to true on the promoted DefaultStackTraceFilterer'() {
        given:
        def context = contextWithProperties([:])
        def originalErr = System.err
        def baos = new ByteArrayOutputStream()
        System.setErr(new PrintStream(baos, true))

        when:
        closeBootstrapContext(context)
        GrailsUtil.deepSanitize(exceptionWithApplicationFrame())

        then: 'the positive control proving the negative case above is meaningful'
        System.err.flush()
        baos.toString().contains(StackTraceFilterer.FULL_STACK_TRACE_MESSAGE)

        cleanup:
        System.setErr(originalErr)
    }

    private static void closeBootstrapContext(GenericApplicationContext context) {
        def registry = new DefaultBootstrapContext()
        new GrailsBootstrapRegistryInitializer().initialize(registry)
        registry.close(context)
    }

    private static GenericApplicationContext contextWithProperties(Map<String, String> properties) {
        def context = new GenericApplicationContext()
        if (properties) {
            context.environment.propertySources.addFirst(new MapPropertySource('test', properties as Map<String, Object>))
        }
        context
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

    private static Field filtererField() {
        Field field = GrailsUtil.getDeclaredField('stackFilterer')
        field.accessible = true
        field
    }

    static class RecordingStackTraceFilterer implements StackTraceFilterer {
        Throwable filter(Throwable source) { source }
        Throwable filter(Throwable source, boolean recursive) { source }
        void addInternalPackage(String name) {}
        void setCutOffPackage(String cutOffPackage) {}
        void setShouldFilter(boolean shouldFilter) {}
    }
}

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
package grails.boot.config

import org.springframework.boot.bootstrap.DefaultBootstrapContext
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import spock.lang.Specification

/**
 * Covers an image colouring its output when it is being watched at a terminal.
 *
 * <p>Spring Boot decides by asking for the console, and an image answers that it has none even when
 * it has one -- so the same application whose start-up is coloured under bootRun arrives plain once
 * it is built. What the environment names as the terminal is read instead, which an image does
 * carry. It cannot tell output being watched from output being redirected -- nothing in an image
 * can -- but it does tell a shell from a build, a container or a service manager.</p>
 */
class GrailsEnvironmentPostProcessorAnsiSpec extends Specification {

    private static final String ANSI = 'spring.output.ansi.enabled'

    StandardEnvironment environment = new StandardEnvironment()

    private String ansiAfter(boolean image, String terminal) {
        new Processor(image, terminal).colourTheOutputOfAnImageThatHasATerminal(environment)
        environment.getProperty(ANSI)
    }

    void 'an image at a terminal colours its output'() {
        expect:
            ansiAfter(true, 'xterm-256color') == 'always'
    }

    void 'an image where nothing names a terminal stays plain'() {
        expect: 'a build, a container or a service manager, whose output is only ever read later'
            ansiAfter(true, null) == null
    }

    void 'a terminal that cannot colour is left alone'() {
        expect:
            ansiAfter(true, 'dumb') == null
    }

    void 'running on a JVM is left to Spring Boot'() {
        expect: 'where asking for the console works, and answers for pipes too'
            ansiAfter(false, 'xterm-256color') == null
    }

    void 'an application that has said either way keeps what it said'() {
        given:
            environment.propertySources.addFirst(new MapPropertySource('test', [(ANSI): 'never']))

        expect:
            ansiAfter(true, 'xterm-256color') == 'never'
    }

    /** Stands in for the two things only a run can answer. */
    static class Processor extends GrailsEnvironmentPostProcessor {

        private final boolean image
        private final String terminal

        Processor(boolean image, String terminal) {
            super(new DefaultBootstrapContext())
            this.image = image
            this.terminal = terminal
        }

        @Override
        protected boolean isImage() {
            image
        }

        @Override
        protected String terminal() {
            terminal
        }
    }
}

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
package grails.boot

import spock.lang.Specification

/**
 * Covers reading an optional library's version without setting the library going.
 *
 * <p>A version is read <em>about</em> a library, not <em>from</em> it. Initialising the class to
 * find one lets it do whatever it does on the way: Spring Security logs
 * {@code You are running with Spring Security Core ...} from its static initialiser, and that line
 * arrived in the middle of the banner -- between the mark and the very versions it was being read
 * for -- because the lookup had initialised it.</p>
 */
class GrailsBannerVersionLookupSpec extends Specification {

    /**
     * Where the record is kept, so that reading it is not itself what sets {@link Noisy} going.
     * Asking the class under test whether it was initialised initialises it.
     */
    static class Record {

        static boolean noisyRan = false
    }

    /** Stands in for a library that does something when it starts. */
    static class Noisy {

        static {
            Record.noisyRan = true
        }
    }

    void 'reading a version does not set the library going'() {
        when:
            GrailsBanner.findVersion(Noisy.name)

        then: 'loaded, but its static initialiser has not run'
            !Record.noisyRan

        and: 'and it really would have run, had the class been initialised'
            Class.forName(Noisy.name, true, getClass().classLoader)
            Record.noisyRan
    }

    void 'a library that is not there is unknown rather than an error'() {
        expect:
            GrailsBanner.findVersion('com.example.NotOnTheClasspath') == 'unknown'
    }

    void 'a library with no version in its manifest is unknown'() {
        expect: 'test classes carry no Implementation-Version, and that is not a failure'
            GrailsBanner.findVersion(Noisy.name) == 'unknown'
    }

    void 'a version recorded in a manifest is read'() {
        expect: 'Spock ships one, so this proves the lookup reads rather than always saying unknown'
            GrailsBanner.findVersion(Specification.name) ==~ /\d+\..*/
    }
}

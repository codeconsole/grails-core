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

import org.springframework.boot.ansi.AnsiColor
import org.springframework.boot.ansi.AnsiOutput
import org.springframework.core.env.StandardEnvironment
import spock.lang.Specification

/**
 * Covers the banner art being shown in the framework's colour.
 *
 * <p>The escapes are written only where colour has been enabled, and the width the versions beneath
 * are centred on is measured before they are added -- otherwise they would count towards it and
 * push the versions off centre by as many characters as the colour cost.</p>
 */
class GrailsBannerColourSpec extends Specification {

    GrailsBanner banner = new GrailsBanner()

    void cleanup() {
        AnsiOutput.setEnabled(AnsiOutput.Enabled.DETECT)
    }

    private String printed() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()
        banner.printBanner(new StandardEnvironment(), GrailsBannerColourSpec, new PrintStream(bytes))
        bytes.toString()
    }

    void 'the art is yellow where colour is on'() {
        given:
            AnsiOutput.setEnabled(AnsiOutput.Enabled.ALWAYS)

        expect:
            banner.colour('art').startsWith(AnsiOutput.encode(AnsiColor.YELLOW))
    }

    void 'the art is left as it stands where colour is off'() {
        given:
            AnsiOutput.setEnabled(AnsiOutput.Enabled.NEVER)

        expect: 'a redirected log, or an application that has turned colour off'
            banner.colour('art') == 'art'
    }

    void 'the banner carries the colour through to what is printed'() {
        given:
            AnsiOutput.setEnabled(AnsiOutput.Enabled.ALWAYS)

        expect:
            printed().contains(AnsiOutput.encode(AnsiColor.YELLOW))
    }

    void 'the versions stay where they were before the art was coloured'() {
        given:
            AnsiOutput.setEnabled(AnsiOutput.Enabled.NEVER)
            List<String> plain = versionLines(printed())

        when:
            AnsiOutput.setEnabled(AnsiOutput.Enabled.ALWAYS)
            List<String> coloured = versionLines(printed().replaceAll(/\[[0-9;]*m/, ''))

        then: 'the width is measured before the escapes are added, so they do not count towards it'
            coloured == plain
            !plain.isEmpty()
    }

    /** The centred lines beneath the art, which is where a mismeasured width would show. */
    private List<String> versionLines(String output) {
        output.readLines().findAll { it.contains('Grails:') || it.contains('Spring') }
    }
}

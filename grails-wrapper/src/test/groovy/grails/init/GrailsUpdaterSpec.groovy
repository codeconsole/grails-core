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
package grails.init

import spock.lang.Specification
import spock.lang.Unroll

class GrailsUpdaterSpec extends Specification {

    @Unroll
    def "remote wrapper URL must use HTTPS for #url"() {
        when:
        GrailsUpdater.createSecureRemoteUrl(url)

        then:
        def e = thrown(IOException)
        e.message == "Grails wrapper remote repository URLs must use HTTPS: ${url}"

        where:
        url << [
                'http://repo.example.test/maven-metadata.xml',
                'file:/tmp/repo/maven-metadata.xml',
        ]
    }

    def "remote wrapper URL accepts HTTPS"() {
        expect:
        GrailsUpdater.createSecureRemoteUrl('https://repo.example.test/maven-metadata.xml').toString() == 'https://repo.example.test/maven-metadata.xml'
    }

    def "remote wrapper redirect must stay on HTTPS"() {
        when:
        GrailsUpdater.resolveSecureRedirectUrl(new URI('https://repo.example.test/releases/maven-metadata.xml'), 'http://repo.example.test/releases/maven-metadata.xml')

        then:
        def e = thrown(IOException)
        e.message == 'Grails wrapper remote repository URLs must use HTTPS: http://repo.example.test/releases/maven-metadata.xml'
    }

    def "remote wrapper redirect accepts relative HTTPS target"() {
        expect:
        GrailsUpdater.resolveSecureRedirectUrl(new URI('https://repo.example.test/releases/maven-metadata.xml'), '../snapshots/maven-metadata.xml') == new URI('https://repo.example.test/snapshots/maven-metadata.xml')
    }

    def "remote wrapper redirect requires location header"() {
        when:
        GrailsUpdater.resolveSecureRedirectUrl(new URI('https://repo.example.test/releases/maven-metadata.xml'), null)

        then:
        def e = thrown(IOException)
        e.message == 'Redirect response is missing a Location header for Grails wrapper artifact: https://repo.example.test/releases/maven-metadata.xml'
    }
}

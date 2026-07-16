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

import java.nio.file.Path

import spock.lang.Specification
import spock.lang.TempDir
import uk.org.webcompere.systemstubs.SystemStubs

class GrailsWrapperRepoSpec extends Specification {

    @TempDir
    Path tempDir

    def cleanup() {
        System.clearProperty('grails.repo.url')
    }

    def 'system property repo override wins over environment variable and trims entries'() {
        given:
        System.setProperty('grails.repo.url', ' https://repo-one.example/releases ; ; /tmp/local-repo ')

        when:
        List<String> repos = null
        SystemStubs.withEnvironmentVariable('GRAILS_REPO_URL', 'https://ignored.example/releases').execute {
            repos = GrailsWrapperRepo.getOverriddenMavenRepos()
        }

        then:
        repos == ['https://repo-one.example/releases', '/tmp/local-repo']
    }

    def 'environment repo override uses the same split and trim rules'() {
        when:
        List<String> repos = null
        SystemStubs.withEnvironmentVariable('GRAILS_REPO_URL', ' https://repo-one.example/releases ; ; https://repo-two.example/releases ').execute {
            repos = GrailsWrapperRepo.getOverriddenMavenRepos()
        }

        then:
        repos == ['https://repo-one.example/releases', 'https://repo-two.example/releases']
    }

    def 'file URI repo override is treated as a local maven repository'() {
        given:
        Path localRepo = tempDir.resolve('local-repo')

        when:
        GrailsWrapperRepo repo = GrailsWrapperRepo.createGrailsWrapperRepo(localRepo.toUri().toString())

        then:
        repo.isFile
        repo.getUrl() == [localRepo.toFile().path, 'org', 'apache', 'grails', GrailsWrapperHome.CLI_COMBINED_PROJECT_NAME].join(File.separator)
        repo.getRootMetadataUrl().endsWith([GrailsWrapperHome.CLI_COMBINED_PROJECT_NAME, 'maven-metadata-local.xml'].join(File.separator))
    }

    def 'HTTP scheme repo override is rejected regardless of case'() {
        when:
        GrailsWrapperRepo.createGrailsWrapperRepo('HTTP://localhost/releases/')

        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'Grails wrapper remote repository URLs must use HTTPS: HTTP://localhost/releases/'
    }

    def 'malformed URL-shaped repo override is rejected rather than treated as a local path'() {
        when: 'a value that is clearly URL-shaped but not a parseable URI is supplied'
        GrailsWrapperRepo.createGrailsWrapperRepo('https://repo example/releases')

        then: 'it is classified as a broken remote override and rejected, not searched on the filesystem'
        def e = thrown(IllegalArgumentException)
        e.message.startsWith('Invalid Grails wrapper remote repository URL:')
    }

    def 'malformed local path override without a scheme separator remains a local repository'() {
        when: 'a non-URL local path that does not parse as a URI is supplied'
        GrailsWrapperRepo repo = GrailsWrapperRepo.createGrailsWrapperRepo('/tmp/local repo/releases')

        then: 'it is still treated as a local maven repository'
        repo.isFile
    }

    def 'malformed local path override with an interior scheme separator remains a local repository'() {
        when: 'an absolute local path whose interior contains "://" but has no leading scheme is supplied'
        GrailsWrapperRepo repo = GrailsWrapperRepo.createGrailsWrapperRepo('/tmp/cache://local repo/releases')

        then: 'the interior separator is not treated as a URL scheme, so it stays a local repository'
        repo.isFile
    }
}

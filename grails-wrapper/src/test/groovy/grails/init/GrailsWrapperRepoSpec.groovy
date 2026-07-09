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

    def 'HTTP scheme repo override is treated as a remote repository regardless of case'() {
        when:
        GrailsWrapperRepo repo = GrailsWrapperRepo.createGrailsWrapperRepo('HTTP://repo.example.test/releases/')

        then:
        !repo.isFile
        repo.getUrl() == 'HTTP://repo.example.test/releases/org/apache/grails/grails-cli'
        repo.getRootMetadataUrl() == 'HTTP://repo.example.test/releases/org/apache/grails/grails-cli/maven-metadata.xml'
    }
}

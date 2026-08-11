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
package org.grails.gradle.plugin.views.gsp

import org.grails.gradle.plugin.core.GradleSpecification

/**
 * What the tag library index is wired into, and what a project declaring no tag libraries has to do
 * about it, which is nothing: the generator runs in a forked process against the project's own compile
 * classpath, so a project with no tag libraries to describe must not fork it at all.
 *
 * @since 8.0
 */
class TagLibraryIndexWiringFunctionalSpec extends GradleSpecification {

    def "the index is on the classpath of everything that resolves tag calls against it"() {
        given:
        setupTestResourceProject('taglib-index-wiring')

        when:
        def result = executeTask('inspectTagLibraryIndexWiring')

        then: 'this project resolves a call to a tag it declares as it compiles'
        result.output.contains('COMPILE_SEES_INDEX=true')

        and: 'so does a page of this project, compiled in a process of its own'
        result.output.contains('PAGES_SEE_INDEX=true')

        and: 'and it travels with the artifact, so a project depending on this one resolves them too'
        result.output.contains('INDEX_IS_A_RESOURCE=true')
    }

    def "a project with no tag libraries does not fork the generator"() {
        given: 'the generator is only on the compile classpath of a project that has tag libraries'
        setupTestResourceProject('taglib-index-wiring')

        when:
        def result = executeTask('processResources')

        then: 'so forking it here would fail the build of a project with nothing to describe'
        assertTaskSuccess('processResources', result)
    }
}

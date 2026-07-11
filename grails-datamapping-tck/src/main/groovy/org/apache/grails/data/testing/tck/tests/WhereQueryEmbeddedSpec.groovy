/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.grails.data.testing.tck.tests

import org.apache.grails.data.testing.tck.base.GrailsDataTckSpec
import org.apache.grails.data.testing.tck.domains.ExternalRef
import org.apache.grails.data.testing.tck.domains.WorkItem
import spock.lang.Issue

/**
 * Tests for where {} queries against properties of embedded components.
 */
class WhereQueryEmbeddedSpec extends GrailsDataTckSpec {

    void setupSpec() {
        manager.registerDomainClasses(WorkItem)
    }

    private void createWorkItems() {
        new WorkItem(description: 'first', extRef1: new ExternalRef(provider: 'SAP', value: 'ABC-123')).save(flush: true, failOnError: true)
        new WorkItem(description: 'second', extRef1: new ExternalRef(provider: 'Jira', value: 'XYZ-456')).save(flush: true, failOnError: true)
    }

    @Issue('https://github.com/apache/grails-core/issues/15955')
    void 'where query with like on inherited embedded component property'() {
        given:
        createWorkItems()

        when:
        String search = 'ABC'
        def query = WorkItem.where {}
        query = query.where {
            extRef1.value =~ "%${search}%"
        }
        def results = query.list()

        then:
        results.size() == 1
        results[0].description == 'first'
    }

    @Issue('https://github.com/apache/grails-core/issues/15955')
    void 'where query with equals on inherited embedded component property'() {
        given:
        createWorkItems()

        when:
        def results = WorkItem.where {
            extRef1.provider == 'SAP'
        }.list()

        then:
        results.size() == 1
        results[0].description == 'first'
    }

    @Issue('https://github.com/apache/grails-core/issues/15955')
    void 'where query with conjunction on embedded component property'() {
        given:
        createWorkItems()

        when:
        def results = WorkItem.where {
            description == 'first' && extRef1.value =~ '%ABC%'
        }.list()

        then:
        results.size() == 1
        results[0].description == 'first'
    }

    @Issue('https://github.com/apache/grails-core/issues/15955')
    void 'where query with disjunction on embedded component property'() {
        given:
        createWorkItems()

        when:
        def results = WorkItem.where {
            description == 'none' || extRef1.provider == 'SAP'
        }.list()

        then:
        results.size() == 1
        results[0].description == 'first'
    }

    @Issue('https://github.com/apache/grails-core/issues/15955')
    void 'where query with multiple dotted predicates on the same embedded component'() {
        given:
        createWorkItems()

        when:
        def results = WorkItem.where {
            extRef1.provider == 'SAP' && extRef1.value =~ '%ABC%'
        }.list()

        then:
        results.size() == 1
        results[0].description == 'first'
    }

    @Issue('https://github.com/apache/grails-core/issues/15955')
    void 'where query with an embedded component predicate in a nested junction'() {
        given:
        createWorkItems()

        when:
        def results = WorkItem.where {
            (description == 'none' || extRef1.provider == 'Jira') && description == 'second'
        }.list()

        then:
        results.size() == 1
        results[0].description == 'second'
    }

    @Issue('https://github.com/apache/grails-core/issues/15955')
    void 'where query with negated embedded component predicate'() {
        given:
        createWorkItems()

        when:
        def results = WorkItem.where {
            !(extRef1.provider == 'SAP')
        }.list()

        then:
        results.size() == 1
        results[0].description == 'second'
    }

    @Issue('https://github.com/apache/grails-core/issues/15955')
    void 'where query with inList on embedded component property'() {
        given:
        createWorkItems()

        when:
        def results = WorkItem.where {
            extRef1.provider in ['SAP', 'Oracle'] && description != 'none'
        }.list()

        then:
        results.size() == 1
        results[0].description == 'first'
    }

    @Issue('https://github.com/apache/grails-core/issues/15955')
    void 'where query with an embedded association block'() {
        given:
        createWorkItems()

        when: 'the embedded component is addressed with an association block, with an outer condition matching both items'
        def results = WorkItem.where {
            description != 'none' && extRef1 { provider == 'SAP' }
        }.list()

        then:
        results.size() == 1
        results[0].description == 'first'
    }

    @Issue('https://github.com/apache/grails-core/issues/15955')
    void 'count on a where query with an embedded component predicate in a junction'() {
        given:
        createWorkItems()

        when: 'the queries are built outside the assertion so the where DSL transformation applies'
        def conjunctionQuery = WorkItem.where {
            description == 'first' && extRef1.value =~ '%ABC%'
        }
        def disjunctionQuery = WorkItem.where {
            description == 'none' || extRef1.provider == 'Jira'
        }

        then:
        conjunctionQuery.count() == 1
        disjunctionQuery.count() == 1
    }

    @Issue('https://github.com/apache/grails-core/issues/15955')
    void 'criteria query with an embedded association block inside a disjunction'() {
        given:
        createWorkItems()

        when: 'the embedded block is nested in a junction, translating through AssociationQuery'
        def results = WorkItem.createCriteria().list {
            or {
                eq('description', 'none')
                extRef1 {
                    eq('provider', 'SAP')
                }
            }
        }

        then:
        results.size() == 1
        results[0].description == 'first'
    }

    @Issue('https://github.com/apache/grails-core/issues/15955')
    void 'where query with a disjunction inside an embedded association block'() {
        given:
        createWorkItems()

        when: 'the embedded block itself contains a disjunction'
        def results = WorkItem.where {
            extRef1 { provider == 'Oracle' || value =~ '%ABC%' }
        }.list()

        then:
        results.size() == 1
        results[0].description == 'first'
    }

    @Issue('https://github.com/apache/grails-core/issues/15955')
    void 'criteria query with a disjunction inside an embedded association block'() {
        given:
        createWorkItems()

        when: 'the embedded block itself contains a disjunction'
        def results = WorkItem.createCriteria().list {
            extRef1 {
                or {
                    eq('provider', 'Oracle')
                    like('value', '%ABC%')
                }
            }
        }

        then:
        results.size() == 1
        results[0].description == 'first'
    }

    @Issue('https://github.com/apache/grails-core/issues/15955')
    void 'criteria query with an embedded association block inside a conjunction'() {
        given:
        createWorkItems()

        when: 'the embedded block itself carries more than one predicate'
        def results = WorkItem.createCriteria().list {
            and {
                like('description', 'fir%')
                extRef1 {
                    eq('provider', 'SAP')
                    like('value', '%ABC%')
                }
            }
        }

        then:
        results.size() == 1
        results[0].description == 'first'
    }
}

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
package grails.gorm.tests

import grails.gorm.annotation.Entity
import org.grails.datastore.mapping.dirty.checking.DirtyCheckable

class Issue16349Spec extends HibernateGormDatastoreSpec {

    void setupSpec() {
        manager.registerDomainClasses(Issue16349IdentityBook, Issue16349IncrementBook)
    }

    void 'identity generator: save does not issue an extra update'() {
        given:
        def statistics = manager.sessionFactory.statistics
        statistics.statisticsEnabled = true
        statistics.clear()

        when:
        def book = new Issue16349IdentityBook(title: 'original').save(flush: true, failOnError: true)

        then:
        book.version == 0
        statistics.entityInsertCount == 1
        statistics.entityUpdateCount == 0
    }

    void 'increment generator: save does not issue an extra update'() {
        given:
        def statistics = manager.sessionFactory.statistics
        statistics.statisticsEnabled = true
        statistics.clear()

        when:
        def book = new Issue16349IncrementBook(title: 'original').save(flush: true, failOnError: true)

        then:
        book.version == 0
        statistics.entityInsertCount == 1
        statistics.entityUpdateCount == 0
    }

    void 'increment generator: manually activating dirty-check tracking before flush avoids the extra update'() {
        given:
        def statistics = manager.sessionFactory.statistics
        statistics.statisticsEnabled = true
        statistics.clear()
        def book = new Issue16349IncrementBook(title: 'original')

        when:
        (book as DirtyCheckable).trackChanges()
        book.save(flush: true, failOnError: true)

        then:
        book.version == 0
        statistics.entityInsertCount == 1
        statistics.entityUpdateCount == 0
    }
}

@Entity
class Issue16349IdentityBook {
    Long id
    Long version
    String title
}

@Entity
class Issue16349IncrementBook {
    Long id
    Long version
    String title

    static mapping = {
        id generator: 'increment'
    }
}

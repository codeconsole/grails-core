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
package org.grails.datastore.gorm.mongo

import ch.qos.logback.classic.Level
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.model.IndexOptions
import grails.gorm.annotation.Entity
import org.bson.Document
import spock.lang.Shared

import org.apache.grails.testing.mongo.AutoStartedMongoSpec
import org.grails.datastore.mapping.core.connections.ConnectionSource
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.mongo.UndeclaredIndex

/**
 * {@link MongoDatastore#findUndeclaredIndexes()} reports the indexes on mapped collections whose keys no domain
 * class declares, and {@link MongoDatastore#dropUndeclaredIndexes()} drops them.
 */
class UndeclaredIndexesSpec extends AutoStartedMongoSpec {

    @Shared
    MongoClient realClient

    @Override
    boolean shouldInitializeDatastore() {
        false
    }

    void setupSpec() {
        realClient = MongoClients.create(dbContainer.getReplicaSetUrl('undeclaredIndexesDb'))
    }

    void cleanupSpec() {
        realClient?.close()
    }

    private Map config(String database, Map extra = [:]) {
        ['grails.mongodb.url'             : dbContainer.getReplicaSetUrl(database),
         'grails.gorm.default.constraints': { '*'(nullable: true) }] + extra
    }

    private MongoDatastore datastore(String database) {
        new MongoDatastore(config(database), UndeclaredIndexesThing, UndeclaredIndexesAnimal, UndeclaredIndexesDog,
                UndeclaredIndexesSharedA, UndeclaredIndexesSharedB).tap { start() }
    }

    private Set<String> indexNames(String database, String collection) {
        realClient.getDatabase(database).getCollection(collection).listIndexes()*.getString('name') as Set
    }

    private static Set<List> described(List<UndeclaredIndex> indexes) {
        indexes.collect { [it.collection(), it.name(), [*: it.key()]] } as Set
    }

    void "test the indexes that no domain class declares are reported, and only those"() {
        given:
        def datastore = datastore('undeclaredFindDb')
        def db = realClient.getDatabase('undeclaredFindDb')

        and: "indexes created outside the mappings, one of them on a collection no domain class maps"
        db.getCollection('undeclaredIndexesThing').createIndex(new Document('code', 1))
        db.getCollection('undeclaredIndexesThing').createIndex(new Document('name', -1))
        db.getCollection('undeclaredIndexesAnimal').createIndex(new Document('breed', 1).append('name', 1))
        db.getCollection('undeclaredIndexesUnmapped').createIndex(new Document('anything', 1))

        and: "declared keys that carry a name and options of their own"
        db.getCollection('undeclaredIndexesThing').dropIndex('code_1_name_-1')
        db.getCollection('undeclaredIndexesThing').createIndex(new Document('code', 1).append('name', -1),
                new IndexOptions().name('byCodeAndName'))
        db.getCollection('undeclaredIndexesThing').dropIndex('name_1')
        db.getCollection('undeclaredIndexesThing').createIndex(new Document('name', 1), new IndexOptions().sparse(true))

        when:
        def undeclared = datastore.findUndeclaredIndexes()

        then: "a key pattern no class mapped to the collection declares, whatever the direction or order"
        described(undeclared) == [
                ['undeclaredIndexesThing', 'code_1', [code: 1]],
                ['undeclaredIndexesThing', 'name_-1', [name: -1]],
                ['undeclaredIndexesAnimal', 'breed_1_name_1', [breed: 1, name: 1]]
        ] as Set
        undeclared.every { it.database() == 'undeclaredFindDb' && it.definition().getString('name') == it.name() }

        and: "nothing was dropped"
        indexNames('undeclaredFindDb', 'undeclaredIndexesThing').containsAll(['code_1', 'name_-1'])

        cleanup:
        datastore?.close()
    }

    void "test the declarations of every class mapped to a collection count, and a text index matches its declaration"() {
        given:
        def datastore = datastore('undeclaredDeclaredDb')

        expect: "the subclass's index on its root's collection, both classes sharing a collection, the text index, and _id"
        indexNames('undeclaredDeclaredDb', 'undeclaredIndexesAnimal') == ['_id_', 'name_1', 'breed_1'] as Set
        indexNames('undeclaredDeclaredDb', 'undeclaredIndexesShared') == ['_id_', 'alpha_1', 'beta_1__id_1'] as Set
        'description_text' in indexNames('undeclaredDeclaredDb', 'undeclaredIndexesThing')
        datastore.findUndeclaredIndexes() == []

        cleanup:
        datastore?.close()
    }

    void "test dropping the undeclared indexes leaves the declared ones and unmapped collections alone"() {
        given:
        def log = new CapturedLog('org.grails.datastore.mapping.mongo', Level.INFO)
        def datastore = datastore('undeclaredDropDb')
        def db = realClient.getDatabase('undeclaredDropDb')
        db.getCollection('undeclaredIndexesThing').createIndex(new Document('code', 1))
        db.getCollection('undeclaredIndexesShared').createIndex(new Document('gamma', 1))
        db.getCollection('undeclaredIndexesUnmapped').createIndex(new Document('anything', 1))

        when:
        def dropped = datastore.dropUndeclaredIndexes()

        then: "exactly the undeclared indexes are gone"
        described(dropped) == [
                ['undeclaredIndexesThing', 'code_1', [code: 1]],
                ['undeclaredIndexesShared', 'gamma_1', [gamma: 1]]
        ] as Set
        indexNames('undeclaredDropDb', 'undeclaredIndexesThing') ==
                ['_id_', 'name_1', 'description_text', 'code_1_name_-1'] as Set
        indexNames('undeclaredDropDb', 'undeclaredIndexesShared') == ['_id_', 'alpha_1', 'beta_1__id_1'] as Set
        indexNames('undeclaredDropDb', 'undeclaredIndexesUnmapped') == ['_id_', 'anything_1'] as Set

        and: "each drop is logged with the keys no class declares"
        log.events*.formattedMessage.contains('Dropped index [code_1] on collection [undeclaredIndexesThing] of ' +
                'database [undeclaredDropDb]: no domain class declares its keys {"code": 1}')

        and: "a second run has nothing left to drop"
        datastore.dropUndeclaredIndexes() == []
        datastore.findUndeclaredIndexes() == []

        cleanup:
        datastore?.close()
        log?.close()
    }

    void "test dropping a reviewed list skips an index or a collection that has gone since"() {
        given:
        def datastore = datastore('undeclaredReviewedDb')
        def db = realClient.getDatabase('undeclaredReviewedDb')
        db.getCollection('undeclaredIndexesThing').createIndex(new Document('code', 1))
        db.getCollection('undeclaredIndexesThing').createIndex(new Document('name', -1))
        db.getCollection('undeclaredIndexesShared').createIndex(new Document('gamma', 1))
        def reviewed = datastore.findUndeclaredIndexes()

        and: "an index and a whole collection go before the reviewed list is dropped"
        db.getCollection('undeclaredIndexesThing').dropIndex('name_-1')
        db.getCollection('undeclaredIndexesShared').drop()

        when:
        def dropped = datastore.dropUndeclaredIndexes(reviewed)

        then:
        reviewed*.name() as Set == ['code_1', 'name_-1', 'gamma_1'] as Set
        dropped*.name() == ['code_1']
        !('code_1' in indexNames('undeclaredReviewedDb', 'undeclaredIndexesThing'))

        cleanup:
        datastore?.close()
    }

    void "test each named connection reports the collections of its own database"() {
        given:
        def datastore = new MongoDatastore(config('undeclaredDefaultDb', [
                'grails.mongodb.connections': [reporting: [url: dbContainer.getReplicaSetUrl('undeclaredReportingDb')]]
        ]), UndeclaredIndexesConnectionThing)
        realClient.getDatabase('undeclaredReportingDb').getCollection('undeclaredIndexesConnectionThing')
                .createIndex(new Document('code', 1))

        expect:
        datastore.findUndeclaredIndexes() == []
        (datastore.getDatastoreForConnection('reporting') as MongoDatastore).findUndeclaredIndexes()*.name() == ['code_1']

        cleanup:
        datastore?.close()
    }
}

@Entity
class UndeclaredIndexesThing {

    String name
    String code
    String description

    static mapping = {
        name index: true
        description index: true, indexAttributes: [type: 'text']
        compoundIndex([code: 1, name: -1])
    }

    static constraints = {
        name blank: false
    }
}

@Entity
class UndeclaredIndexesAnimal {

    String name

    static mapping = {
        name index: true
    }
}

@Entity
class UndeclaredIndexesDog extends UndeclaredIndexesAnimal {

    String breed

    static mapping = {
        breed index: true
    }
}

@Entity
class UndeclaredIndexesSharedA {

    String alpha

    static mapping = {
        collection 'undeclaredIndexesShared'
        alpha index: true
    }
}

@Entity
class UndeclaredIndexesSharedB {

    String beta

    static mapping = {
        collection 'undeclaredIndexesShared'
        compoundIndex([beta: 1, _id: 1])
    }
}

@Entity
class UndeclaredIndexesConnectionThing {

    String name

    static mapping = {
        collection 'undeclaredIndexesConnectionThing'
        connection ConnectionSource.ALL
        name index: true
    }
}

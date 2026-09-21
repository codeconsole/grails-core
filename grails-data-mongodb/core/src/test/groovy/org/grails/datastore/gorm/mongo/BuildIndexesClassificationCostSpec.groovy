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

import java.util.concurrent.atomic.AtomicInteger

import ch.qos.logback.classic.Level
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import grails.gorm.annotation.Entity
import spock.lang.AutoCleanup
import spock.lang.Shared

import org.apache.grails.testing.mongo.AutoStartedMongoSpec
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.datastore.mapping.mongo.MongoDatastore

/**
 * Telling a created index from one that was already there costs a {@code listIndexes} per indexed
 * collection, and the only thing it feeds is the summary line. An application that does not log that line
 * does not pay for it.
 */
class BuildIndexesClassificationCostSpec extends AutoStartedMongoSpec {

    static final String DATABASE = 'classificationCostDb'

    @Shared
    @AutoCleanup
    MongoDatastore datastore

    @Shared
    MongoClient countingClient

    @Shared
    AtomicInteger listings = new AtomicInteger()

    @Override
    boolean shouldInitializeDatastore() {
        false
    }

    void setupSpec() {
        countingClient = MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(dbContainer.getReplicaSetUrl(DATABASE)))
                .addCommandListener(new CommandListener() {
                    @Override
                    void commandStarted(CommandStartedEvent event) {
                        if (event.commandName == 'listIndexes') {
                            listings.incrementAndGet()
                        }
                    }
                })
                .build())
    }

    void cleanupSpec() {
        countingClient?.close()
    }

    void "test the existing indexes are listed only when the summary will be logged"() {
        when: "the datastore builds its indexes with the summary not logged"
        def quiet = new CapturedLog('org.grails.datastore.mapping.mongo', Level.WARN)
        datastore = new MongoDatastore(countingClient,
                DatastoreUtils.createPropertyResolver(['grails.mongodb.databaseName': DATABASE]), ClassifiedThing)
        quiet.close()

        then: "nothing was listed"
        listings.get() == 0

        when: "the same build runs with the summary logged"
        def logged = new CapturedLog('org.grails.datastore.mapping.mongo', Level.INFO)
        datastore.buildIndex()

        then: "the collection is listed so the summary can say what was already there"
        listings.get() == 1
        logged.events*.formattedMessage.find { it.contains("database [$DATABASE]") }.contains('0 created, 1 already present')

        cleanup:
        logged?.close()
    }
}

@Entity
class ClassifiedThing {
    String name

    static mapping = {
        version false
        collection 'classifiedThing'
        name index: true
    }
}

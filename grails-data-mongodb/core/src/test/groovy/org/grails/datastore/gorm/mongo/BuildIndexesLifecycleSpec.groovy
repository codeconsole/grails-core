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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import ch.qos.logback.classic.Level
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import grails.gorm.annotation.Entity
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import org.apache.grails.testing.mongo.AutoStartedMongoSpec
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.mongo.config.MongoSettings

/**
 * A background build belongs to the datastore that started it. Stopping the datastore for a checkpoint
 * interrupts the build rather than letting it fail against the closed client, and restarting it runs the
 * build again; closing it for good turns a later request for a build into a warning rather than an
 * exception.
 */
class BuildIndexesLifecycleSpec extends AutoStartedMongoSpec {

    @Shared
    MongoClient realClient

    @Override
    boolean shouldInitializeDatastore() {
        false
    }

    void setupSpec() {
        realClient = MongoClients.create(dbContainer.getReplicaSetUrl('lifecycleDb'))
    }

    void cleanupSpec() {
        realClient?.close()
    }

    private Map asyncConfig(String database) {
        ['grails.mongodb.url'                       : dbContainer.getReplicaSetUrl(database),
         (MongoSettings.SETTING_BUILD_INDEXES_ASYNC): true] as Map
    }

    void "test a build cut short by stop() is not reported as a failure and runs again on start()"() {
        given:
        def conditions = new PollingConditions(timeout: 30)
        def log = new CapturedLog('org.grails.datastore.mapping', Level.DEBUG)
        CheckpointedDatastore.REACHED = new CountDownLatch(1)
        CheckpointedDatastore.RELEASE = new CountDownLatch(1)
        CheckpointedDatastore.BLOCK_ONCE.set(true)
        def indexes = { -> realClient.getDatabase('checkpointDb').getCollection('checkpointedThing').listIndexes()*.key }

        when: "the background build is under way"
        def datastore = new CheckpointedDatastore(asyncConfig('checkpointDb'), CheckpointedThing)

        then:
        CheckpointedDatastore.REACHED.await(30, TimeUnit.SECONDS)

        when: "the datastore is stopped for a checkpoint while the build is still running"
        datastore.stop()

        then: "the build is abandoned as a shutdown and reports how far it got, without a failure"
        conditions.eventually {
            assert log.events.any {
                it.level == Level.DEBUG && it.formattedMessage.contains('database [checkpointDb] did not finish')
            }
        }
        !log.events.any {
            it.level.isGreaterOrEqual(Level.WARN) && it.formattedMessage.contains('database [checkpointDb]')
        }

        and: "the declared index was not created"
        !([name: 1] in indexes())

        when: "the datastore is restarted"
        datastore.start()

        then: "the build that was cut short runs again and completes"
        conditions.eventually {
            assert [name: 1] in indexes()
        }

        cleanup:
        CheckpointedDatastore.RELEASE?.countDown()
        datastore?.close()
        log?.close()
    }

    void "test a build requested after close() is refused with a warning rather than an exception"() {
        given:
        def log = new CapturedLog('org.grails.datastore.mapping', Level.INFO)
        def datastore = new MongoDatastore(asyncConfig('closedDb'), ClosedBuildThing)
        datastore.close()

        when:
        datastore.buildIndex()

        then:
        notThrown(Exception)
        log.events.any {
            it.level == Level.WARN && it.formattedMessage.contains('requested after the datastore was closed')
        }

        cleanup:
        log?.close()
    }
}

/**
 * Blocks the first index build in the protected hook, so that a test can act while the build is under
 * way. The latches are static: with an asynchronous build the hook can run before this class's own
 * constructor has finished, which is exactly what the hook's documentation warns an override about.
 */
class CheckpointedDatastore extends MongoDatastore {

    static final AtomicBoolean BLOCK_ONCE = new AtomicBoolean()

    static volatile CountDownLatch REACHED

    static volatile CountDownLatch RELEASE

    CheckpointedDatastore(Map<String, Object> configuration, Class... classes) {
        super(configuration, classes)
    }

    @Override
    protected void initializeIndices(PersistentEntity entity) {
        if (BLOCK_ONCE.compareAndSet(true, false)) {
            REACHED.countDown()
            RELEASE.await()
        }
        super.initializeIndices(entity)
    }
}

@Entity
class CheckpointedThing {
    String name

    static mapping = {
        version false
        collection 'checkpointedThing'
        name index: true
    }
}

@Entity
class ClosedBuildThing {
    String name

    static mapping = {
        version false
        collection 'closedBuildThing'
        name index: true
    }
}

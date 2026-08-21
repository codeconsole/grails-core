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
package org.grails.datastore.gorm.mongo.transactions

import org.grails.datastore.gorm.mongodb.embedded.EmbeddedMongoInitializer
import org.grails.datastore.gorm.mongodb.embedded.FlapdoodleMongoBackend

import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.env.MapPropertySource

import spock.lang.Shared
import spock.lang.Specification

/**
 * A base for the specifications that need real MongoDB transactions, which a standalone server
 * refuses: they are served by an embedded replica set of one.
 *
 * <p>It is a real mongod, as a container is, and it needs no Docker - so a developer without one
 * runs these, and a CI job runs them without a container to lose halfway through a specification.
 */
abstract class EmbeddedReplicaSetSpec extends Specification {

    @Shared
    private GenericApplicationContext embedded

    @Shared
    protected String mongoUrl

    void setupSpec() {
        int port = freePort()
        this.embedded = new GenericApplicationContext()
        this.embedded.environment.propertySources.addFirst(new MapPropertySource('embeddedMongoTest', [
                (EmbeddedMongoInitializer.BACKEND)    : FlapdoodleMongoBackend.NAME,
                (EmbeddedMongoInitializer.REPLICA_SET): 'rs0',
                'grails.mongodb.url'                  : "mongodb://${EmbeddedMongoInitializer.EMBEDDED_HOST}:${port}/myDb".toString(),
        ]))
        new EmbeddedMongoInitializer().initialize(this.embedded)
        this.mongoUrl = this.embedded.environment.getProperty('grails.mongodb.url')
    }

    void cleanupSpec() {
        this.embedded?.close()
    }

    /**
     * Asked for rather than fixed, because the specifications that use this run beside each other in
     * a build: a port named in advance is a port another fork may hold.
     */
    private static int freePort() {
        new ServerSocket(0).withCloseable { ServerSocket socket ->
            socket.reuseAddress = true
            socket.localPort
        }
    }

}

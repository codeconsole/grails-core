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
package org.apache.grails.testing.mongo

import org.grails.datastore.gorm.mongodb.embedded.EmbeddedMongoInitializer
import org.grails.datastore.gorm.mongodb.embedded.EmbeddedMongoLifecycle
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
 *
 * <p>A specification extending this needs {@code grails-data-mongodb-embedded} and the flapdoodle
 * library it runs the server with on its test class path; this module compiles against them and
 * carries neither.
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
                (EmbeddedMongoInitializer.BACKEND): FlapdoodleMongoBackend.NAME,
                (EmbeddedMongoInitializer.REPLICA_SET): 'rs0',
                (EmbeddedMongoInitializer.VERSION): serverVersion(),
                'grails.mongodb.url': "mongodb://${EmbeddedMongoInitializer.EMBEDDED_HOST}:${port}/myDb".toString(),
        ]))
        new EmbeddedMongoInitializer().initialize(this.embedded)
        this.mongoUrl = this.embedded.environment.getProperty('grails.mongodb.url')
    }

    /**
     * The server the rest of the suite is run against, named the way the embedded server names
     * versions. A build asks for one with -PmongodbContainerVersion, and this answers the same
     * request, so a specification served from a container and one served from here are never
     * quietly run against different servers.
     */
    private static String serverVersion() {
        // '7.0.19' and '8.0' alike name the line the embedded server takes: V7_0, V8_0
        List<String> parts = AbstractMongoGrailsExtension.desiredMongoVersion.tokenize('.')
        "V${parts[0]}_${parts.size() > 1 ? parts[1] : '0'}".toString()
    }

    void cleanupSpec() {
        // Stopped through the bean the initializer registers rather than by closing the context:
        // a context that was never refreshed is not active, and closing one that is not active
        // does nothing at all - which left a mongod running for every specification in the fork.
        this.embedded?.beanFactory
                ?.getBean(EmbeddedMongoLifecycle.BEAN_NAME, EmbeddedMongoLifecycle)
                ?.stop()
    }

    /**
     * Asked for rather than fixed, because the specifications that use this run beside each other in
     * a build: a port named in advance is a port another fork may hold.
     */
    private static int freePort() {
        // The port is free when it is read and taken when the server binds it, which is not the
        // same moment; nothing here can close that gap, so a port that has just been handed out is
        // the best this can offer.
        new ServerSocket(0).withCloseable { ServerSocket socket -> socket.localPort }
    }

}

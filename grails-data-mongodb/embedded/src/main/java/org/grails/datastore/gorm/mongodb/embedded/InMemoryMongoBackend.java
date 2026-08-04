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
package org.grails.datastore.gorm.mongodb.embedded;

import java.net.InetSocketAddress;

import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;

import org.springframework.util.ClassUtils;

/**
 * Runs MongoDB inside this JVM using mongo-java-server, which reimplements the MongoDB
 * wire protocol in Java. It starts in milliseconds, downloads nothing and needs no
 * MongoDB installation, which is why it is the default.
 *
 * <p>Being a reimplementation rather than mongod, it does not support transactions,
 * change streams, {@code $text} or some {@code $expr} operators, and it cannot persist
 * anything. An application that needs those should add flapdoodle and let
 * {@link FlapdoodleMongoBackend} run a real mongod instead.
 *
 * @author Grails
 * @since 8.0
 */
public class InMemoryMongoBackend implements EmbeddedMongoBackend {

    public static final String NAME = "in-memory";

    private static final String SERVER_CLASS = "de.bwaldvogel.mongo.MongoServer";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return ClassUtils.isPresent(SERVER_CLASS, getClass().getClassLoader());
    }

    @Override
    public RunningEmbeddedMongo start(EmbeddedMongoSettings settings) {
        if (settings.isPersistent()) {
            throw new IllegalStateException("The " + NAME + " backend keeps everything in memory and cannot honour " +
                    EmbeddedMongoInitializer.DATABASE_DIR + ". Add " +
                    "de.flapdoodle.embed:de.flapdoodle.embed.mongo to run a real mongod that can, or remove the " +
                    "directory to accept a database that is discarded when the server stops.");
        }

        MongoServer server = new MongoServer(new MemoryBackend());
        server.bind("localhost", settings.getPort());
        InetSocketAddress address = server.getLocalAddress();
        return new RunningInMemoryMongo(server, address);
    }

    private static final class RunningInMemoryMongo implements RunningEmbeddedMongo {

        private final MongoServer server;

        private final InetSocketAddress address;

        private RunningInMemoryMongo(MongoServer server, InetSocketAddress address) {
            this.server = server;
            this.address = address;
        }

        @Override
        public String getHost() {
            return "localhost";
        }

        @Override
        public int getPort() {
            return this.address.getPort();
        }

        @Override
        public void stop() {
            this.server.shutdownNow();
        }
    }
}

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
package org.grails.datastore.gorm.mongodb.embedded

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients

import org.bson.Document

import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.env.MapPropertySource

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * Exercises both backends against real servers. Servers started here are reaped by the
 * shutdown hook the initializer registers, when this JVM exits.
 */
class EmbeddedMongoInitializerSpec extends Specification {

    @TempDir
    Path temp

    void 'nothing is started until it is switched on'() {
        given:
        GenericApplicationContext context = contextWith([:])

        when:
        new EmbeddedMongoInitializer().initialize(context)

        then:
        !context.environment.getProperty(EmbeddedMongoInitializer.DEFAULT_PROPERTY_NAME)
        context.environment.propertySources.every { it.name != 'embeddedMongoDB' }
    }

    void 'the in-memory backend serves a real MongoDB connection'() {
        given:
        GenericApplicationContext context = contextWith([
                (EmbeddedMongoInitializer.ENABLED): 'true',
                (EmbeddedMongoInitializer.BACKEND): InMemoryMongoBackend.NAME,
                (EmbeddedMongoInitializer.PORT)   : '27981',
                'grails.mongodb.url'              : 'mongodb://localhost:27017/bookstore',
        ])

        when:
        new EmbeddedMongoInitializer().initialize(context)
        String url = context.environment.getProperty('grails.mongodb.url')

        then: 'the database name came from the application configuration'
        url == 'mongodb://localhost:27981/bookstore'

        and: 'a driver can round-trip a document through it'
        roundTrip(url) == 'in-memory'
    }

    void 'the flapdoodle backend serves a real MongoDB connection'() {
        given:
        GenericApplicationContext context = contextWith([
                (EmbeddedMongoInitializer.ENABLED): 'true',
                (EmbeddedMongoInitializer.BACKEND): FlapdoodleMongoBackend.NAME,
                (EmbeddedMongoInitializer.PORT)   : '27982',
                'grails.mongodb.url'              : 'mongodb://localhost:27017/bookstore',
        ])

        when:
        new EmbeddedMongoInitializer().initialize(context)
        String url = context.environment.getProperty('grails.mongodb.url')

        then:
        url == 'mongodb://localhost:27982/bookstore'
        roundTrip(url) == 'flapdoodle'
    }

    void 'flapdoodle is preferred when both backends are on the classpath'() {
        given: 'no backend is named, and this module has both available in tests'
        GenericApplicationContext context = contextWith([
                (EmbeddedMongoInitializer.ENABLED): 'true',
                (EmbeddedMongoInitializer.PORT)   : '27983',
        ])

        when: 'adding flapdoodle is the opt-in for a real mongod'
        new EmbeddedMongoInitializer().initialize(context)

        then: 'a real mongod answers, which the in-memory backend could not do for a transaction'
        context.environment.getProperty('grails.mongodb.url') == 'mongodb://localhost:27983/test'
    }

    void 'the url is published into every configured property'() {
        given:
        GenericApplicationContext context = contextWith([
                (EmbeddedMongoInitializer.ENABLED)       : 'true',
                (EmbeddedMongoInitializer.BACKEND)       : InMemoryMongoBackend.NAME,
                (EmbeddedMongoInitializer.PORT)          : '27984',
                (EmbeddedMongoInitializer.PROPERTY_NAMES): 'grails.mongodb.url, spring.data.mongodb.uri',
                (EmbeddedMongoInitializer.DATABASE)      : 'bookstore',
        ])

        when:
        new EmbeddedMongoInitializer().initialize(context)

        then:
        String expected = 'mongodb://localhost:27984/bookstore'
        context.environment.getProperty('grails.mongodb.url') == expected
        context.environment.getProperty('spring.data.mongodb.uri') == expected
    }

    void 'a second context reuses the server the first one started'() {
        given:
        GenericApplicationContext first = contextWith([
                (EmbeddedMongoInitializer.ENABLED) : 'true',
                (EmbeddedMongoInitializer.BACKEND) : InMemoryMongoBackend.NAME,
                (EmbeddedMongoInitializer.PORT)    : '27985',
                (EmbeddedMongoInitializer.DATABASE): 'bookstore',
        ])
        new EmbeddedMongoInitializer().initialize(first)

        and:
        GenericApplicationContext restarted = contextWith([
                (EmbeddedMongoInitializer.ENABLED) : 'true',
                (EmbeddedMongoInitializer.BACKEND) : InMemoryMongoBackend.NAME,
                (EmbeddedMongoInitializer.PORT)    : '27985',
                (EmbeddedMongoInitializer.DATABASE): 'bookstore',
        ])

        when: 'the port is already owned, as it is after a devtools restart'
        new EmbeddedMongoInitializer().initialize(restarted)

        then:
        restarted.environment.getProperty('grails.mongodb.url') == 'mongodb://localhost:27985/bookstore'
    }

    void 'the in-memory backend refuses to pretend it can persist'() {
        given:
        GenericApplicationContext context = contextWith([
                (EmbeddedMongoInitializer.ENABLED)     : 'true',
                (EmbeddedMongoInitializer.BACKEND)     : InMemoryMongoBackend.NAME,
                (EmbeddedMongoInitializer.PORT)        : '27986',
                (EmbeddedMongoInitializer.DATABASE_DIR): temp.resolve('data').toString(),
        ])

        when:
        new EmbeddedMongoInitializer().initialize(context)

        then: 'it names the backend that can, rather than silently discarding the data'
        IllegalStateException e = thrown()
        e.message.contains('cannot honour')
        e.message.contains('flapdoodle')
    }

    void 'a port held by something other than an embedded MongoDB is never reused'() {
        given: 'an unrelated service holding the port, on the address a backend binds'
        ServerSocket intruder = new ServerSocket(27990, 1, InetAddress.getByName('localhost'))
        GenericApplicationContext context = contextWith([
                (EmbeddedMongoInitializer.ENABLED): 'true',
                (EmbeddedMongoInitializer.BACKEND): InMemoryMongoBackend.NAME,
                (EmbeddedMongoInitializer.PORT)   : '27990',
        ])

        when:
        new EmbeddedMongoInitializer().initialize(context)

        then: 'it fails rather than publishing a MongoDB url pointing at that service'
        IllegalStateException e = thrown()
        e.message.contains('something else may already be using')

        and: 'no url was published'
        !context.environment.getProperty('grails.mongodb.url')

        cleanup:
        intruder.close()
    }

    void 'an unknown backend is reported by name'() {
        given:
        GenericApplicationContext context = contextWith([
                (EmbeddedMongoInitializer.ENABLED): 'true',
                (EmbeddedMongoInitializer.BACKEND): 'sqlite',
                (EmbeddedMongoInitializer.PORT)   : '27989',
        ])

        when:
        new EmbeddedMongoInitializer().initialize(context)

        then:
        IllegalStateException e = thrown()
        e.message.contains('is not a known backend')
    }

    private static String roundTrip(String url) {
        try (MongoClient client = MongoClients.create(url)) {
            def collection = client.getDatabase('bookstore').getCollection('probe')
            collection.insertOne(new Document('backend', url.contains('27981') ? 'in-memory' : 'flapdoodle'))
            collection.find().first().getString('backend')
        }
    }

    private static GenericApplicationContext contextWith(Map<String, Object> properties) {
        GenericApplicationContext context = new GenericApplicationContext()
        context.environment.propertySources.addFirst(new MapPropertySource('test', properties))
        context
    }
}

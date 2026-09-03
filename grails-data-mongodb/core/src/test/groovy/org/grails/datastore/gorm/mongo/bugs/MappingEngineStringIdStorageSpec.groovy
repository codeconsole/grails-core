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
package org.grails.datastore.gorm.mongo.bugs

import grails.persistence.Entity
import org.apache.grails.data.mongo.core.GrailsDataMongoTckManager
import org.apache.grails.data.testing.tck.base.GrailsDataTckSpec
import org.bson.Document
import org.bson.types.ObjectId
import org.grails.datastore.mapping.core.Session
import org.grails.datastore.mapping.mongo.MongoDatastore
import org.grails.datastore.mapping.mongo.config.MongoSettings
import spock.lang.AutoCleanup
import spock.lang.Shared

/**
 * The non-codec ("mapping") engine has to store {@code _id} in the same type the shared
 * {@link org.grails.datastore.mapping.mongo.query.MongoQuery} looks it up in.
 *
 * <p>{@code MongoEntityPersister.generateIdentifier} decided the stored type from the
 * *declared* identifier type alone, so with {@code String id} it always wrote a BSON String.
 * Once a bare {@code String id} resolves to {@code storedAs: ObjectId}, a document saved by
 * this engine could not be found again by id, because the query sent an ObjectId.
 */
class MappingEngineStringIdStorageSpec extends GrailsDataTckSpec<GrailsDataMongoTckManager> {

    @Shared
    @AutoCleanup
    MongoDatastore mappingEngineDatastore

    void setupSpec() {
        manager.registerDomainClasses(MappingEngineVideo)
        mappingEngineDatastore = new MongoDatastore(
                manager.configuration + [(MongoSettings.SETTING_ENGINE): 'mapping'],
                MappingEngineVideo)
    }

    void "the datastore under test really is the mapping engine"() {
        expect: 'guards the cases below -- a codec session would prove nothing about this path'
        mappingEngineDatastore.connect().getClass().simpleName == 'MongoSession'
    }

    void "the mapping engine writes _id in the configured storage type"() {
        given: 'driven through the session API -- GORM statics bind to another datastore'
        String hex = null

        when:
        mappingEngineDatastore.withSession { Session session ->
            MappingEngineVideo v = new MappingEngineVideo(title: 'Mapped')
            session.persist(v)
            session.flush()
            hex = v.id
        }

        then: 'the domain still sees a hex String'
        hex ==~ /[0-9a-f]{24}/

        and: '_id on disk is the ObjectId the shared query layer will look for'
        rawCollection().find(new Document('_id', new ObjectId(hex))).first()?.getString('title') == 'Mapped'
        rawCollection().find(new Document('_id', hex)).first() == null
    }

    void "a document saved by the mapping engine can be read back by id"() {
        given:
        String hex = null
        String title = null

        when:
        mappingEngineDatastore.withSession { Session session ->
            MappingEngineVideo v = new MappingEngineVideo(title: 'Round trip')
            session.persist(v)
            session.flush()
            hex = v.id
            session.clear()
            title = session.retrieve(MappingEngineVideo, hex)?.title
        }

        then: 'save and query agree on the storage type'
        title == 'Round trip'
    }

    private com.mongodb.client.MongoCollection<Document> rawCollection() {
        manager.mongoClient
                .getDatabase('test')
                .getCollection('mappingEngineVideo')
    }
}

@Entity
class MappingEngineVideo {
    String id
    String title
}

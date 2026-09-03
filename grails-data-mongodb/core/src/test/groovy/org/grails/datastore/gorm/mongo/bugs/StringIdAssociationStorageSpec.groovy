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

import com.mongodb.client.MongoCollection
import grails.persistence.Entity
import org.apache.grails.data.mongo.core.GrailsDataMongoTckManager
import org.apache.grails.data.testing.tck.base.GrailsDataTckSpec
import org.bson.Document
import org.bson.types.ObjectId

/**
 * Association identifiers must be written in the type the target's {@code _id} is stored as.
 *
 * <p>Before this was fixed the encoders wrote a reference using the id's *declared* type, so a
 * {@code String id} domain whose {@code _id} is a BSON ObjectId was pointed at by a BSON String.
 * GORM traversal still worked -- the decoder coerces on read -- so the mismatch was invisible
 * from application code while {@code $lookup}, the raw driver and any external client silently
 * matched nothing.
 *
 * <p>These cases assert the BSON that actually lands on disk, not just that traversal works,
 * because traversal passed even when the storage was wrong.
 */
class StringIdAssociationStorageSpec extends GrailsDataTckSpec<GrailsDataMongoTckManager> {

    void setupSpec() {
        manager.registerDomainClasses(RefProject, RefTicket, RefTag)
    }

    void "a to-one reference is written as the target's stored _id type"() {
        given:
        RefProject project = new RefProject(name: 'Apollo').save(flush: true)
        RefTicket ticket = new RefTicket(title: 'Boot failure', project: project).save(flush: true)

        when:
        manager.session.clear()
        Document raw = rawTickets().find(new Document('_id', new ObjectId(ticket.id))).first()

        then: 'the reference is an ObjectId, matching RefProject._id'
        raw.get('project') instanceof ObjectId
        raw.get('project') == new ObjectId(project.id)

        and: 'so a raw driver join resolves, which is what was broken'
        rawProjects().find(new Document('_id', raw.get('project'))).first().getString('name') == 'Apollo'
    }

    void "a to-one reference still resolves through GORM"() {
        given:
        RefProject project = new RefProject(name: 'Gemini').save(flush: true)
        String ticketId = new RefTicket(title: 'Telemetry', project: project).save(flush: true).id

        when:
        manager.session.clear()
        RefTicket loaded = RefTicket.get(ticketId)

        then: 'the decoder hands back the declared String type'
        loaded.project.id == project.id
        loaded.project.id instanceof String
        loaded.project.name == 'Gemini'
    }

    void "a to-many reference array is written as the target's stored _id type"() {
        given:
        RefTag a = new RefTag(label: 'urgent').save(flush: true)
        RefTag b = new RefTag(label: 'backend').save(flush: true)
        RefProject project = new RefProject(name: 'Mercury')
        project.tags = [a, b]
        project.save(flush: true)

        when:
        manager.session.clear()
        Document raw = rawProjects().find(new Document('_id', new ObjectId(project.id))).first()

        then:
        raw.get('tags').every { it instanceof ObjectId }
        raw.get('tags') as Set == [new ObjectId(a.id), new ObjectId(b.id)] as Set
    }

    void "a to-many collection still resolves through GORM"() {
        given:
        RefTag a = new RefTag(label: 'ui').save(flush: true)
        RefProject project = new RefProject(name: 'Vostok')
        project.tags = [a]
        project.save(flush: true)

        when:
        manager.session.clear()
        RefProject loaded = RefProject.get(project.id)

        then:
        loaded.tags.size() == 1
        loaded.tags.first().label == 'ui'
    }

    void "a bidirectional one-to-many resolves, because the foreign-key filter is coerced too"() {
        given: 'the inverse side is fetched by querying tickets whose project is this id'
        RefProject project = new RefProject(name: 'Soyuz').save(flush: true)
        new RefTicket(title: 'One', project: project).save(flush: true)
        new RefTicket(title: 'Two', project: project).save(flush: true)

        when:
        manager.session.clear()
        List<RefTicket> found = RefTicket.findAllByProject(RefProject.get(project.id))

        then: 'without coercion this sent a hex String against an ObjectId FK and returned nothing'
        found.size() == 2
        found*.title as Set == ['One', 'Two'] as Set
    }

    void "findAllById resolves, having previously bypassed id coercion entirely"() {
        given: "a dynamic finder builds Equals('id', ..) rather than IdEquals"
        String id = new RefProject(name: 'Voskhod').save(flush: true).id

        when:
        manager.session.clear()
        List<RefProject> found = RefProject.findAllById(id)

        then:
        found.size() == 1
        found[0].name == 'Voskhod'
    }

    void "findAllByIdInList resolves the same ids"() {
        given:
        String one = new RefProject(name: 'Zond').save(flush: true).id
        String two = new RefProject(name: 'Luna').save(flush: true).id

        when:
        manager.session.clear()
        List<RefProject> found = RefProject.findAllByIdInList([one, two])

        then:
        found.size() == 2
    }

    void "a reference stored as a BSON String is still decoded, so pre-existing data keeps working"() {
        given: 'a document written before the storage type changed'
        RefProject project = new RefProject(name: 'Apollo 13').save(flush: true)
        ObjectId legacyTicketId = new ObjectId()
        rawTickets().insertOne(
                new Document('_id', legacyTicketId)
                        .append('title', 'Legacy row')
                        .append('project', project.id))   // hex String, the old format

        when:
        manager.session.clear()
        RefTicket loaded = RefTicket.get(legacyTicketId.toHexString())

        then: 'the decoder reads whatever BSON type is present rather than the predicted one'
        loaded.title == 'Legacy row'
        loaded.project.id == project.id
        loaded.project.name == 'Apollo 13'
    }

    void "updateAll writes a to-one reference in the target's stored _id type"() {
        given: 'the bulk update path builds its own $set document rather than going through the codec'
        RefProject from = new RefProject(name: 'Old owner').save(flush: true)
        RefProject to = new RefProject(name: 'New owner').save(flush: true)
        RefTicket ticket = new RefTicket(title: 'Reassign me', project: from).save(flush: true)

        when:
        manager.session.clear()
        RefTicket.where { title == 'Reassign me' }.updateAll(project: RefProject.get(to.id))
        Document raw = rawTickets().find(new Document('_id', new ObjectId(ticket.id))).first()

        then: 'the same representation normal persistence writes, not the declared String'
        raw.get('project') instanceof ObjectId
        raw.get('project') == new ObjectId(to.id)

        and: 'so the relationship is still queryable afterwards'
        manager.session.clear()
        RefTicket.get(ticket.id).project.name == 'New owner'
    }

    private MongoCollection<Document> rawTickets() {
        manager.mongoClient.getDatabase('test').getCollection('refTicket')
    }

    private MongoCollection<Document> rawProjects() {
        manager.mongoClient.getDatabase('test').getCollection('refProject')
    }
}

@Entity
class RefProject {
    String id
    String name
    Set<RefTag> tags = []
    static hasMany = [tags: RefTag]
    static mapping = {
        version false
    }
}

@Entity
class RefTicket {
    String id
    String title
    RefProject project
    static mapping = {
        version false
    }
}

@Entity
class RefTag {
    String id
    String label
    static mapping = {
        version false
    }
}

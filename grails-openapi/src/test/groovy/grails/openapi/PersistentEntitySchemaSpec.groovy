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
package grails.openapi

import io.swagger.v3.oas.models.OpenAPI

import grails.core.DefaultGrailsApplication
import grails.core.GrailsApplication
import grails.gorm.annotation.Entity
import org.grails.datastore.gorm.validation.constraints.registry.DefaultValidatorRegistry
import org.grails.datastore.mapping.core.connections.ConnectionSourceSettings
import org.grails.datastore.mapping.keyvalue.mapping.config.KeyValueMappingContext
import org.grails.datastore.mapping.model.MappingContext
import org.grails.support.MockApplicationContext
import org.grails.web.mapping.DefaultUrlMappingEvaluator
import org.grails.web.mapping.DefaultUrlMappingsHolder
import org.grails.web.util.WebUtils

import spock.lang.Specification

class PersistentEntitySchemaSpec extends Specification {

    void setup() {
        WebUtils.clearGrailsWebRequest()
    }

    void 'registers a schema for the documented resource and its request body'() {
        given:
        def openApi = new OpenAPI()

        when:
        customizer().customise(openApi)

        then:
        openApi.components.schemas.containsKey('Widget')
    }

    void 'defines a domain class reached only through an association'() {
        given:
        def openApi = new OpenAPI()

        when: 'only widgets are mapped, and Crate is reached from Widget'
        customizer().customise(openApi)

        then:
        openApi.components.schemas.containsKey('Crate')
    }

    void 'omits an unreferenced domain class entirely'() {
        given:
        def openApi = new OpenAPI()
        MappingContext context = new KeyValueMappingContext('test')
        context.addPersistentEntity(Widget)
        context.addPersistentEntity(Crate)
        context.addPersistentEntity(Orphan)
        context.setValidatorRegistry(new DefaultValidatorRegistry(context, new ConnectionSourceSettings()))

        when: 'Orphan has no mapping and nothing references it'
        new UrlMappingsOpenApiCustomizer(holder()).tap { mappingContext = context }.customise(openApi)

        then:
        !openApi.components.schemas.containsKey('Orphan')
    }

    void 'maps domain property types onto OpenAPI schema types'() {
        given:
        def openApi = new OpenAPI()

        when:
        customizer().customise(openApi)

        then:
        with(openApi.components.schemas['Widget'].properties) {
            id.type == 'integer'
            id.format == 'int64'
            name.type == 'string'
            weight.type == 'number'
            active.type == 'boolean'
        }
    }

    void 'references the associated schema rather than inlining it'() {
        given:
        def openApi = new OpenAPI()

        when:
        customizer().customise(openApi)

        then: 'a to-one association is a direct reference'
        openApi.components.schemas['Widget'].properties.crate.$ref == '#/components/schemas/Crate'

        and: 'a to-many association is an array of references'
        with(openApi.components.schemas['Crate'].properties.widgets) {
            type == 'array'
            items.$ref == '#/components/schemas/Widget'
        }
    }

    void 'derives required members from the constraints block'() {
        given:
        def openApi = new OpenAPI()

        when:
        customizer().customise(openApi)

        then: 'a property the constraints declare non-nullable is required'
        'name' in openApi.components.schemas['Widget'].required

        and: 'a nullable property is not'
        !('weight' in (openApi.components.schemas['Widget'].required ?: []))

        and: 'the generated version column is never required'
        !('version' in (openApi.components.schemas['Widget'].required ?: []))
    }

    void 'carries validation constraints across to the schema'() {
        given:
        def openApi = new OpenAPI()

        when:
        customizer().customise(openApi)

        then:
        with(openApi.components.schemas['Widget'].properties) {
            name.maxLength == 40
            colour.enum == ['red', 'green']
            code.pattern == '[A-Z]{3}'
            contact.format == 'email'
        }
    }

    void 'marks the server assigned properties readOnly rather than defining a second schema'() {
        given:
        def openApi = new OpenAPI()

        when:
        customizer().customise(openApi)

        then: 'the identifier and version are present but not for a client to send'
        with(openApi.components.schemas['Widget'].properties) {
            id.readOnly
            version.readOnly

            and: 'an editable property is not marked'
            !name.readOnly
        }

        and: 'no second schema is defined for request bodies'
        openApi.components.schemas.keySet().every { !it.endsWith('Request') }
    }

    void 'documents a 404 on operations addressed by identifier'() {
        given:
        def openApi = new OpenAPI()

        when:
        customizer().customise(openApi)

        then: 'an instance operation can miss'
        openApi.paths['/widgets/{id}'].get.responses['404']

        and: 'a collection operation cannot'
        openApi.paths['/widgets'].get.responses['404'] == null
    }

    void 'responds with a collection schema for index and a single resource otherwise'() {
        given:
        def openApi = new OpenAPI()

        when:
        customizer().customise(openApi)

        then: 'index returns an array of the resource'
        with(openApi.paths['/widgets'].get.responses['200'].content['application/json'].schema) {
            type == 'array'
            items.$ref == '#/components/schemas/Widget'
        }

        and: 'show returns a single resource'
        openApi.paths['/widgets/{id}'].get.responses['200']
                .content['application/json'].schema.$ref == '#/components/schemas/Widget'
    }

    void 'documents a request body for methods that accept one'() {
        given:
        def openApi = new OpenAPI()

        when:
        customizer().customise(openApi)

        then:
        openApi.paths['/widgets'].post.requestBody
                .content['application/json'].schema.$ref == '#/components/schemas/Widget'

        and: 'a read-only method carries no request body'
        openApi.paths['/widgets'].get.requestBody == null
    }

    void 'omits schemas when the application has no mapping context'() {
        given:
        def openApi = new OpenAPI()
        def customizer = new UrlMappingsOpenApiCustomizer(holder())

        when:
        customizer.customise(openApi)

        then: 'paths are still documented'
        openApi.paths['/widgets'].get

        and: 'but nothing claims a schema'
        openApi.components?.schemas == null
        openApi.paths['/widgets'].get.responses['200'].content['application/json'].schema == null
    }

    private static UrlMappingsOpenApiCustomizer customizer() {
        MappingContext context = new KeyValueMappingContext('test')
        context.addPersistentEntity(Widget)
        context.addPersistentEntity(Crate)
        // Constraints are only available once a validator registry has evaluated them.
        context.setValidatorRegistry(new DefaultValidatorRegistry(context, new ConnectionSourceSettings()))
        new UrlMappingsOpenApiCustomizer(holder()).tap { mappingContext = context }
    }

    private static DefaultUrlMappingsHolder holder() {
        def ctx = new MockApplicationContext()
        ctx.registerMockBean(GrailsApplication.APPLICATION_ID, new DefaultGrailsApplication())
        def evaluator = new DefaultUrlMappingEvaluator(ctx)
        new DefaultUrlMappingsHolder(evaluator.evaluateMappings {
            '/widgets'(resources: 'widget')
        })
    }
}

@Entity
class Widget {
    String name
    Double weight
    Boolean active
    String colour
    String code
    String contact
    Crate crate

    static constraints = {
        name blank: false, nullable: false, maxSize: 40
        weight nullable: true
        active nullable: true
        colour nullable: true, inList: ['red', 'green']
        code nullable: true, matches: '[A-Z]{3}'
        contact nullable: true, email: true
        crate nullable: true
    }
}

@Entity
class Crate {
    String label
    static hasMany = [widgets: Widget]
}

@Entity
class Orphan {
    String note
}

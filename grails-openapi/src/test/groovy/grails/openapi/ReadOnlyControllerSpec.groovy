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

import grails.artefact.Artefact
import grails.gorm.annotation.Entity
import grails.rest.RestfulController

import spock.lang.Specification

class ReadOnlyControllerSpec extends Specification {

    void 'a read-only controller is described without the write actions it refuses'() {
        when:
        def openApi = document([new ArchiveController()]) {
            '/archives'(resources: 'archive')
        }

        then: 'only what it serves is described'
        openApi.paths.keySet() == ['/archives', '/archives/{id}'] as Set
        openApi.paths['/archives'].readOperationsMap().keySet()*.name() == ['GET']
        openApi.paths['/archives/{id}'].readOperationsMap().keySet()*.name() == ['GET']
    }

    void 'the routes of the default mapping leave out the write actions a read-only controller refuses'() {
        when:
        def openApi = document([new ArchiveController()]) {
            "/$controller/$action?/$id?(.$format)?" {}
        }

        then:
        openApi.paths.keySet() == ['/archive/index', '/archive/show/{id}'] as Set
    }

    void 'the form actions are left out even where they are asked for'() {
        when:
        def openApi = document([new ArchiveController()], ['grails.openapi.include-form-actions': true]) {
            "/$controller/$action?/$id?(.$format)?" {}
        }

        then:
        !openApi.paths.keySet().any { it.startsWith('/archive/create') || it.startsWith('/archive/edit') }
    }

    void 'an action a read-only controller provides itself is described'() {
        when:
        def openApi = document([new LedgerController()]) {
            '/ledgers'(resources: 'ledger')
        }

        then: 'save is its own, and patch reaches the update it provides'
        openApi.paths['/ledgers'].post
        openApi.paths['/ledgers/{id}'].put
        openApi.paths['/ledgers/{id}'].patch

        and: 'delete is still refused'
        openApi.paths['/ledgers/{id}'].delete == null
    }

    void 'reads a controller that another controller extends'() {
        when: 'both are in the context, so looking the controller up by its type finds two'
        def openApi = document([new ArchiveController(), new AuditedArchiveController()]) {
            '/archives'(resources: 'archive')
            '/audited'(resources: 'auditedArchive')
        }

        then:
        openApi.paths['/archives'].readOperationsMap().keySet()*.name() == ['GET']
        openApi.paths['/audited'].readOperationsMap().keySet()*.name() == ['GET']
    }

    void 'a controller that is not read only is described in full'() {
        when:
        def openApi = document([new ArchiveController(false)]) {
            '/archives'(resources: 'archive')
        }

        then: 'update answers POST as well as PUT'
        openApi.paths['/archives'].post
        openApi.paths['/archives/{id}'].readOperationsMap().keySet()*.name() as Set ==
                ['GET', 'PUT', 'POST', 'PATCH', 'DELETE'] as Set
    }

    void 'a controller the application context does not hold is described in full'() {
        when: 'nothing can say it was constructed read only'
        def openApi = OpenApiFixture.document([ArchiveController], [Archive]) {
            '/archives'(resources: 'archive')
        }

        then:
        openApi.paths['/archives'].post
        openApi.paths['/archives/{id}'].delete
    }

    private static OpenAPI document(List<Object> controllers, Map<String, Object> config = [:], Closure mappings) {
        List<Class<?>> types = controllers*.getClass()
        OpenApiFixture.generator(OpenApiFixture.holder(mappings), OpenApiFixture.application(types, controllers),
                OpenApiFixture.context([Archive]), config).generate()
    }
}

@Entity
class Archive {
    String title
}

@Artefact('Controller')
class ArchiveController extends RestfulController<Archive> {
    ArchiveController() { this(true) }
    ArchiveController(boolean readOnly) { super(Archive, readOnly) }
}

@Artefact('Controller')
class AuditedArchiveController extends ArchiveController {
}

@Artefact('Controller')
class LedgerController extends RestfulController<Archive> {

    LedgerController() { super(Archive, true) }

    @Override
    Object save() {
        render(status: 201)
    }

    @Override
    Object update() {
        render(status: 200)
    }
}

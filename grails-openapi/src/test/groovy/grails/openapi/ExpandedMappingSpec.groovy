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
import io.swagger.v3.oas.models.PathItem

import grails.artefact.Artefact
import grails.gorm.annotation.Entity
import grails.openapi.expanded.v1.ParcelController as V1ParcelController
import grails.openapi.expanded.v2.ParcelController as V2ParcelController
import grails.rest.RestfulController

import spock.lang.Specification

class ExpandedMappingSpec extends Specification {

    void 'describes each controller at the namespace a mapping captures'() {
        when:
        def openApi = OpenApiFixture.document([V1ParcelController, V2ParcelController, DrawerController], [Parcel, Drawer]) {
            "/$namespace/$controller/$action?/$id?(.$format)?" {}
        }

        then:
        openApi.paths['/v1/parcel/index'].get
        openApi.paths['/v2/parcel/show/{id}'].get

        and: 'a controller without a namespace is not reached where the mapping requires one'
        !openApi.paths.keySet().any { it.contains('drawer') }
        !openApi.paths.keySet().any { it.contains('{namespace}') }
    }

    void 'a mapping without the namespace reaches the controller Grails resolves for the name'() {
        when:
        def openApi = OpenApiFixture.document([V1ParcelController, V2ParcelController, DrawerController], [Parcel, Drawer]) {
            "/$controller/$action?/$id?(.$format)?" {}
        }

        then:
        openApi.paths['/drawer/index'].get

        and: 'two controllers of one name are told apart only by a namespace, which this mapping does not give'
        !openApi.paths.containsKey('/parcel/index')
    }

    private static Set<String> operations(OpenAPI openApi) {
        (openApi.paths ?: [:]).collectMany { String path, PathItem item ->
            item.readOperationsMap().keySet().collect { "${it} ${path}".toString() }
        } as Set<String>
    }
}

@Entity
class Parcel {
    String label
}

@Entity
class Drawer {
    String label
}

@Artefact('Controller')
class DrawerController extends RestfulController<Drawer> {
    DrawerController() { super(Drawer) }
}

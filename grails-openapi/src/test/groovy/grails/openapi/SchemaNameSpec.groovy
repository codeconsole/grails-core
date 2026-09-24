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
import grails.openapi.names.ValidationErrors as NamedValidationErrors
import grails.openapi.names.v1.Label as V1Label
import grails.openapi.names.v1.LabelPatch as V1LabelPatch
import grails.openapi.names.v2.Label as V2Label
import grails.rest.RestfulController

import spock.lang.Specification

class SchemaNameSpec extends Specification {

    private static final String V1 = 'grails.openapi.names.v1.Label'
    private static final String V2 = 'grails.openapi.names.v2.Label'

    void 'names each class sharing a simple name by its package'() {
        when:
        def openApi = OpenApiFixture.document([PrintedLabelController, ShippingLabelController], []) {
            '/printed'(resources: 'printedLabel')
            '/shipping'(resources: 'shippingLabel')
        }

        then: 'neither takes the name they share, whichever is described first'
        !openApi.components.schemas.containsKey('Label')

        and: 'each is described in full under its own name'
        openApi.components.schemas[V1].properties.keySet() == ['text'] as Set
        openApi.components.schemas[V2].properties.keySet() == ['code', 'width'] as Set

        and: 'each operation refers to the one it serves'
        responseReference(openApi, '/printed/{id}') == "#/components/schemas/${V1}"
        responseReference(openApi, '/shipping/{id}') == "#/components/schemas/${V2}"
        openApi.paths['/printed'].post.requestBody.content['application/json'].schema.$ref == "#/components/schemas/${V1}"
    }

    void 'names apart the classes sharing a name within one resource'() {
        when:
        def openApi = OpenApiFixture.document([LabelSheetController], []) {
            '/sheets'(resources: 'labelSheet')
        }
        def properties = openApi.components.schemas['LabelSheet'].properties

        then:
        properties.front.$ref == "#/components/schemas/${V1}"
        properties.back.$ref == "#/components/schemas/${V2}"
        openApi.components.schemas[V1].properties.keySet() == ['text'] as Set
        openApi.components.schemas[V2].properties.keySet() == ['code', 'width'] as Set
        !openApi.components.schemas.containsKey('Label')
    }

    void 'the names do not depend on which class is described first'() {
        when:
        def openApi = OpenApiFixture.document([controller], []) {
            '/sheets'(resources: resourceName)
        }
        def properties = openApi.components.schemas[sheet.simpleName].properties

        then:
        properties.values()*.$ref as Set == ["#/components/schemas/${V1}", "#/components/schemas/${V2}"]*.toString() as Set
        openApi.components.schemas.keySet() - GrailsOpenApiGenerator.VALIDATION_ERRORS_SCHEMA ==
                [sheet.simpleName, V1, V2] as Set

        where:
        controller                   | sheet
        LabelSheetController         | LabelSheet
        ReversedLabelSheetController | ReversedLabelSheet

        resourceName = controller.simpleName.replace('Controller', '').uncapitalize()
    }

    void 'keeps the simple name of a class no other shares'() {
        when:
        def openApi = OpenApiFixture.document([PrintedLabelController], []) {
            '/printed'(resources: 'printedLabel')
        }

        then:
        openApi.components.schemas.containsKey('Label')
        responseReference(openApi, '/printed/{id}') == '#/components/schemas/Label'
    }

    void 'a patch schema follows the schema it is a patch of'() {
        when:
        def openApi = OpenApiFixture.document([PrintedLabelController, ShippingLabelController], []) {
            '/printed'(resources: 'printedLabel')
            '/shipping'(resources: 'shippingLabel')
        }

        then:
        openApi.paths['/shipping/{id}'].patch.requestBody.content['application/json'].schema.$ref ==
                "#/components/schemas/${V2}Patch"
        openApi.components.schemas["${V2}Patch".toString()].properties.keySet() == ['code', 'width'] as Set
        !openApi.components.schemas.containsKey('LabelPatch')
    }

    void 'a class sharing the name of a patch schema is named by its package'() {
        when:
        def openApi = OpenApiFixture.document([ShippingLabelController, LabelAdjustmentController], []) {
            post '/adjustments'(controller: 'labelAdjustment', action: 'adjust')
            '/shipping'(resources: 'shippingLabel')
        }

        then: 'the patch schema keeps the name it derives'
        openApi.paths['/shipping/{id}'].patch.requestBody.content['application/json'].schema.$ref ==
                '#/components/schemas/LabelPatch'
        openApi.components.schemas['LabelPatch'].properties.keySet() == ['code', 'width'] as Set

        and: 'the class is described apart from it'
        openApi.paths['/adjustments'].post.requestBody.content['application/json'].schema.$ref ==
                '#/components/schemas/grails.openapi.names.v1.LabelPatch'
        openApi.components.schemas['grails.openapi.names.v1.LabelPatch'].properties.keySet() == ['reason'] as Set
    }

    void 'a class sharing the name of the validation errors is named by its package'() {
        when:
        def openApi = OpenApiFixture.document([ValidationReportController], []) {
            '/reports'(resources: 'validationReport')
        }

        then: 'the validation errors keep their name'
        openApi.paths['/reports'].post.responses['422'].content['application/json'].schema.$ref ==
                "#/components/schemas/${GrailsOpenApiGenerator.VALIDATION_ERRORS_SCHEMA}"
        openApi.components.schemas[GrailsOpenApiGenerator.VALIDATION_ERRORS_SCHEMA].properties.keySet() == ['errors'] as Set

        and: 'the class is described apart from them'
        openApi.paths['/reports'].post.responses['201'].content['application/json'].schema.$ref ==
                '#/components/schemas/grails.openapi.names.ValidationErrors'
        openApi.components.schemas['grails.openapi.names.ValidationErrors'].properties.keySet() == ['summary'] as Set
    }

    void 'every reference resolves once the names are moved'() {
        when:
        def openApi = OpenApiFixture.document([PrintedLabelController, ShippingLabelController, LabelSheetController], []) {
            '/printed'(resources: 'printedLabel')
            '/shipping'(resources: 'shippingLabel')
            '/sheets'(resources: 'labelSheet')
        }
        Set<String> defined = openApi.components.schemas.keySet()
        Set<String> referenced = references(openApi)

        then:
        !referenced.isEmpty()
        defined.containsAll(referenced)
    }

    private static String responseReference(OpenAPI openApi, String path) {
        openApi.paths[path].get.responses['200'].content['application/json'].schema.$ref
    }

    private static Set<String> references(OpenAPI openApi) {
        String json = GrailsOpenApiGenerator.serialize(openApi, 'json')
        (json =~ /#\/components\/schemas\/([^"]+)"/).collect { ((List<String>) it)[1] } as Set<String>
    }
}

class LabelSheet {
    V1Label front
    V2Label back
}

class ReversedLabelSheet {
    V2Label front
    V1Label back
}

@Artefact('Controller')
class ReversedLabelSheetController extends RestfulController<ReversedLabelSheet> {
    ReversedLabelSheetController() { super(ReversedLabelSheet) }
}

@Artefact('Controller')
class PrintedLabelController extends RestfulController<V1Label> {
    PrintedLabelController() { super(V1Label) }
}

@Artefact('Controller')
class ShippingLabelController extends RestfulController<V2Label> {
    ShippingLabelController() { super(V2Label) }
}

@Artefact('Controller')
class LabelSheetController extends RestfulController<LabelSheet> {
    LabelSheetController() { super(LabelSheet) }
}

@Artefact('Controller')
class LabelAdjustmentController {
    def adjust(V1LabelPatch adjustment) { }
}

@Artefact('Controller')
class ValidationReportController extends RestfulController<NamedValidationErrors> {
    ValidationReportController() { super(NamedValidationErrors) }
}

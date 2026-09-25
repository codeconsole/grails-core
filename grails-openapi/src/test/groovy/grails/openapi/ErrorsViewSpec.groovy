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
import io.swagger.v3.oas.models.media.Content

import grails.artefact.Artefact
import grails.gorm.annotation.Entity
import grails.plugin.json.view.JsonViewConfiguration
import grails.plugin.json.view.mvc.JsonViewResolver
import grails.rest.RestfulController

import spock.lang.Specification
import spock.lang.TempDir

class ErrorsViewSpec extends Specification {

    private static final String ERRORS = '#/components/schemas/ValidationErrors'

    @TempDir
    File views

    void 'describes the errors the converters render, in every data format, without JSON views'() {
        when:
        def openApi = document()

        then:
        errors(openApi, '/kettles')['application/json'].schema.$ref == ERRORS
        errors(openApi, '/kettles')['text/xml'].schema.$ref == ERRORS
        openApi.components.schemas['ValidationErrors'].properties.keySet() == ['errors'] as Set
    }

    void 'describes the errors the errors view renders in JSON, and those the converters render in XML without a shape'() {
        given: 'an application generated with JSON views'
        view('object/_object.gson', OBJECT_VIEW)
        view('errors/_errors.gson', ERRORS_VIEW)

        when:
        def openApi = document()

        then: 'the errors view renders only JSON'
        errors(openApi, '/kettles')['application/json'].schema.$ref == ERRORS
        errors(openApi, '/kettles')['text/xml'].schema == null

        and: 'as one error, or several embedded'
        openApi.components.schemas['ValidationErrors'].oneOf.size() == 2
    }

    void 'describes the errors the converters render where JSON views fall back to the object view'() {
        given: 'JSON views without an errors view, which render the errors with the view for any object'
        view('object/_object.gson', OBJECT_VIEW)

        when:
        def openApi = document()

        then: 'the JSON the object view renders is not the errors view'
        errors(openApi, '/kettles')['application/json'].schema == null

        and: 'the converters render the XML'
        errors(openApi, '/kettles')['text/xml'].schema.$ref == ERRORS
        openApi.components.schemas['ValidationErrors'].properties.keySet() == ['errors'] as Set
    }

    void 'describes the errors a controller renders with an errors view of its own without a shape'() {
        given:
        view('object/_object.gson', OBJECT_VIEW)
        view('errors/_errors.gson', ERRORS_VIEW)
        view('kettle/_errors.gson', ERRORS_VIEW)
        view('admin/gizmo/_errors.gson', ERRORS_VIEW)

        when:
        def openApi = document()

        then: 'a controller with its own errors view'
        errors(openApi, '/kettles')['application/json'].schema == null
        errors(openApi, '/kettles')['text/xml'].schema == null

        and: 'a namespaced controller with its own errors view'
        errors(openApi, '/admin/gizmos')['application/json'].schema == null

        and: 'a controller rendering with the errors view'
        errors(openApi, '/sprockets')['application/json'].schema.$ref == ERRORS
    }

    private static final String OBJECT_VIEW = '''\
        @Field Object object

        json g.render(object)
        '''.stripIndent()

    private static final String ERRORS_VIEW = '''\
        import org.springframework.validation.Errors

        model {
            Errors errors
        }

        json {
            total errors.errorCount
        }
        '''.stripIndent()

    private void view(String path, String template) {
        File file = new File(views, path)
        file.parentFile.mkdirs()
        file.text = template
    }

    private OpenAPI document() {
        def resolver = new JsonViewResolver(new JsonViewConfiguration(templatePath: views.path))
        def controllers = [KettleController, GizmoController, SprocketController]
        OpenApiFixture.generator(OpenApiFixture.holder {
            '/kettles'(resources: 'kettle')
            '/admin/gizmos'(resources: 'gizmo', namespace: 'admin')
            '/sprockets'(resources: 'sprocket')
        }, OpenApiFixture.application(controllers, [resolver]), OpenApiFixture.context([Kettle])).generate()
    }

    private static Content errors(OpenAPI openApi, String path) {
        openApi.paths[path].post.responses['422'].content
    }
}

@Entity
class Kettle {
    String name
}

@Artefact('Controller')
class KettleController extends RestfulController<Kettle> {
    static responseFormats = ['json', 'xml']

    KettleController() { super(Kettle) }
}

@Artefact('Controller')
class GizmoController extends RestfulController<Kettle> {
    static namespace = 'admin'
    static responseFormats = ['json', 'xml']

    GizmoController() { super(Kettle) }
}

@Artefact('Controller')
class SprocketController extends RestfulController<Kettle> {
    static responseFormats = ['json', 'xml']

    SprocketController() { super(Kettle) }
}

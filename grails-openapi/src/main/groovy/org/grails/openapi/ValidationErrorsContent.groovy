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
package org.grails.openapi

import groovy.transform.CompileStatic

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.ComposedSchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema

import grails.core.GrailsControllerClass

/**
 * The validation errors a request that cannot be bound is answered with, described once as a
 * schema of the document's own.
 */
@CompileStatic
class ValidationErrorsContent {

    private final Components components
    private final ErrorsViews views
    private final String schemaName

    /**
     * @param views the views that render the errors in JSON
     * @param schemaName the name the errors are described under
     */
    ValidationErrorsContent(Components components, ErrorsViews views, String schemaName) {
        this.components = components
        this.views = views
        this.schemaName = schemaName
    }

    /**
     * The errors a failed validation answers with, in each media type. JSON views render them in
     * JSON, as the controller's own errors view does where it has one, and the converters render
     * them in any other format; those a view other than the errors view renders are listed without
     * a shape, as are those the converters render where the errors view renders the JSON, since the
     * schema describes the view.
     */
    Content content(GrailsControllerClass controller, Map<String, Boolean> mediaTypes) {
        ErrorsRendering json = controller != null && views.hasOwnView(controller)
                ? ErrorsRendering.OTHER_VIEW : views.rendering()
        Content content = new Content()
        mediaTypes.each { String mediaType, Boolean shaped ->
            boolean described = shaped && (mediaType in MediaTypes.JSON_MEDIA_TYPES
                    ? json != ErrorsRendering.OTHER_VIEW
                    : views.rendering() != ErrorsRendering.ERRORS_VIEW)
            content.addMediaType(mediaType, described ? new MediaType().schema(reference()) : new MediaType())
        }
        content
    }

    private Schema<?> reference() {
        if (!components.schemas?.containsKey(schemaName)) {
            components.addSchemas(schemaName, views.rendering() == ErrorsRendering.ERRORS_VIEW
                    ? viewErrors() : converterErrors())
        }
        ComponentSchemas.referenceTo(schemaName)
    }

    /**
     * The errors the JSON converters render.
     */
    private static Schema<?> converterErrors() {
        Schema<?> error = new ObjectSchema()
                .addProperty('object', new StringSchema().description('The name of the object that failed validation'))
                .addProperty('field', new StringSchema().description('The property that failed validation'))
                .addProperty('rejected-value', new Schema<>().description('The value that was rejected'))
                .addProperty('message', new StringSchema().description('Why the value was rejected'))
        error.setRequired(['object', 'message'])
        Schema<?> errors = new ObjectSchema()
                .description('The validation errors of a request that could not be bound')
                .addProperty('errors', new ArraySchema().items(error))
        errors.setRequired(['errors'])
        errors
    }

    /**
     * The errors the errors view of an application with JSON views renders, as the view an
     * application is generated with renders them: one error on its own, or several embedded.
     */
    private static Schema<?> viewErrors() {
        Schema<?> self = new ObjectSchema()
                .addProperty('href', new StringSchema().format('uri').description('The URL the request was made to'))
        Schema<?> error = new ObjectSchema()
                .addProperty('message', new StringSchema().description('Why the request could not be bound'))
                .addProperty('path', new StringSchema().description('The path the request was made to'))
                .addProperty('_links', new ObjectSchema().addProperty('self', self))
        error.setRequired(['message'])
        Schema<?> several = new ObjectSchema()
                .addProperty('total', new IntegerSchema().description('The number of errors'))
                .addProperty('_embedded', new ObjectSchema().addProperty('errors', new ArraySchema().items(error)))
        several.setRequired(['total', '_embedded'])
        Schema<?> errors = new ComposedSchema()
        errors.setDescription('The validation errors of a request that could not be bound')
        errors.setOneOf([error, several])
        errors
    }
}

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

import groovy.transform.CompileStatic

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.beans.factory.annotation.Autowired

import grails.gorm.validation.Constrained
import grails.gorm.validation.ConstrainedProperty
import grails.web.mapping.UrlMapping
import grails.web.mapping.UrlMappingsHolder
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.PersistentEntity

/**
 * Contributes the application's Grails URL mappings to the springdoc generated OpenAPI document.
 *
 * <p>springdoc builds its document from Spring MVC handler methods. Grails dispatches through its
 * own URL mappings rather than {@code @RequestMapping} handler methods, so without this customizer
 * a Grails application produces an OpenAPI document with no paths.</p>
 *
 * <p>Mappings that do not resolve to a statically known controller - such as the dynamic
 * {@code "/$controller/$action?/$id?"} mapping - are skipped, because their controller and action
 * are only known per request.</p>
 *
 * @author Scott Murphy Heiberg
 * @since 8.0
 */
@CompileStatic
class UrlMappingsOpenApiCustomizer implements OpenApiCustomizer {

    private static final String DEFAULT_MEDIA_TYPE = 'application/json'
    private static final String DEFAULT_RESPONSE_CODE = '200'
    private static final String NOT_FOUND_RESPONSE_CODE = '404'
    private static final String GREEDY_PARAMETER_NAME = 'path'
    private static final String FORMAT_PARAMETER_NAME = 'format'
    private static final String OPTIONAL_EXTENSION_SUFFIX = UrlMapping.OPTIONAL_EXTENSION_WILDCARD + '?'

    private static final List<String> COLLECTION_ACTIONS = ['index'].asImmutable()
    private static final List<String> BODY_METHODS = ['POST', 'PUT', 'PATCH'].asImmutable()

    private final UrlMappingsHolder urlMappingsHolder
    private final PersistentEntitySchemaBuilder schemaBuilder = new PersistentEntitySchemaBuilder()

    /**
     * The GORM mapping context, when the application has one. Without it the document still
     * describes paths, but carries no domain schemas.
     */
    @Autowired(required = false)
    MappingContext mappingContext

    UrlMappingsOpenApiCustomizer(UrlMappingsHolder urlMappingsHolder) {
        this.urlMappingsHolder = urlMappingsHolder
    }

    @Override
    void customise(OpenAPI openApi) {
        Map<String, PersistentEntity> entitiesByController = registerSchemas(openApi)
        Paths paths = openApi.paths ?: new Paths()

        for (UrlMapping mapping : urlMappingsHolder.urlMappings) {
            String controllerName = asStaticName(mapping.controllerName)
            if (!controllerName) {
                continue
            }

            List<String> pathNames = pathParameterNames(mapping)
            String path = toOpenApiPath(mapping, pathNames)
            if (!path) {
                continue
            }

            PathItem pathItem = paths.get(path) ?: new PathItem()
            PathItem.HttpMethod method = toHttpMethod(mapping.httpMethod)
            if (pathItem.readOperationsMap().containsKey(method)) {
                continue
            }

            pathItem.operation(method, buildOperation(mapping, controllerName, method, pathNames,
                    entitiesByController[controllerName]))
            paths.addPathItem(path, pathItem)
        }

        openApi.setPaths(paths)
    }

    /**
     * Registers a schema for every mapped domain class and indexes them by the controller name that
     * conventionally serves them, so operations can reference the right schema.
     */
    private Map<String, PersistentEntity> registerSchemas(OpenAPI openApi) {
        if (mappingContext == null) {
            return Collections.<String, PersistentEntity> emptyMap()
        }

        Components components = openApi.components ?: new Components()
        Map<String, PersistentEntity> byController = [:]

        for (PersistentEntity entity : mappingContext.persistentEntities) {
            components.addSchemas(PersistentEntitySchemaBuilder.schemaName(entity),
                    schemaBuilder.build(entity, mappingContext))
            components.addSchemas(PersistentEntitySchemaBuilder.requestSchemaName(entity),
                    schemaBuilder.buildRequest(entity, mappingContext))
            byController[entity.decapitalizedName] = entity
        }

        openApi.setComponents(components)
        byController
    }

    /**
     * Grails exposes controller and action names as {@code Object} because a mapping may define
     * them dynamically. Only statically declared names can be documented.
     */
    private static String asStaticName(Object name) {
        name instanceof String && name ? (String) name : null
    }

    /**
     * Converts a Grails URL pattern to an OpenAPI path, replacing each captured wildcard with the
     * name of the constraint that binds it.
     */
    private static String toOpenApiPath(UrlMapping mapping, List<String> names) {
        String pattern = stripOptionalExtension(mapping.urlData?.urlPattern)
        if (pattern == null) {
            return null
        }

        StringBuilder result = new StringBuilder()
        int index = 0
        int nameIndex = 0

        while (index < pattern.length()) {
            if (pattern.startsWith(UrlMapping.CAPTURED_DOUBLE_WILDCARD, index)) {
                result.append('{').append(GREEDY_PARAMETER_NAME).append('}')
                index += UrlMapping.CAPTURED_DOUBLE_WILDCARD.length()
                nameIndex++
            }
            else if (pattern.startsWith(UrlMapping.CAPTURED_WILDCARD, index)) {
                String name = nameIndex < names.size() ? names[nameIndex] : "param${nameIndex}".toString()
                result.append('{').append(name).append('}')
                index += UrlMapping.CAPTURED_WILDCARD.length()
                nameIndex++
            }
            else {
                result.append(pattern.charAt(index))
                index++
            }
        }

        String path = result.toString()
        if (!path) {
            return UrlMapping.SLASH
        }
        path.startsWith(UrlMapping.SLASH) ? path : UrlMapping.SLASH + path
    }

    /**
     * Removes the trailing Grails response format suffix. OpenAPI expresses the response format
     * through content types rather than a path segment.
     */
    private static String stripOptionalExtension(String pattern) {
        if (pattern == null) {
            return null
        }
        pattern.endsWith(OPTIONAL_EXTENSION_SUFFIX)
                ? pattern[0..<(pattern.length() - OPTIONAL_EXTENSION_SUFFIX.length())]
                : pattern
    }

    private static List<String> pathParameterNames(UrlMapping mapping) {
        Constrained[] constraints = mapping.constraints
        if (!constraints) {
            return Collections.<String> emptyList()
        }

        List<String> names = constraints.findResults { Constrained constrained ->
            constrained instanceof ConstrainedProperty ? ((ConstrainedProperty) constrained).propertyName : null
        } as List<String>

        // The format binding belongs to the optional extension that is stripped from the path.
        boolean hasExtension = mapping.urlData?.urlPattern?.endsWith(OPTIONAL_EXTENSION_SUFFIX)
        if (hasExtension && names && names.last() == FORMAT_PARAMETER_NAME) {
            names = names[0..<(names.size() - 1)]
        }
        names
    }

    /**
     * Grails allows a mapping to accept any HTTP method. OpenAPI requires a concrete operation, so
     * an unrestricted mapping is documented as GET.
     */
    private static PathItem.HttpMethod toHttpMethod(String httpMethod) {
        if (!httpMethod || httpMethod == UrlMapping.ANY_HTTP_METHOD) {
            return PathItem.HttpMethod.GET
        }
        PathItem.HttpMethod.valueOf(httpMethod.toUpperCase(Locale.ENGLISH))
    }

    private static Operation buildOperation(UrlMapping mapping, String controllerName, PathItem.HttpMethod method,
                                            List<String> pathNames, PersistentEntity entity) {
        String actionName = asStaticName(mapping.actionName)

        Operation operation = new Operation()
        operation.addTagsItem(controllerName)
        operation.setOperationId(operationId(controllerName, actionName, method))

        for (String name : pathNames) {
            operation.addParametersItem(
                    new Parameter()
                            .name(name)
                            .in('path')
                            .required(true)
                            .schema(new StringSchema()))
        }

        Schema<?> responseSchema = responseSchema(entity, actionName)
        MediaType mediaType = responseSchema ? new MediaType().schema(responseSchema) : new MediaType()
        ApiResponse response = new ApiResponse()
                .description('Success')
                .content(new Content().addMediaType(DEFAULT_MEDIA_TYPE, mediaType))
        operation.setResponses(new ApiResponses().addApiResponse(DEFAULT_RESPONSE_CODE, response))

        if (pathNames) {
            operation.responses.addApiResponse(NOT_FOUND_RESPONSE_CODE,
                    new ApiResponse().description('Not Found'))
        }

        if (entity && method.name() in BODY_METHODS) {
            operation.setRequestBody(new RequestBody().content(
                    new Content().addMediaType(DEFAULT_MEDIA_TYPE,
                            new MediaType().schema(requestReference(entity)))))
        }

        operation
    }

    /**
     * The index action responds with a collection; the remaining REST actions respond with a single
     * resource.
     */
    private static Schema<?> responseSchema(PersistentEntity entity, String actionName) {
        if (entity == null) {
            return null
        }
        actionName in COLLECTION_ACTIONS
                ? new ArraySchema().items(entityReference(entity))
                : entityReference(entity)
    }

    private static Schema<?> entityReference(PersistentEntity entity) {
        new Schema<>().$ref(PersistentEntitySchemaBuilder.referencePath(
                PersistentEntitySchemaBuilder.schemaName(entity)))
    }

    private static Schema<?> requestReference(PersistentEntity entity) {
        new Schema<>().$ref(PersistentEntitySchemaBuilder.referencePath(
                PersistentEntitySchemaBuilder.requestSchemaName(entity)))
    }

    private static String operationId(String controllerName, String actionName, PathItem.HttpMethod method) {
        actionName ? "${controllerName}_${actionName}_${method.name().toLowerCase(Locale.ENGLISH)}".toString()
                : "${controllerName}_${method.name().toLowerCase(Locale.ENGLISH)}".toString()
    }
}

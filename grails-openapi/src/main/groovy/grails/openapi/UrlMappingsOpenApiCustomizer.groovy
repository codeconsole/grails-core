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
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired

import grails.gorm.validation.Constrained
import grails.gorm.validation.ConstrainedProperty
import grails.core.GrailsApplication
import grails.core.GrailsClass
import grails.rest.RestfulController
import grails.web.mapping.UrlMapping
import grails.web.mapping.UrlMappingsHolder
import org.grails.core.artefact.ControllerArtefactHandler
import org.grails.web.mapping.ResponseCodeMappingData
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

    private static final Logger LOG = LoggerFactory.getLogger(UrlMappingsOpenApiCustomizer)

    private static final String DEFAULT_MEDIA_TYPE = 'application/json'
    private static final String DEFAULT_RESPONSE_CODE = '200'
    private static final String NOT_FOUND_RESPONSE_CODE = '404'
    private static final String UNPROCESSABLE_RESPONSE_CODE = '422'
    private static final String CONTROLLER_TOKEN = 'controller'
    private static final String ACTION_TOKEN = 'action'
    private static final String GREEDY_PARAMETER_NAME = 'path'
    private static final String FORMAT_PARAMETER_NAME = 'format'
    private static final String OPTIONAL_EXTENSION_SUFFIX = UrlMapping.OPTIONAL_EXTENSION_WILDCARD + '?'

    private static final List<String> BODY_METHODS = ['POST', 'PUT', 'PATCH'].asImmutable()

    private final UrlMappingsHolder urlMappingsHolder
    private final PersistentEntitySchemaBuilder schemaBuilder = new PersistentEntitySchemaBuilder()

    /**
     * The GORM mapping context, when the application has exactly one. Without it the document still
     * describes paths, but carries no domain schemas.
     */
    MappingContext mappingContext

    /**
     * Resolved through a provider rather than injected directly: an application with more than one
     * datastore has more than one mapping context, and {@code required = false} covers the absent
     * case but not the ambiguous one, which would fail the context rather than the document.
     */
    @Autowired(required = false)
    void setMappingContextProvider(ObjectProvider<MappingContext> provider) {
        this.mappingContext = provider?.getIfUnique()
    }

    /**
     * The application's artefacts, used to describe a RestfulController that no URL mapping names.
     * Without it only the declared URL mappings are documented.
     */
    @Autowired(required = false)
    GrailsApplication grailsApplication

    UrlMappingsOpenApiCustomizer(UrlMappingsHolder urlMappingsHolder) {
        this.urlMappingsHolder = urlMappingsHolder
    }

    @Override
    void customise(OpenAPI openApi) {
        Map<String, PersistentEntity> entitiesByController = indexEntitiesByController()
        Map<String, Class<?>> controllerClasses = indexControllerClasses()
        Map<String, PersistentEntity> documentedEntities = [:]
        Map<String, Class<?>> documentedCommands = [:]
        Paths paths = openApi.paths ?: new Paths()

        for (UrlMapping mapping : urlMappingsHolder.urlMappings) {
            describe("URL mapping [${mapping.urlData?.urlPattern}]".toString()) {
                addMappedOperation(paths, mapping, controllerClasses, entitiesByController,
                        documentedEntities, documentedCommands)
            }
        }

        addExpandedMappings(paths, entitiesByController, documentedEntities, documentedCommands)

        openApi.setPaths(paths)
        registerSchemas(openApi, documentedEntities, documentedCommands)
    }

    /**
     * Describes every {@link RestfulController} at the URLs the default
     * {@code "/$controller/$action?/$id?"} mapping serves. A controller that a declared mapping also
     * names is reachable both ways, and both are documented: an endpoint that answers but is absent
     * from the document is worse than one described twice.
     */
    /**
     * Describes one operation from a mapping that names its controller.
     */
    private void addMappedOperation(Paths paths, UrlMapping mapping, Map<String, Class<?>> controllerClasses,
                                    Map<String, PersistentEntity> entitiesByController,
                                    Map<String, PersistentEntity> documentedEntities,
                                    Map<String, Class<?>> documentedCommands) {
        String controllerName = asStaticName(mapping.controllerName)
        if (!controllerName || isResponseCode(mapping)) {
            return
        }

        String mappedAction = asStaticName(mapping.actionName)
        Class<?> controllerType = controllerClasses[controllerName]
        if (ActionAnnotations.isHidden(controllerType)
                || (mappedAction && ActionAnnotations.isHidden(controllerType, mappedAction))) {
            return
        }

        List<String> pathNames = pathParameterNames(mapping)
        String path = toOpenApiPath(mapping, pathNames)
        if (!path) {
            return
        }

        PathItem pathItem = paths.get(path) ?: new PathItem()
        PathItem.HttpMethod method = toHttpMethod(mapping.httpMethod)
        if (method == null || pathItem.readOperationsMap().containsKey(method)) {
            return
        }

        PersistentEntity entity = entitiesByController[controllerName]
        record(entity, documentedEntities)

        Class<?> commandType = requestBodyType(controllerType, mappedAction, method, documentedCommands)
        recordDeclaredResponseTypes(controllerType, mappedAction, documentedCommands)
        Operation operation = buildOperation(mapping, controllerName, method, pathNames, entity, commandType,
                controllerType != null && RestfulController.isAssignableFrom(controllerType))
        ActionAnnotations.apply(operation, controllerType, mappedAction)
        pathItem.operation(method, operation)
        paths.addPathItem(path, pathItem)
    }

    /**
     * Describes the RestfulControllers a mapping reaches without naming, which is how a REST
     * application is mapped: {@code get "/$controller"(action: 'index')} names the action but leaves
     * the controller to the request, and the default {@code "/$controller/$action?/$id?"} mapping
     * leaves both.
     *
     * <p>Expansion follows the mapping rather than the controllers, so only routes the application
     * actually serves are described. An application without such a mapping gets none of them.</p>
     */
    private void addExpandedMappings(Paths paths, Map<String, PersistentEntity> entitiesByController,
                                     Map<String, PersistentEntity> documentedEntities,
                                     Map<String, Class<?>> documentedCommands) {
        if (grailsApplication == null) {
            return
        }

        List<GrailsClass> controllers = restfulControllers()
        if (!controllers) {
            return
        }

        for (UrlMapping mapping : urlMappingsHolder.urlMappings) {
            if (asStaticName(mapping.controllerName) || mapping.viewName || isResponseCode(mapping)) {
                continue
            }

            List<String> names = pathParameterNames(mapping)
            if (!names.contains(CONTROLLER_TOKEN)) {
                continue
            }

            String mappedAction = asStaticName(mapping.actionName)
            boolean expandsAction = mappedAction == null && names.contains(ACTION_TOKEN)
            if (mappedAction == null && !expandsAction) {
                continue
            }

            for (GrailsClass controllerClass : controllers) {
                Collection<String> actions = expandsAction
                        ? actionNames(controllerClass)
                        : Collections.singletonList(mappedAction)
                for (String actionName : actions) {
                    describe("action [${controllerClass.logicalPropertyName}.${actionName}]".toString()) {
                        addExpandedOperation(paths, mapping, names, controllerClass, actionName, expandsAction,
                                entitiesByController, documentedEntities, documentedCommands)
                    }
                }
            }
        }
    }

    private void addExpandedOperation(Paths paths, UrlMapping mapping, List<String> names,
                                      GrailsClass controllerClass, String actionName, boolean expandsAction,
                                      Map<String, PersistentEntity> entitiesByController,
                                      Map<String, PersistentEntity> documentedEntities,
                                      Map<String, Class<?>> documentedCommands) {
        if (!actionName || ActionAnnotations.isHidden(controllerClass.clazz, actionName)) {
            return
        }
        // A mapping that names the action reaches every controller; one that leaves the action to
        // the request only reaches the actions that controller declares.
        if (!expandsAction && !actionNames(controllerClass).contains(actionName)) {
            return
        }

        String controllerName = controllerClass.logicalPropertyName
        boolean takesId
        String path
        if (expandsAction) {
            takesId = RestfulControllerActions.takesId(actionName)
            path = takesId
                    ? "/${controllerName}/${actionName}/{id}".toString()
                    : "/${controllerName}/${actionName}".toString()
        }
        else {
            path = expandPath(mapping, names, controllerName)
            takesId = path?.contains('{')
        }
        if (!path) {
            return
        }

        PathItem.HttpMethod method = expandsAction
                ? toHttpMethod(RestfulControllerActions.httpMethod(actionName,
                        controllerClass.getPropertyValue('allowedMethods')))
                : toHttpMethod(mapping.httpMethod)

        PathItem pathItem = paths.get(path) ?: new PathItem()
        if (method == null || pathItem.readOperationsMap().containsKey(method)) {
            return
        }

        PersistentEntity entity = entitiesByController[controllerName]
        record(entity, documentedEntities)

        Class<?> commandType = requestBodyType(controllerClass.clazz, actionName, method, documentedCommands)
        recordDeclaredResponseTypes(controllerClass.clazz, actionName, documentedCommands)
        Operation operation = buildAction(controllerName, actionName, method, takesId, entity, commandType,
                expandedOperationId(controllerName, actionName, method, expandsAction))
        ActionAnnotations.apply(operation, controllerClass.clazz, actionName)
        pathItem.operation(method, operation)
        paths.addPathItem(path, pathItem)
    }

    /**
     * The mapping's own pattern with the controller substituted, so the described path is the one
     * the mapping serves rather than one assembled from a convention.
     */
    private static String expandPath(UrlMapping mapping, List<String> names, String controllerName) {
        String pattern = stripOptionalExtension(mapping.urlData?.urlPattern)
        if (pattern == null) {
            return null
        }

        StringBuilder result = new StringBuilder()
        int index = 0
        int nameIndex = 0
        while (index < pattern.length()) {
            if (pattern.startsWith(UrlMapping.CAPTURED_WILDCARD, index)) {
                String name = nameIndex < names.size() ? names[nameIndex] : "param${nameIndex}".toString()
                result.append(name == CONTROLLER_TOKEN ? controllerName : "{${name}}".toString())
                index += UrlMapping.CAPTURED_WILDCARD.length()
                nameIndex++
                if (index < pattern.length() && pattern.charAt(index) == ('?' as char)) {
                    index++
                }
            }
            else {
                result.append(pattern.charAt(index))
                index++
            }
        }

        String path = result.toString()
        path.startsWith(UrlMapping.SLASH) ? path : UrlMapping.SLASH + path
    }

    /**
     * Distinguishes an operation reached through the default mapping from the same action reached
     * through a mapping that names it, so the document does not carry two of the same identifier.
     */
    private static String expandedOperationId(String controllerName, String actionName,
                                              PathItem.HttpMethod method, boolean expandsAction) {
        String derived = "${controllerName}_${actionName}_${method.name().toLowerCase(Locale.ENGLISH)}".toString()
        expandsAction ? derived + '_byAction' : derived
    }

    private List<GrailsClass> restfulControllers() {
        grailsApplication.getArtefacts(ControllerArtefactHandler.TYPE).findAll { GrailsClass it ->
            RestfulController.isAssignableFrom(it.clazz) && !ActionAnnotations.isHidden(it.clazz)
        }.toList()
    }

    /**
     * Notes the resource a documented operation serves, so its schema is registered.
     */
    private static void record(PersistentEntity entity, Map<String, PersistentEntity> documentedEntities) {
        if (entity) {
            documentedEntities[PersistentEntitySchemaBuilder.schemaName(entity)] = entity
        }
    }

    private static Collection<String> actionNames(GrailsClass controllerClass) {
        controllerClass instanceof grails.core.GrailsControllerClass
                ? ((grails.core.GrailsControllerClass) controllerClass).actions
                : Collections.<String> emptySet()
    }

    private static Operation buildAction(String controllerName, String actionName, PathItem.HttpMethod method,
                                         boolean takesId, PersistentEntity entity, Class<?> commandType,
                                         String operationId) {
        Operation operation = new Operation()
        operation.addTagsItem(controllerName)
        operation.setOperationId(operationId)

        if (takesId) {
            operation.addParametersItem(new Parameter()
                    .name('id')
                    .in('path')
                    .required(true)
                    .schema(new StringSchema()))
        }

        operation.setResponses(restfulResponses(entity, actionName, takesId))

        Schema<?> bodySchema = requestBodySchema(entity, commandType, method)
        if (bodySchema) {
            operation.setRequestBody(new RequestBody().content(
                    new Content().addMediaType(DEFAULT_MEDIA_TYPE, new MediaType().schema(bodySchema))))
        }

        operation
    }

    /**
     * Registers a schema for every mapped domain class and indexes them by the controller name that
     * conventionally serves them, so operations can reference the right schema.
     */
    private Map<String, Class<?>> indexControllerClasses() {
        if (grailsApplication == null) {
            return Collections.<String, Class<?>> emptyMap()
        }
        Map<String, Class<?>> byName = [:]
        for (GrailsClass controllerClass : grailsApplication.getArtefacts(ControllerArtefactHandler.TYPE)) {
            byName[controllerClass.logicalPropertyName] = controllerClass.clazz
        }
        byName
    }

    private Map<String, PersistentEntity> indexEntitiesByController() {
        if (mappingContext == null) {
            return Collections.<String, PersistentEntity> emptyMap()
        }
        Map<String, PersistentEntity> byController = [:]
        for (PersistentEntity entity : mappingContext.persistentEntities) {
            byController[entity.decapitalizedName] = entity
        }
        byController
    }

    /**
     * Registers only the schemas the document actually references, so a domain class with no
     * documented operation does not appear as an orphan. Associations are followed so that a
     * referenced schema never points at one that was left out.
     */
    private void registerSchemas(OpenAPI openApi, Map<String, PersistentEntity> documentedEntities,
                                 Map<String, Class<?>> documentedCommands) {
        if (!documentedEntities && !documentedCommands) {
            return
        }

        Components components = openApi.components ?: new Components()
        for (Class<?> commandType : documentedCommands.values()) {
            describe("command [${commandType.name}]".toString()) {
                schemaBuilder.buildCommand(commandType, openApi.specVersion).each { String name, Schema<?> schema ->
                    components.addSchemas(name, schema)
                }
            }
        }
        if (mappingContext == null) {
            openApi.setComponents(components)
            return
        }

        for (PersistentEntity entity : documentedEntities.values()) {
            // swagger-core resolves the classes an entity is associated with, so each documented
            // resource contributes its own schema and everything it refers to.
            describe("domain class [${entity.javaClass.name}]".toString()) {
                schemaBuilder.build(entity, mappingContext, openApi.specVersion).each { String name, Schema<?> schema ->
                    components.addSchemas(name, schema)
                }
            }
        }
        openApi.setComponents(components)
    }

    /**
     * Grails exposes controller and action names as {@code Object} because a mapping may define
     * them dynamically. Only statically declared names can be documented.
     */
    /**
     * Describes one part of the document, leaving the rest intact if it cannot be described. An
     * application should not lose its whole API description because one class cannot be introspected.
     */
    private static void describe(String what, Closure<?> work) {
        try {
            work.call()
        }
        catch (Exception e) {
            LOG.warn('Skipping {} in the OpenAPI document: {}', what, e.message)
            LOG.debug('Could not describe {}', what, e)
        }
    }

    /**
     * A mapping declared for a status code rather than a URL, whose pattern is the code itself.
     */
    private static boolean isResponseCode(UrlMapping mapping) {
        mapping.urlData instanceof ResponseCodeMappingData
    }

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
                // The greedy token binds a constraint like any other, so it is named for it rather
                // than generically: the template variable and the declared parameter must agree.
                String name = nameIndex < names.size() ? names[nameIndex] : GREEDY_PARAMETER_NAME
                result.append('{').append(name).append('}')
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
        try {
            return PathItem.HttpMethod.valueOf(httpMethod.toUpperCase(Locale.ENGLISH))
        }
        catch (IllegalArgumentException ignored) {
            // A mapping can name a method OpenAPI has no operation for. The mapping is skipped
            // rather than failing the whole document.
            LOG.warn('Skipping a URL mapping declared for the unsupported HTTP method [{}]', httpMethod)
            return null
        }
    }

    private static Operation buildOperation(UrlMapping mapping, String controllerName, PathItem.HttpMethod method,
                                            List<String> pathNames, PersistentEntity entity, Class<?> commandType,
                                            boolean restfulController) {
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

        // A RestfulController's responses are known from what it does, whether it was reached
        // through a mapping that names it or through the default one.
        if (restfulController && actionName) {
            operation.setResponses(restfulResponses(entity, actionName, pathNames as boolean))
        }
        else {
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
        }

        Schema<?> bodySchema = requestBodySchema(entity, commandType, method)
        if (bodySchema) {
            operation.setRequestBody(new RequestBody().content(
                    new Content().addMediaType(DEFAULT_MEDIA_TYPE, new MediaType().schema(bodySchema))))
        }

        operation
    }

    /**
     * The responses a RestfulController action gives, read from what the controller does rather
     * than assumed: save answers CREATED, delete answers NO_CONTENT with no body, an action
     * addressed by an identifier can miss, and an action that validates what it binds can answer
     * with the validation errors.
     */
    private static ApiResponses restfulResponses(PersistentEntity entity, String actionName, boolean takesId) {
        ApiResponses responses = new ApiResponses()

        ApiResponse success = new ApiResponse().description('Success')
        if (RestfulControllerActions.hasResponseBody(actionName)) {
            Schema<?> schema = responseSchema(entity, actionName)
            MediaType mediaType = schema ? new MediaType().schema(schema) : new MediaType()
            success.setContent(new Content().addMediaType(DEFAULT_MEDIA_TYPE, mediaType))
        }
        responses.addApiResponse(RestfulControllerActions.successCode(actionName), success)

        if (takesId) {
            responses.addApiResponse(NOT_FOUND_RESPONSE_CODE, new ApiResponse().description('Not Found'))
        }
        if (RestfulControllerActions.validates(actionName)) {
            responses.addApiResponse(UNPROCESSABLE_RESPONSE_CODE, new ApiResponse().description('Validation failed'))
        }
        responses
    }

    /**
     * The index action responds with a collection; the remaining REST actions respond with a single
     * resource.
     */
    private static Schema<?> responseSchema(PersistentEntity entity, String actionName) {
        if (entity == null) {
            return null
        }
        RestfulControllerActions.isCollection(actionName)
                ? new ArraySchema().items(entityReference(entity))
                : entityReference(entity)
    }

    /**
     * The type an action binds. A command object is what the action actually receives, so it
     * describes the body in preference to the resource the controller is named for.
     */
    private static Class<?> requestBodyType(Class<?> controllerClass, String actionName, PathItem.HttpMethod method,
                                            Map<String, Class<?>> documentedCommands) {
        if (!(method.name() in BODY_METHODS)) {
            return null
        }
        Class<?> commandType = ActionAnnotations.commandObjectType(controllerClass, actionName)
        if (commandType != null) {
            documentedCommands[commandType.simpleName] = commandType
        }
        commandType
    }

    /**
     * A type an application named as a response schema is described the same way a command object
     * is, so the reference the annotation produces resolves.
     */
    private static void recordDeclaredResponseTypes(Class<?> controllerClass, String actionName,
                                                    Map<String, Class<?>> documented) {
        for (Class<?> type : ActionAnnotations.declaredResponseTypes(controllerClass, actionName)) {
            documented[type.simpleName] = type
        }
    }

    private static Schema<?> requestBodySchema(PersistentEntity entity, Class<?> commandType, PathItem.HttpMethod method) {
        if (!(method.name() in BODY_METHODS)) {
            return null
        }
        if (commandType != null) {
            return new Schema<>().$ref(PersistentEntitySchemaBuilder.referencePath(commandType.simpleName))
        }
        entity ? entityReference(entity) : null
    }

    private static Schema<?> entityReference(PersistentEntity entity) {
        new Schema<>().$ref(PersistentEntitySchemaBuilder.referencePath(
                PersistentEntitySchemaBuilder.schemaName(entity)))
    }

    private static String operationId(String controllerName, String actionName, PathItem.HttpMethod method) {
        actionName ? "${controllerName}_${actionName}_${method.name().toLowerCase(Locale.ENGLISH)}".toString()
                : "${controllerName}_${method.name().toLowerCase(Locale.ENGLISH)}".toString()
    }
}

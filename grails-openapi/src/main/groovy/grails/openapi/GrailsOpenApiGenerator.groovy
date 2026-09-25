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

import java.lang.reflect.Method as ReflectedMethod
import java.lang.reflect.Parameter as ReflectedParameter

import groovy.transform.CompileStatic

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.core.converter.ResolvedSchema
import io.swagger.v3.core.util.Json
import io.swagger.v3.core.util.Json31
import io.swagger.v3.core.util.PrimitiveType
import io.swagger.v3.core.util.Yaml
import io.swagger.v3.core.util.Yaml31
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.SpecVersion
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.tags.Tag
import io.swagger.v3.oas.annotations.tags.Tag as TagAnnotation
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.core.GenericTypeResolver
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader

import grails.core.GrailsApplication
import grails.core.GrailsClass
import grails.core.GrailsControllerClass
import grails.plugins.VersionComparator
import grails.rest.RestfulController
import grails.web.http.HttpHeaders
import grails.web.mapping.UrlMapping
import grails.web.mapping.UrlMappingsHolder
import grails.web.mime.MimeType
import org.grails.core.artefact.ControllerArtefactHandler
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.openapi.ActionAnnotations
import org.grails.openapi.GrailsModelConverter
import org.grails.openapi.RestfulControllerActions
import org.grails.openapi.SchemaNames
import org.grails.openapi.SchemaReferences
import org.grails.openapi.UrlMappingPaths
import org.grails.web.mapping.ResponseCodeMappingData

/**
 * Generates an OpenAPI description of a Grails application from its URL mappings, its
 * controllers and its GORM mapping context.
 *
 * <p>The generator depends on nothing but the application, so the same description is produced
 * at build time by the {@code generate-open-api} command and at runtime by springdoc, which this
 * module contributes the description to when springdoc is on the classpath.</p>
 *
 * @since 8.0
 */
@CompileStatic
class GrailsOpenApiGenerator {

    /**
     * The schema describing the validation errors Grails renders when a request cannot be bound.
     */
    static final String VALIDATION_ERRORS_SCHEMA = 'ValidationErrors'

    private static final Logger LOG = LoggerFactory.getLogger(GrailsOpenApiGenerator)

    private static final String DEFAULT_MEDIA_TYPE = ActionAnnotations.DEFAULT_MEDIA_TYPE
    private static final String DEFAULT_RESPONSE_CODE = '200'
    private static final String NOT_FOUND_RESPONSE_CODE = '404'
    private static final String UNPROCESSABLE_RESPONSE_CODE = '422'
    private static final String CONTROLLER_TOKEN = 'controller'
    private static final String ACTION_TOKEN = 'action'
    private static final String NAMESPACE_TOKEN = 'namespace'
    private static final String ID_TOKEN = 'id'
    private static final String ID_SUFFIX = 'Id'
    private static final String PATCH_SUFFIX = 'Patch'
    private static final String RESPONSE_FORMATS = 'responseFormats'
    private static final String ALLOWED_METHODS = 'allowedMethods'
    private static final String LOCATION_HEADER = 'Location'
    private static final String MULTIPART_MEDIA_TYPE = 'multipart/form-data'
    private static final Set<String> DATA_FORMATS = ['json', 'xml'].toSet().asImmutable()
    private static final VersionComparator VERSION_COMPARATOR = new VersionComparator()
    private static final String REFERENCE_PREFIX = '#/components/schemas/'

    private static final List<String> BODY_METHODS = ['POST', 'PUT', 'PATCH'].asImmutable()

    private final GrailsApplication grailsApplication
    private final UrlMappingsHolder urlMappingsHolder
    private final Collection<MappingContext> mappingContexts
    private final OpenApiSettings settings
    private final ResourceLoader resourceLoader

    /**
     * @param grailsApplication the application whose controllers are described
     * @param urlMappingsHolder the URL mappings whose paths are described
     * @param mappingContexts the GORM mapping contexts whose entities are described, which may be
     * empty in an application without GORM
     * @param settings what to describe
     */
    GrailsOpenApiGenerator(GrailsApplication grailsApplication, UrlMappingsHolder urlMappingsHolder,
                           Collection<MappingContext> mappingContexts, OpenApiSettings settings) {
        this.grailsApplication = grailsApplication
        this.urlMappingsHolder = urlMappingsHolder
        this.mappingContexts = mappingContexts ?: Collections.<MappingContext> emptyList()
        this.settings = settings ?: new OpenApiSettings()
        this.resourceLoader = grailsApplication?.mainContext ?: new DefaultResourceLoader(GrailsOpenApiGenerator.classLoader)
    }

    OpenApiSettings getSettings() {
        settings
    }

    /**
     * @return the default document
     */
    OpenAPI generate() {
        generate(settings.defaultSelection)
    }

    /**
     * @param group the name of a group in {@code springdoc.group-configs}
     * @return the document of that group
     * @throws IllegalArgumentException if no such group is configured
     */
    OpenAPI generate(String group) {
        OpenApiSelection selection = settings.group(group)
        if (selection == null) {
            throw new IllegalArgumentException("No OpenAPI group named [${group}] is configured".toString())
        }
        generate(selection)
    }

    /**
     * @return a complete document of the operations the selection selects
     */
    OpenAPI generate(OpenApiSelection selection) {
        boolean openapi31 = settings.specVersion == SpecVersion.V31
        OpenAPI openApi = new OpenAPI(settings.specVersion).openapi(openapi31 ? '3.1.0' : '3.0.1')
        String name = grailsApplication?.config?.getProperty('info.app.name', String)
        String version = grailsApplication?.config?.getProperty('info.app.version', String)
        openApi.setInfo(new Info()
                .title(selection?.displayName ?: name ?: 'Grails application')
                .version(version ?: '1.0'))
        contribute(openApi, selection)
        if (openApi.info?.version == null) {
            openApi.info.setVersion(version ?: '1.0')
        }
        openApi
    }

    /**
     * Adds the described operations, and the base document when one is configured, to a document
     * something else has started.
     */
    void contribute(OpenAPI openApi, OpenApiSelection selection) {
        if (!settings.enabled) {
            return
        }
        GrailsModelConverter.register()
        GrailsModelConverter.withMappingContexts(mappingContexts, settings.includeVersion) {
            new Contribution(openApi, selection ?: new OpenApiSelection()).contribute()
        }
    }

    /**
     * Writes a document in the format its extension names: {@code .json} or YAML otherwise.
     */
    static String serialize(OpenAPI openApi, String format) {
        boolean openapi31 = openApi.specVersion == SpecVersion.V31
        boolean json = format?.equalsIgnoreCase('json')
        if (openapi31) {
            return json ? Json31.pretty(openApi) : Yaml31.pretty(openApi)
        }
        json ? Json.pretty(openApi) : Yaml.pretty(openApi)
    }

    /**
     * The state of adding the operations to one document.
     */
    private class Contribution {

        private final OpenAPI openApi
        private final OpenApiSelection selection
        private final boolean openapi31
        private final Components components
        private final Paths paths
        private final Map<String, GrailsControllerClass> controllersByKey = [:]
        private final Map<String, List<GrailsControllerClass>> controllersByName = [:]
        private final Map<Class<?>, Object> controllerInstances = [:]
        private final SchemaNames schemaNames = new SchemaNames()
        private final Set<String> addedSchemas = [] as Set
        private final Map<String, String> patchSchemas = [:]
        private Map<String, String> formatMediaTypes
        private final Map<Class<?>, Class<?>> boundResources = [:]
        private Map<String, String> latestVersions

        Contribution(OpenAPI openApi, OpenApiSelection selection) {
            this.openApi = openApi
            this.selection = selection
            this.openapi31 = openApi.specVersion == SpecVersion.V31
            this.components = openApi.components ?: new Components()
            this.paths = openApi.paths ?: new Paths()
            indexControllers()
        }

        void contribute() {
            mergeBaseDocument()

            // A name the document already has, from the base document or springdoc, or that it
            // derives rather than resolves, is not taken by a class of the same name.
            components.schemas?.keySet()?.each { String name -> schemaNames.reserve(name) }
            schemaNames.reserve(VALIDATION_ERRORS_SCHEMA)
            GrailsModelConverter.withSchemaNames(schemaNames) {
                for (UrlMapping mapping : urlMappingsHolder.urlMappings) {
                    describe("URL mapping [${mapping.urlData?.urlPattern}]".toString()) {
                        addMappedOperations(mapping)
                    }
                }
                addExpandedMappings()
            }
            disambiguateOperationIds()

            openApi.setPaths(paths)
            openApi.setComponents(components)
            SchemaReferences.rename(openApi, schemaRenames())
            registerTags()
            dropUnresolvedReferences()
            if (!components.schemas && !components.securitySchemes && !components.responses
                    && !components.parameters && !components.examples && !components.requestBodies
                    && !components.headers && !components.links && !components.callbacks) {
                openApi.setComponents(null)
            }
        }

        private void indexControllers() {
            if (grailsApplication == null) {
                return
            }
            for (GrailsClass artefact : grailsApplication.getArtefacts(ControllerArtefactHandler.TYPE)) {
                if (!(artefact instanceof GrailsControllerClass)) {
                    continue
                }
                GrailsControllerClass controller = (GrailsControllerClass) artefact
                controllersByKey[controllerKey(controller.namespace, controller.logicalPropertyName)] = controller
                controllersByName.computeIfAbsent(controller.logicalPropertyName) { [] }.add(controller)
            }
        }

        /**
         * The controller a mapping dispatches to. A mapping that names a namespace reaches that
         * namespace's controller; one that names none reaches the controller without a namespace,
         * or the only controller of that name.
         */
        private GrailsControllerClass controllerFor(String name, String namespace) {
            GrailsControllerClass exact = controllersByKey[controllerKey(namespace, name)]
            if (exact != null || namespace) {
                return exact
            }
            List<GrailsControllerClass> named = controllersByName[name]
            named?.size() == 1 ? named.first() : null
        }

        private void mergeBaseDocument() {
            OpenAPI base = readBaseDocument()
            if (base == null) {
                return
            }
            if (base.info != null) {
                openApi.setInfo(base.info)
            }
            if (base.servers) {
                openApi.setServers(base.servers)
            }
            if (base.security) {
                openApi.setSecurity(base.security)
            }
            if (base.externalDocs != null) {
                openApi.setExternalDocs(base.externalDocs)
            }
            base.tags?.each { Tag tag ->
                if (!openApi.tags?.any { Tag existing -> existing.name == tag.name }) {
                    openApi.addTagsItem(tag)
                }
            }
            base.extensions?.each { String name, Object value -> openApi.addExtension(name, value) }
            // A path the base document declares belongs to the documents whose paths select it.
            base.paths?.each { String path, PathItem item ->
                if (!paths.containsKey(path) && selection.selectsPath(path)) {
                    paths.addPathItem(path, item)
                }
            }
            Components declared = base.components
            if (declared != null) {
                declared.schemas?.each { String name, Schema schema -> components.addSchemas(name, schema) }
                declared.securitySchemes?.each { name, scheme -> components.addSecuritySchemes(name, scheme) }
                declared.responses?.each { name, response -> components.addResponses(name, response) }
                declared.parameters?.each { name, parameter -> components.addParameters(name, parameter) }
                declared.examples?.each { name, example -> components.addExamples(name, example) }
                declared.requestBodies?.each { name, body -> components.addRequestBodies(name, body) }
                declared.headers?.each { name, header -> components.addHeaders(name, header) }
                declared.links?.each { name, link -> components.addLinks(name, link) }
                declared.callbacks?.each { name, callback -> components.addCallbacks(name, callback) }
            }
        }

        private OpenAPI readBaseDocument() {
            if (!settings.baseDocument) {
                return null
            }
            Resource resource = resourceLoader.getResource(settings.baseDocument)
            if (!resource.exists()) {
                throw new IllegalStateException(
                        "The OpenAPI base document [${settings.baseDocument}] does not exist".toString())
            }
            String text = resource.inputStream.withCloseable { InputStream input -> input.getText('UTF-8') }
            boolean json = text.trim().startsWith('{')
            Class<OpenAPI> type = OpenAPI
            if (openapi31) {
                return json ? Json31.mapper().readValue(text, type) : Yaml31.mapper().readValue(text, type)
            }
            json ? Json.mapper().readValue(text, type) : Yaml.mapper().readValue(text, type)
        }

        /**
         * Describes the operations of a mapping that names its controller.
         */
        private void addMappedOperations(UrlMapping mapping) {
            String controllerName = asStaticName(mapping.controllerName)
            if (!controllerName || isResponseCode(mapping)) {
                return
            }
            GrailsControllerClass controller = controllerFor(controllerName, asStaticName(mapping.namespace))
            Object declaredAction = mapping.actionName
            if (declaredAction instanceof Map) {
                // The action is chosen by the method of the request, so there is an operation for each.
                ((Map<Object, Object>) declaredAction).each { Object method, Object action ->
                    addMappedOperation(mapping, controller, controllerName, asStaticName(action), method?.toString())
                }
                return
            }
            if (declaredAction != null && !(declaredAction instanceof CharSequence)) {
                LOG.warn('Skipping the URL mapping [{}]: its action is decided as each request is made',
                        mapping.urlData?.urlPattern)
                return
            }
            String actionName = asStaticName(declaredAction)
            if (actionName == null && controller != null && UrlMappingPaths.variableNames(mapping).contains(ACTION_TOKEN)) {
                // The action is taken from the path, so every action the controller declares is reached.
                for (String action : controller.actions) {
                    describe("action [${controllerName}.${action}]".toString()) {
                        addExpandedOperation(mapping, controller, action, true)
                    }
                }
                return
            }
            // A mapping that names only the controller dispatches to its default action.
            addMappedOperation(mapping, controller, controllerName, actionName ?: controller?.defaultAction, mapping.httpMethod)
        }

        private void addMappedOperation(UrlMapping mapping, GrailsControllerClass controller, String controllerName,
                                        String actionName, String mappedMethod) {
            Class<?> controllerType = controller?.clazz
            if (!isDescribed(controller, controllerType, actionName)) {
                return
            }
            for (PathItem.HttpMethod method : httpMethods(mappedMethod, controller, actionName)) {
                for (String path : UrlMappingPaths.paths(mapping)) {
                    addOperation(mapping, path, method, controller, controllerType, controllerName, actionName,
                            operationId(controller, controllerName, actionName, method))
                }
            }
        }

        /**
         * The methods an action is described as answering: the one the mapping declares, unless the
         * controller's {@code allowedMethods} refuses it for the action, which Grails answers with
         * 405; or, where the mapping accepts any method, those {@code allowedMethods} declares for the
         * action, or the one its kind of action answers.
         */
        private List<PathItem.HttpMethod> httpMethods(String mappedMethod, GrailsControllerClass controller,
                                                      String actionName) {
            List<String> allowed = RestfulControllerActions.allowedMethods(actionName,
                    controller?.getPropertyValue(ALLOWED_METHODS))
            List<String> methods
            if (mappedMethod && mappedMethod != UrlMapping.ANY_HTTP_METHOD) {
                String declared = mappedMethod.toUpperCase(Locale.ENGLISH)
                methods = allowed == null || declared in allowed ? [declared] : []
            }
            else {
                methods = allowed ?: [RestfulControllerActions.defaultMethod(actionName, isResourceController(controller))]
            }
            methods.collect { String method -> toHttpMethod(method) }.findAll().unique()
        }

        /**
         * Describes the RestfulControllers a mapping reaches without naming, which is how a REST
         * application is mapped: {@code get "/$controller"(action: 'index')} names the action but
         * leaves the controller to the request, and the default
         * {@code "/$controller/$action?/$id?"} mapping leaves both.
         *
         * <p>Expansion follows the mapping rather than the controllers, so only routes the
         * application actually serves are described.</p>
         */
        private void addExpandedMappings() {
            List<GrailsControllerClass> controllers = controllersByKey.values().findAll { GrailsControllerClass it ->
                isRestController(it) && !ActionAnnotations.isHidden(it.clazz)
            }.toList()
            if (!controllers) {
                return
            }

            for (UrlMapping mapping : urlMappingsHolder.urlMappings) {
                if (asStaticName(mapping.controllerName) || mapping.viewName || isResponseCode(mapping)) {
                    continue
                }
                List<String> names = UrlMappingPaths.variableNames(mapping)
                if (!names.contains(CONTROLLER_TOKEN)) {
                    continue
                }
                String mappedAction = asStaticName(mapping.actionName)
                boolean expandsAction = mappedAction == null && names.contains(ACTION_TOKEN)
                if (mappedAction == null && !expandsAction) {
                    continue
                }

                boolean capturesNamespace = names.contains(NAMESPACE_TOKEN)
                for (GrailsControllerClass controller : controllers) {
                    // A mapping that leaves the namespace out reaches the controller Grails resolves for
                    // the name alone; one that captures it reaches each controller at its own.
                    if (!capturesNamespace && !controllerFor(controller.logicalPropertyName, null).is(controller)) {
                        continue
                    }
                    Collection<String> actions = expandsAction ? controller.actions : [mappedAction]
                    for (String actionName : actions) {
                        describe("action [${controller.logicalPropertyName}.${actionName}]".toString()) {
                            addExpandedOperation(mapping, controller, actionName, expandsAction)
                        }
                    }
                }
            }
        }

        private void addExpandedOperation(UrlMapping mapping, GrailsControllerClass controller,
                                          String actionName, boolean expandsAction) {
            // A mapping that names the action reaches every controller; one that leaves the action to
            // the request only reaches the actions that controller declares.
            if (!actionName || !controller.actions.contains(actionName)
                    || !isDescribed(controller, controller.clazz, actionName)) {
                return
            }

            String controllerName = controller.logicalPropertyName
            Map<String, String> substitutions = [(CONTROLLER_TOKEN): controllerName]
            Set<String> omitted = [] as Set
            if (UrlMappingPaths.variableNames(mapping).contains(NAMESPACE_TOKEN)) {
                if (controller.namespace) {
                    substitutions[NAMESPACE_TOKEN] = controller.namespace
                }
                else if (UrlMappingPaths.isOptional(mapping, NAMESPACE_TOKEN)) {
                    omitted << NAMESPACE_TOKEN
                }
                else {
                    // A namespace the controller does not have does not reach it.
                    return
                }
            }
            boolean takesId = takesId(controller, actionName)
            if (expandsAction) {
                substitutions[ACTION_TOKEN] = actionName
                if (!takesId) {
                    omitted << ID_TOKEN
                }
            }

            List<String> described = UrlMappingPaths.paths(mapping, substitutions, omitted)
            for (PathItem.HttpMethod method : httpMethods(expandsAction ? null : mapping.httpMethod, controller, actionName)) {
                String operationId = operationId(controller, controllerName, actionName, method)
                if (expandsAction) {
                    operationId += '_byAction'
                }
                // Only the form that addresses a resource is described where the action takes one;
                // the shorter forms an optional identifier allows do not reach it.
                for (String path : (expandsAction && takesId ? described.take(1) : described)) {
                    addOperation(mapping, path, method, controller, controller.clazz, controllerName, actionName, operationId)
                }
            }
        }

        /**
         * Whether an action addresses one resource, and so takes the identifier a mapping may leave
         * optional: the actions of a resource controller that address one, or an action declaring an
         * {@code id} parameter.
         */
        private boolean takesId(GrailsControllerClass controller, String actionName) {
            if (isResourceController(controller)) {
                return RestfulControllerActions.takesId(actionName)
            }
            ReflectedMethod action = ActionAnnotations.actionMethod(controller.clazz, actionName)
            action != null && action.parameters.any { ReflectedParameter parameter -> parameter.name == ID_TOKEN }
        }

        /**
         * A controller a mapping reaching controllers by name describes: a RestfulController, or one
         * declaring the formats it responds in, as a REST controller does, rather than one rendering
         * views for a browser.
         */
        private boolean isRestController(GrailsControllerClass controller) {
            RestfulController.isAssignableFrom(controller.clazz) || controller.getPropertyValue(RESPONSE_FORMATS) != null
        }

        /**
         * Whether a controller serves a resource the way a RestfulController does: it is one, or its
         * save and update actions bind the same domain class, as the controllers the rest-api
         * profile generates do.
         */
        private boolean isResourceController(GrailsControllerClass controller) {
            controller != null && (RestfulController.isAssignableFrom(controller.clazz) || boundResource(controller) != null)
        }

        private Class<?> boundResource(GrailsControllerClass controller) {
            if (!boundResources.containsKey(controller.clazz)) {
                Class<?> saved = ActionAnnotations.commandObjectType(controller.clazz, 'save')
                Class<?> updated = ActionAnnotations.commandObjectType(controller.clazz, 'update')
                boundResources[controller.clazz] = saved != null && saved == updated && GrailsModelConverter.entityFor(saved) != null
                        ? saved : null
            }
            boundResources[controller.clazz]
        }

        private boolean isDescribed(GrailsControllerClass controller, Class<?> controllerType, String actionName) {
            if (ActionAnnotations.isHidden(controllerType)
                    || (actionName && ActionAnnotations.isHidden(controllerType, actionName))) {
                return false
            }
            boolean restful = controllerType != null && RestfulController.isAssignableFrom(controllerType)
            if (isResourceController(controller) && !settings.includeFormActions && RestfulControllerActions.isFormAction(actionName)) {
                return false
            }
            if (restful && RestfulControllerActions.refusedWhenReadOnly(controllerType, actionName)
                    && isReadOnly(controller)) {
                return false
            }
            !settings.annotatedOnly || ActionAnnotations.isAnnotated(controllerType, actionName)
        }

        /**
         * Whether a RestfulController was constructed read only, which is decided by its
         * constructor rather than declared on the class, so is read from the controller itself.
         */
        private boolean isReadOnly(GrailsControllerClass controller) {
            Object instance = controller != null ? controllerInstance(controller) : null
            instance instanceof RestfulController && ((RestfulController) instance).readOnly
        }

        private void addOperation(UrlMapping mapping, String path, PathItem.HttpMethod method,
                                  GrailsControllerClass controller, Class<?> controllerType, String controllerName,
                                  String actionName, String operationId) {
            ReflectedMethod action = ActionAnnotations.actionMethod(controllerType, actionName)
            if (!selection.selects(path, controllerType) || !selection.selectsAction(action)) {
                return
            }
            PathItem pathItem = paths.get(path) ?: new PathItem()
            if (pathItem.readOperationsMap().containsKey(method)) {
                return
            }
            List<String> produces = responseMediaTypes(controller, actionName).keySet().toList()
            List<String> consumes = bindsBody(method, controller, controllerType, actionName)
                    ? bodyMediaTypes(controller, controllerType, actionName)
                    : Collections.<String> emptyList()
            String version = versionOf(mapping)
            List<String> headers = version != null
                    ? ["${HttpHeaders.ACCEPT_VERSION}=${version}".toString()]
                    : Collections.<String> emptyList()
            if (!selection.selectsConditions(produces, consumes, headers)) {
                return
            }

            Operation operation = buildOperation(mapping, path, method, controller, controllerType, controllerName,
                    actionName, operationId)
            if (version != null) {
                addVersionParameter(operation, version, !isLatestVersion(mapping, version))
            }
            ActionAnnotations.apply(operation, controllerType, actionName, components, openapi31)
            operation = selection.customize(operation, components,
                    controller != null ? controllerInstance(controller) : null, action)
            if (operation == null) {
                return
            }
            pathItem.operation(method, operation)
            paths.addPathItem(path, pathItem)
        }

        private Operation buildOperation(UrlMapping mapping, String path, PathItem.HttpMethod method,
                                         GrailsControllerClass controller, Class<?> controllerType,
                                         String controllerName, String actionName, String operationId) {
            boolean restful = isResourceController(controller)
            Class<?> resourceType = restful ? resourceType(controller) : null
            List<String> pathNames = UrlMappingPaths.templateVariables(path)

            Operation operation = new Operation()
            List<TagAnnotation> tags = ActionAnnotations.declaredTags(controllerType)
            operation.setTags(tags ? tags*.name().toList() : [controllerName])
            operation.setOperationId(operationId)

            // Every variable the path declares needs a parameter, and nothing else does.
            for (String name : pathNames) {
                operation.addParametersItem(new Parameter()
                        .name(name)
                        .in('path')
                        .required(true)
                        .schema(pathParameterSchema(mapping, name, controllerType, actionName, resourceType)))
            }
            if (restful && actionName && RestfulControllerActions.paginates(actionName)) {
                addPagingParameters(operation)
            }
            addRequestParameters(operation, controllerType, actionName, pathNames)
            if (!(method.name() in BODY_METHODS)) {
                addCommandParameters(operation, controllerType, actionName, pathNames)
            }

            Map<String, Boolean> mediaTypes = responseMediaTypes(controller, actionName)
            if (restful && actionName) {
                boolean locates = controllerType != null && RestfulController.isAssignableFrom(controllerType)
                operation.setResponses(restfulResponses(resourceType, actionName, !pathNames.isEmpty(), mediaTypes, locates))
            }
            else {
                ApiResponses responses = new ApiResponses()
                        .addApiResponse(DEFAULT_RESPONSE_CODE, new ApiResponse().description('Success'))
                if (pathNames) {
                    responses.addApiResponse(NOT_FOUND_RESPONSE_CODE, new ApiResponse().description('Not Found'))
                }
                operation.setResponses(responses)
            }

            if (method.name() in BODY_METHODS && !ActionAnnotations.declaresRequestBody(controllerType, actionName)) {
                Schema<?> body = requestBodySchema(controllerType, actionName, resourceType, method)
                if (body != null) {
                    Map<String, Boolean> bodyTypes = bodyMediaTypes(controller, controllerType, actionName)
                            .collectEntries { String mediaType -> [(mediaType): true] } as Map<String, Boolean>
                    operation.setRequestBody(new RequestBody().content(content(body, bodyTypes)))
                }
            }
            operation
        }

        /**
         * The version a mapping is declared for, which Grails matches on the {@code Accept-Version}
         * header, or {@code null} for a mapping that answers any version.
         */
        private String versionOf(UrlMapping mapping) {
            String version = mapping?.version
            version && version != UrlMapping.ANY_VERSION ? version : null
        }

        /**
         * Whether a version is the one Grails answers a request asking for none with: the highest
         * version mapped for the same pattern and method.
         */
        private boolean isLatestVersion(UrlMapping mapping, String version) {
            if (latestVersions == null) {
                latestVersions = [:]
                for (UrlMapping candidate : urlMappingsHolder.urlMappings) {
                    String candidateVersion = versionOf(candidate)
                    if (candidateVersion == null) {
                        continue
                    }
                    String route = routeOf(candidate)
                    String latest = latestVersions[route]
                    if (latest == null || VERSION_COMPARATOR.compare(candidateVersion, latest) > 0) {
                        latestVersions[route] = candidateVersion
                    }
                }
            }
            latestVersions[routeOf(mapping)] == version
        }

        private String routeOf(UrlMapping mapping) {
            "${mapping.httpMethod ?: UrlMapping.ANY_HTTP_METHOD} ${mapping.urlData?.urlPattern}".toString()
        }

        private void addVersionParameter(Operation operation, String version, boolean required) {
            StringSchema schema = new StringSchema()
            schema.setEnum([version])
            operation.addParametersItem(new Parameter()
                    .name(HttpHeaders.ACCEPT_VERSION)
                    .in('header')
                    .required(required)
                    .description(required
                            ? "Asks for version ${version} of the API".toString()
                            : "Asks for version ${version} of the API, the version answered where none is asked for".toString())
                    .schema(schema))
        }

        /**
         * Whether an operation binds a body: one it declares, the command object its action takes,
         * or the resource a RestfulController serves.
         */
        private boolean bindsBody(PathItem.HttpMethod method, GrailsControllerClass controller,
                                  Class<?> controllerType, String actionName) {
            if (!(method.name() in BODY_METHODS)) {
                return false
            }
            if (ActionAnnotations.declaresRequestBody(controllerType, actionName)
                    || ActionAnnotations.commandObjectType(controllerType, actionName) != null) {
                return true
            }
            isResourceController(controller) && resourceType(controller) != null
        }

        /**
         * The type a RestfulController serves: the type argument it declares, or the resource it
         * was constructed with.
         */
        private Class<?> resourceType(GrailsControllerClass controller) {
            if (!RestfulController.isAssignableFrom(controller.clazz)) {
                return boundResource(controller)
            }
            Class<?> declared = GenericTypeResolver.resolveTypeArgument(controller.clazz, RestfulController)
            if (declared != null && declared != Object) {
                return declared
            }
            Object instance = controllerInstance(controller)
            instance instanceof RestfulController ? ((RestfulController) instance).resource : null
        }

        /**
         * The controller the application context serves requests with, if it holds one.
         */
        private Object controllerInstance(GrailsControllerClass controller) {
            if (!controllerInstances.containsKey(controller.clazz)) {
                controllerInstances[controller.clazz] = lookUpController(controller)
            }
            controllerInstances[controller.clazz]
        }

        /**
         * Grails registers a controller under its class name, which a subclass controller does not
         * share, where a lookup by type would find both.
         */
        private Object lookUpController(GrailsControllerClass controller) {
            ApplicationContext context = grailsApplication?.mainContext
            if (context == null) {
                return null
            }
            try {
                return context.containsBean(controller.fullName)
                        ? context.getBean(controller.fullName)
                        : context.getBean(controller.clazz)
            }
            catch (RuntimeException ignored) {
                return null
            }
        }

        /**
         * A path variable has the type of the identifier it names - the identifier of the resource
         * an operation addresses, or that of another resource a nested mapping names it by, such as
         * {@code bookId} - or else of the action parameter bound from it, and the pattern and values
         * the mapping constrains it to.
         */
        private Schema<?> pathParameterSchema(UrlMapping mapping, String name, Class<?> controllerType,
                                              String actionName, Class<?> resourceType) {
            Schema<?> schema = identifierSchema(name, resourceType)
            if (schema == null) {
                ReflectedParameter declared = ActionAnnotations.requestParameters(controllerType, actionName)
                        .find { ReflectedParameter it -> ActionAnnotations.requestParameterName(it) == name }
                schema = declared != null ? PrimitiveType.createProperty(declared.type) : null
            }
            schema = schema ?: new StringSchema()
            UrlMappingPaths.constrain(schema, mapping, name)
            schema
        }

        private Schema<?> identifierSchema(String name, Class<?> resourceType) {
            PersistentEntity entity = null
            if (name == ID_TOKEN) {
                entity = GrailsModelConverter.entityFor(resourceType)
            }
            else if (name.endsWith(ID_SUFFIX) && name.length() > ID_SUFFIX.length()) {
                entity = entityNamed(name.substring(0, name.length() - ID_SUFFIX.length()))
            }
            entity != null ? GrailsModelConverter.identifierSchema(entity) : null
        }

        private PersistentEntity entityNamed(String propertyName) {
            for (MappingContext context : mappingContexts) {
                PersistentEntity entity = context.persistentEntities.find { PersistentEntity it ->
                    it.decapitalizedName == propertyName
                }
                if (entity != null) {
                    return entity
                }
            }
            null
        }

        /**
         * A command object an action binds on a request without a body is bound from the request
         * parameters, so each property it binds is a query parameter.
         */
        private void addCommandParameters(Operation operation, Class<?> controllerType, String actionName,
                                          List<String> pathNames) {
            Class<?> commandType = ActionAnnotations.commandObjectType(controllerType, actionName)
            Schema<?> command = commandType != null ? inlineSchema(commandType) : null
            ((Map<String, Schema>) command?.properties)?.each { String name, Schema property ->
                if (name in pathNames || property.readOnly || !isParameterValue(property)) {
                    return
                }
                Parameter parameter = queryParameter(name, property.description, property)
                if (name in (command.required ?: [])) {
                    parameter.setRequired(true)
                }
                addParameterIfAbsent(operation, parameter)
            }
        }

        private Schema<?> inlineSchema(Class<?> type) {
            ResolvedSchema resolved = null
            describe("type [${type.name}]".toString()) {
                resolved = ModelConverters.getInstance(openapi31)
                        .resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(false))
            }
            resolved?.schema
        }

        /**
         * A value a request parameter can carry: not an object, nor a list of objects.
         */
        private boolean isParameterValue(Schema<?> schema) {
            if (schema.$ref || typeOf(schema) == 'object') {
                return false
            }
            typeOf(schema) != 'array' || (schema.items != null && !schema.items.$ref && typeOf(schema.items) != 'object')
        }

        private String typeOf(Schema<?> schema) {
            schema.type ?: schema.types?.find { String type -> type != 'null' }
        }

        /**
         * The parameters of a simple type an action declares are bound from the request by name.
         */
        private void addRequestParameters(Operation operation, Class<?> controllerType, String actionName,
                                          List<String> pathNames) {
            for (ReflectedParameter reflected : ActionAnnotations.requestParameters(controllerType, actionName)) {
                String name = ActionAnnotations.requestParameterName(reflected)
                if (name in pathNames) {
                    continue
                }
                Schema<?> schema = PrimitiveType.createProperty(reflected.type) ?: new StringSchema()
                addParameterIfAbsent(operation, queryParameter(name, null, schema))
            }
        }

        /**
         * The paging and sorting a listing accepts. RestfulController passes the request
         * parameters to GORM, so a listing answers to them whether or not a mapping mentions them.
         */
        private void addPagingParameters(Operation operation) {
            IntegerSchema max = new IntegerSchema()
            max.setMinimum(BigDecimal.ZERO)
            max.setMaximum(BigDecimal.valueOf(RestfulControllerActions.MAX_RESULTS))
            max.setDefault(RestfulControllerActions.DEFAULT_MAX)
            addParameterIfAbsent(operation, queryParameter('max',
                    "The most results to return, at most ${RestfulControllerActions.MAX_RESULTS}".toString(), max))

            IntegerSchema offset = new IntegerSchema()
            offset.setMinimum(BigDecimal.ZERO)
            addParameterIfAbsent(operation, queryParameter('offset', 'The result to start from', offset))
            addParameterIfAbsent(operation, queryParameter('sort', 'The property to sort by', new StringSchema()))

            StringSchema order = new StringSchema()
            order.setEnum(['asc', 'desc'])
            addParameterIfAbsent(operation, queryParameter('order', 'The direction to sort in', order))
        }

        private void addParameterIfAbsent(Operation operation, Parameter parameter) {
            if (!operation.parameters?.any { Parameter existing -> existing.name == parameter.name }) {
                operation.addParametersItem(parameter)
            }
        }

        private Parameter queryParameter(String name, String description, Schema<?> schema) {
            Parameter parameter = new Parameter()
            parameter.setName(name)
            parameter.setIn('query')
            parameter.setDescription(description)
            parameter.setSchema(schema)
            parameter
        }

        /**
         * The responses a RestfulController action gives: save answers CREATED, delete answers
         * NO_CONTENT with no body, an action addressed by an identifier can miss, and an action
         * that validates what it binds can answer with the validation errors.
         */
        private ApiResponses restfulResponses(Class<?> resourceType, String actionName, boolean takesId,
                                              Map<String, Boolean> mediaTypes, boolean locates) {
            ApiResponses responses = new ApiResponses()

            ApiResponse success = new ApiResponse().description('Success')
            if (RestfulControllerActions.hasResponseBody(actionName)) {
                Schema<?> resource = reference(resourceType)
                if (resource != null) {
                    Schema<?> schema = RestfulControllerActions.isCollection(actionName)
                            ? new ArraySchema().items(resource)
                            : resource
                    success.setContent(content(schema, mediaTypes))
                }
            }
            if (locates && RestfulControllerActions.locates(actionName)) {
                // RestfulController answers a save with where the created resource is.
                success.addHeaderObject(LOCATION_HEADER, new Header()
                        .description('The URL of the created resource')
                        .schema(new StringSchema().format('uri')))
            }
            responses.addApiResponse(RestfulControllerActions.successCode(actionName), success)

            if (takesId) {
                responses.addApiResponse(NOT_FOUND_RESPONSE_CODE, new ApiResponse().description('Not Found'))
            }
            if (RestfulControllerActions.validates(actionName)) {
                responses.addApiResponse(UNPROCESSABLE_RESPONSE_CODE, new ApiResponse()
                        .description('Validation failed')
                        .content(content(validationErrorsReference(), mediaTypes)))
            }
            responses
        }

        /**
         * The media types an action responds in: those of the formats its controller declares in
         * {@code responseFormats}, for the action or for every action, or JSON where it declares
         * none. Each says whether it carries the shape the document describes, which a data format,
         * JSON or XML, does and a view, a form or a HAL document does not.
         */
        private Map<String, Boolean> responseMediaTypes(GrailsControllerClass controller, String actionName) {
            Object declared = controller?.getPropertyValue(RESPONSE_FORMATS)
            Object formats = declared instanceof Map ? ((Map) declared).get(actionName) : declared
            Map<String, Boolean> mediaTypes = [:]
            if (formats instanceof Collection) {
                for (Object format : (Collection) formats) {
                    String mediaType = format != null ? formatMediaTypes()[format.toString()] : null
                    if (mediaType != null && !mediaTypes.containsKey(mediaType)) {
                        mediaTypes[mediaType] = format.toString() in DATA_FORMATS
                    }
                }
            }
            mediaTypes ?: [(DEFAULT_MEDIA_TYPE): true] as Map<String, Boolean>
        }

        /**
         * The media types an action binds a body from: {@code multipart/form-data} where what it
         * binds has a file, and otherwise those of the data formats it responds in, or JSON.
         */
        private List<String> bodyMediaTypes(GrailsControllerClass controller, Class<?> controllerType, String actionName) {
            Class<?> bound = ActionAnnotations.commandObjectType(controllerType, actionName)
                    ?: (isResourceController(controller) ? resourceType(controller) : null)
            if (GrailsModelConverter.hasFileProperty(bound)) {
                return [MULTIPART_MEDIA_TYPE]
            }
            List<String> data = responseMediaTypes(controller, actionName).findAll { String type, Boolean shaped -> shaped }
                    .keySet().toList()
            data ?: [DEFAULT_MEDIA_TYPE]
        }

        /**
         * The media type Grails maps each format to: the first configured for it.
         */
        private Map<String, String> formatMediaTypes() {
            if (formatMediaTypes == null) {
                formatMediaTypes = [:]
                for (MimeType mimeType : configuredMimeTypes()) {
                    if (mimeType.extension && !formatMediaTypes.containsKey(mimeType.extension)) {
                        formatMediaTypes[mimeType.extension] = mimeType.name
                    }
                }
            }
            formatMediaTypes
        }

        private MimeType[] configuredMimeTypes() {
            try {
                ApplicationContext context = grailsApplication?.mainContext
                if (context?.containsBean(MimeType.BEAN_NAME)) {
                    return context.getBean(MimeType.BEAN_NAME, MimeType[])
                }
            }
            catch (RuntimeException ignored) {
                // An application without the configured types describes with the defaults.
            }
            MimeType.createDefaults()
        }

        private Content content(Schema<?> schema, Map<String, Boolean> mediaTypes) {
            Content content = new Content()
            mediaTypes.each { String mediaType, Boolean shaped ->
                content.addMediaType(mediaType, shaped ? new MediaType().schema(schema) : new MediaType())
            }
            content
        }

        /**
         * The body an action binds: the command object it takes, in preference to the resource
         * its controller serves. A patch binds only what it is sent, so nothing is required of it.
         */
        private Schema<?> requestBodySchema(Class<?> controllerType, String actionName, Class<?> resourceType,
                                            PathItem.HttpMethod method) {
            Class<?> commandType = ActionAnnotations.commandObjectType(controllerType, actionName)
            Schema<?> body = reference(commandType ?: resourceType)
            method == PathItem.HttpMethod.PATCH ? patchReference(body) : body
        }

        private Schema<?> patchReference(Schema<?> reference) {
            String name = schemaName(reference)
            Schema<?> full = name ? components.schemas?.get(name) : null
            if (full == null || !full.required) {
                return reference
            }
            String patchName = patchSchemas.computeIfAbsent(name) { String base -> schemaNames.reserve(base + PATCH_SUFFIX) }
            if (!components.schemas.containsKey(patchName)) {
                Schema<?> patch = new ObjectSchema()
                patch.setProperties(new LinkedHashMap<String, Schema>(full.properties ?: [:]))
                patch.setDescription(full.description)
                components.addSchemas(patchName, patch)
                addedSchemas << patchName
            }
            new Schema<>().$ref(REFERENCE_PREFIX + patchName)
        }

        /**
         * Resolves a type into the components through swagger-core and refers to it.
         */
        private Schema<?> reference(Class<?> type) {
            if (type == null || type == Object) {
                return null
            }
            ResolvedSchema resolved = null
            describe("type [${type.name}]".toString()) {
                resolved = ModelConverters.getInstance(openapi31)
                        .resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(true))
            }
            if (resolved?.schema == null) {
                return null
            }
            resolved.referencedSchemas?.each { String name, Schema schema ->
                if (!components.schemas?.containsKey(name)) {
                    components.addSchemas(name, schema)
                    addedSchemas << name
                }
            }
            resolved.schema.$ref ? new Schema<>().$ref(resolved.schema.$ref) : resolved.schema
        }

        /**
         * The names to move the schemas this contribution added to, now that every class described
         * is known: a class holding a name another class also claims moves to its qualified name,
         * and a patch schema follows the schema it is a patch of. A schema the base document or
         * springdoc supplied keeps its name.
         */
        private Map<String, String> schemaRenames() {
            Map<String, String> renames = schemaNames.renames().findAll { String from, String to -> from in addedSchemas }
            patchSchemas.each { String base, String patch ->
                String moved = renames[base]
                if (moved != null && patch in addedSchemas) {
                    renames[patch] = schemaNames.move(base + PATCH_SUFFIX, moved + PATCH_SUFFIX)
                }
            }
            renames
        }

        /**
         * The errors Grails renders for a request that fails validation.
         */
        private Schema<?> validationErrorsReference() {
            if (!components.schemas?.containsKey(VALIDATION_ERRORS_SCHEMA)) {
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
                components.addSchemas(VALIDATION_ERRORS_SCHEMA, errors)
            }
            new Schema<>().$ref(REFERENCE_PREFIX + VALIDATION_ERRORS_SCHEMA)
        }

        /**
         * An identifier must be unique across the document. The same action reached through more
         * than one mapping derives the same identifier, so the later one is qualified by the path it
         * is reached at, which depends on the path rather than on the order the mappings were read.
         */
        private void disambiguateOperationIds() {
            Set<String> used = [] as Set
            paths.each { String path, PathItem item ->
                item.readOperationsMap().each { PathItem.HttpMethod method, Operation operation ->
                    String id = operation.operationId
                    if (id == null || used.add(id)) {
                        return
                    }
                    String qualified = "${id}_${pathDiscriminator(path)}".toString()
                    int suffix = 2
                    while (!used.add(qualified)) {
                        qualified = "${id}_${pathDiscriminator(path)}_${suffix++}".toString()
                    }
                    operation.setOperationId(qualified)
                }
            }
        }

        /**
         * Describes the tags the documented controllers declare, so a grouping carries its
         * description rather than only its name.
         */
        private void registerTags() {
            Set<String> used = paths.values().collectMany { PathItem item ->
                item.readOperations().collectMany { Operation operation -> operation.tags ?: [] }
            } as Set<String>
            for (GrailsControllerClass controller : controllersByKey.values()) {
                describe("the tags of [${controller.fullName}]".toString()) {
                    registerTags(controller, used)
                }
            }
        }

        private void registerTags(GrailsControllerClass controller, Set<String> used) {
            if (ActionAnnotations.isHidden(controller.clazz)) {
                return
            }
            for (TagAnnotation declared : ActionAnnotations.declaredTags(controller.clazz)) {
                if (!(declared.name() in used) || openApi.tags?.any { Tag it -> it.name == declared.name() }) {
                    continue
                }
                Tag tag = new Tag().name(declared.name())
                if (declared.description()) {
                    tag.setDescription(declared.description())
                }
                openApi.addTagsItem(tag)
            }
        }

        /**
         * Removes a reference this contribution made to a schema that is not in the document. A
         * class that could not be described leaves the operations that referred to it pointing at
         * nothing, and a reference that does not resolve is worse for a code generator than an
         * operation described without a shape.
         */
        private void dropUnresolvedReferences() {
            Set<String> defined = components.schemas?.keySet() ?: [] as Set<String>
            paths.each { String path, PathItem item ->
                item.readOperations().each { Operation operation ->
                    operation.responses?.values()?.each { ApiResponse response ->
                        if (dropUnresolved(response.content, defined, path) && response.content.values().every { it.schema == null }) {
                            response.setContent(null)
                        }
                    }
                    if (dropUnresolved(operation.requestBody?.content, defined, path)) {
                        operation.setRequestBody(null)
                    }
                }
            }
        }

        private boolean dropUnresolved(Content content, Set<String> defined, String path) {
            if (content == null) {
                return false
            }
            boolean emptied = false
            content.each { String mediaTypeName, MediaType mediaType ->
                if (!resolves(mediaType.schema, defined)) {
                    LOG.warn('Describing {} without a schema: the type it referred to could not be described', path)
                    mediaType.setSchema(null)
                    emptied = true
                }
            }
            emptied
        }

        private boolean resolves(Schema<?> schema, Set<String> defined) {
            if (schema == null) {
                return true
            }
            String ref = schema.$ref ?: schema.items?.$ref
            ref == null || !ref.startsWith(REFERENCE_PREFIX) || defined.contains(ref.substring(REFERENCE_PREFIX.length()))
        }
    }

    private static String schemaName(Schema<?> reference) {
        String ref = reference?.$ref
        ref?.startsWith(REFERENCE_PREFIX) ? ref.substring(REFERENCE_PREFIX.length()) : null
    }

    private static String controllerKey(String namespace, String name) {
        "${namespace ?: ''}:${name}".toString()
    }

    /**
     * Describes one part of the document, leaving the rest intact if it cannot be described. An
     * application should not lose its whole API description because one class cannot be
     * introspected.
     */
    private static void describe(String what, Closure<?> work) {
        try {
            work.call()
        }
        catch (Exception | LinkageError e) {
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

    /**
     * Grails exposes controller, action and namespace names as {@code Object} because a mapping
     * may define them dynamically. Only statically declared names can be documented.
     */
    private static String asStaticName(Object name) {
        name instanceof CharSequence && name ? name.toString() : null
    }

    private static String pathDiscriminator(String path) {
        String cleaned = path.replaceAll(/[^A-Za-z0-9]+/, '_')
        cleaned.startsWith('_') ? cleaned.substring(1) : cleaned
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
            LOG.warn('Skipping a URL mapping declared for the unsupported HTTP method [{}]', httpMethod)
            return null
        }
    }

    /**
     * Controllers of the same name in different namespaces are distinguished by the namespace.
     */
    private static String operationId(GrailsControllerClass controller, String controllerName, String actionName,
                                      PathItem.HttpMethod method) {
        String prefix = controller?.namespace ? "${controller.namespace}_${controllerName}".toString() : controllerName
        String verb = method.name().toLowerCase(Locale.ENGLISH)
        actionName ? "${prefix}_${actionName}_${verb}".toString() : "${prefix}_${verb}".toString()
    }
}

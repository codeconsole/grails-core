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
package org.grails.web.mapping

import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import groovy.util.logging.Slf4j

import jakarta.annotation.PostConstruct

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ResolvableType
import org.springframework.http.HttpMethod

import grails.config.Settings
import grails.plugins.GrailsPluginManager
import grails.plugins.PluginManagerAware
import grails.util.Environment
import grails.util.GrailsClassUtils
import grails.util.GrailsNameUtils
import grails.util.GrailsWebUtil
import grails.web.CamelCaseUrlConverter
import grails.web.UrlConverter
import grails.web.mapping.LinkGenerator
import grails.web.mapping.UrlCreator
import grails.web.mapping.UrlMapping
import grails.web.mapping.UrlMappingsHolder
import grails.core.GrailsApplication
import grails.core.GrailsClass
import grails.core.GrailsControllerClass
import org.grails.core.artefact.ControllerArtefactHandler
import org.grails.core.artefact.DomainClassArtefactHandler
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.web.servlet.mvc.DefaultRequestStateLookupStrategy
import org.grails.web.servlet.mvc.GrailsRequestStateLookupStrategy
import org.grails.web.servlet.mvc.GrailsWebRequest
import org.grails.web.util.WebUtils

/**
 * A link generating service for applications to use when generating links.
 *
 * @author Graeme Rocher
 * @since 2.0
 */
@CompileStatic
@Slf4j
class DefaultLinkGenerator implements LinkGenerator, PluginManagerAware {

    private static final Pattern absoluteUrlPattern = Pattern.compile('^[A-Za-z][A-Za-z0-9+\\-.]*:.*$')

    String configuredServerBaseURL
    String contextPath
    String resourcePath

    GrailsRequestStateLookupStrategy requestStateLookupStrategy = new DefaultRequestStateLookupStrategy()

    GrailsPluginManager pluginManager

    @Autowired
    @Qualifier('grailsUrlMappingsHolder')
    UrlMappingsHolder urlMappingsHolder

    @Autowired(required=false)
    @Qualifier('grailsDomainClassMappingContext')
    MappingContext mappingContext

    @Autowired(required = false)
    UrlConverter grailsUrlConverter = new CamelCaseUrlConverter()

    @Autowired(required = false)
    GrailsApplication grailsApplication

    private volatile ControllerIndex<String> controllerNamespacesByName
    private volatile ControllerIndex<ControllerRef> controllersByDomainClass
    private final Set<String> ambiguousControllersReported = ConcurrentHashMap.newKeySet()

    @Value('${grails.resources.pattern:/static/**}')
    String resourcePattern = Settings.DEFAULT_RESOURCE_PATTERN

    DefaultLinkGenerator(String serverBaseURL, String contextPath) {
        configuredServerBaseURL = serverBaseURL
        this.contextPath = contextPath
    }

    DefaultLinkGenerator(String serverBaseURL) {
        configuredServerBaseURL = serverBaseURL
    }

    @PostConstruct
    void initializeResourcePath() {
        if (resourcePattern?.endsWith('/**')) {
            resourcePath = resourcePattern.substring(0, resourcePattern.length() - 3)
        }
    }

    /**
     * {@inheritDoc}
     */
    String link(Map attrs, String encoding = 'UTF-8') {
        def writer = new StringBuilder()
        // prefer URI attribute
        boolean includeContext = GrailsClassUtils.getBooleanFromMap(ATTRIBUTE_INCLUDE_CONTEXT, attrs, true)

        if (attrs.get(ATTRIBUTE_URI) != null) {
            def uri = attrs.get(ATTRIBUTE_URI).toString()
            if (!isUriAbsolute(uri)) {
                final base = handleAbsolute(attrs)
                if (base != null) {
                    writer.append(base)
                }
                else if (includeContext) {

                    def cp = attrs.get(ATTRIBUTE_CONTEXT_PATH)
                    if (cp == null) cp = getContextPath()
                    if (cp != null)
                        writer.append(cp)
                }
            }
            writer.append(uri)

            def params = attrs.get(ATTRIBUTE_PARAMS)

            if (params instanceof Map) {
                def charset = GrailsWebUtil.DEFAULT_ENCODING
                def paramString = params.collect { Map.Entry entry ->
                    def encodedKey = URLEncoder.encode(entry.key as String, charset)
                    def encodedValue = URLEncoder.encode(entry.value as String, charset)
                    "$encodedKey=$encodedValue"
                }.join('&')
                writer.append(uri.indexOf('?') >= 0 ? '&' : '?')
                      .append(paramString)
            }
        }
        else if (attrs.get(ATTRIBUTE_RELATIVE_URI) != null) {
            String relativeUri = attrs.get(ATTRIBUTE_RELATIVE_URI)
            String forwardUri = WebUtils.getForwardURI(requestStateLookupStrategy.webRequest.request)
            int index = forwardUri.lastIndexOf('/')
            if (index != -1) {
                writer.append(forwardUri.substring(0, index + 1))
            }
            writer.append(relativeUri)
        }
        else {
            // prefer a URL attribute
            Map urlAttrs = attrs
            final urlAttribute = attrs.get(ATTRIBUTE_URL)
            if (urlAttribute instanceof Map) {
                urlAttrs = (Map) urlAttribute
            }
            if (!urlAttribute || urlAttribute instanceof Map) {
                final controllerAttribute = urlAttrs.get(ATTRIBUTE_CONTROLLER)
                final resourceAttribute = urlAttrs.get(ATTRIBUTE_RESOURCE)
                String controller
                ResourceTarget resourceTarget = null
                String action = urlAttrs.get(ATTRIBUTE_ACTION)?.toString()
                def id = urlAttrs.get(ATTRIBUTE_ID)
                String httpMethod
                final methodAttribute = urlAttrs.get(ATTRIBUTE_METHOD)
                final paramsAttribute = urlAttrs.get(ATTRIBUTE_PARAMS)
                Map params = paramsAttribute instanceof Map ? (Map) paramsAttribute : [:]

                if (resourceAttribute) {
                    String resource
                    if (resourceAttribute instanceof CharSequence)
                        resource = resourceAttribute.toString()
                    else {
                        PersistentEntity persistentEntity = (mappingContext != null) ? mappingContext.getPersistentEntity(resourceAttribute.getClass().getName()) : null
                        boolean hasId = false
                        if (persistentEntity != null) {
                            resourceTarget = resolveResourceTarget(persistentEntity, attrs)
                            resource = resourceTarget.controller
                            hasId = true
                        } else if (DomainClassArtefactHandler.isDomainClass(resourceAttribute.getClass(), true)) {
                            resource = GrailsNameUtils.getPropertyName(resourceAttribute.getClass())
                            hasId = true
                        } else if (resourceAttribute instanceof Class) {
                            // A domain class rather than an instance, used where loading the instance would
                            // defeat the point, such as an uninitialised association rendered from its proxy.
                            PersistentEntity classEntity = (mappingContext != null) ?
                                    mappingContext.getPersistentEntity(((Class) resourceAttribute).name) : null
                            if (classEntity != null) {
                                resourceTarget = resolveResourceTarget(classEntity, attrs)
                                resource = resourceTarget.controller
                            } else {
                                resource = GrailsNameUtils.getPropertyName(resourceAttribute)
                            }
                        } else {
                            resource = resourceAttribute.toString()
                        }
                        if (!id && hasId) {
                            id = getResourceId(resourceAttribute)
                        }
                    }
                    List tokens = resource.contains('/') ? resource.tokenize('/') : [resource]
                    controller = controllerAttribute ?: tokens[-1]
                    if (tokens.size() > 1) {
                        for (t in tokens[0..-2]) {
                            final key = "${t}Id".toString()
                            final attr = urlAttrs.remove(key)
                            // the params value might not be null
                            // only overwrite if urlAttrs actually had the key
                            if (attr) {
                                params[key] = attr
                            }
                        }
                    }
                    if (!methodAttribute && action) {
                        httpMethod =  REST_RESOURCE_ACTION_TO_HTTP_METHOD_MAP.get(action.toString())
                        if (!httpMethod) {
                            httpMethod = HttpMethod.GET.toString()
                        }
                    }
                    else if (methodAttribute && !action) {
                        def method = methodAttribute.toString().toUpperCase()
                        httpMethod = method
                        if (method == 'GET' && id) method = "${method}_ID".toString()
                        action = REST_RESOURCE_HTTP_METHOD_TO_ACTION_MAP[method]
                    }
                    else {
                        httpMethod = methodAttribute == null ? requestStateLookupStrategy.getHttpMethod() ?: UrlMapping.ANY_HTTP_METHOD : methodAttribute.toString()
                    }

                }
                else {
                    controller = controllerAttribute == null ? requestStateLookupStrategy.getControllerName() : controllerAttribute.toString()
                    httpMethod = methodAttribute == null ? requestStateLookupStrategy.getHttpMethod() ?: UrlMapping.ANY_HTTP_METHOD : methodAttribute.toString()
                }

                String convertedControllerName = grailsUrlConverter.toUrlElement(controller)

                boolean isDefaultAction = false
                if (controller && !action) {
                    action = requestStateLookupStrategy.getActionName(convertedControllerName)
                    isDefaultAction = true
                }
                String convertedActionName = action
                if (action) {
                    convertedActionName = grailsUrlConverter.toUrlElement(action)
                }

                String frag = urlAttrs.get(ATTRIBUTE_FRAGMENT)?.toString()

                def mappingName = urlAttrs.get(ATTRIBUTE_MAPPING)
                if (mappingName != null) {
                    params.mappingName = mappingName
                }
                def url
                if (id != null) {
                    params.put(ATTRIBUTE_ID, id)
                }
                def pluginName = attrs.get(UrlMapping.PLUGIN)?.toString()
                // A resource link resolved from the request context carries the namespace that chose it,
                // unless the caller named the controller itself.
                String namespace = resourceTarget != null && resourceTarget.pinsNamespace && controllerAttribute == null ?
                        resourceTarget.namespace :
                        resolveNamespace(controller, pluginName, attrs)
                UrlCreator mapping = urlMappingsHolder.getReverseMappingNoDefault(controller, action, namespace, pluginName, httpMethod, params)
                if (mapping == null && isDefaultAction) {
                    mapping = urlMappingsHolder.getReverseMappingNoDefault(controller, null, namespace, pluginName, httpMethod, params)
                }
                if (mapping == null) {
                    mapping = urlMappingsHolder.getReverseMapping(controller, action, namespace, pluginName, httpMethod, params)
                }

                boolean absolute = isAbsolute(attrs)

                if (!absolute) {
                    url = mapping.createRelativeURL(convertedControllerName, convertedActionName, namespace, pluginName, params, encoding, frag)
                    final contextPathAttribute = attrs.get(ATTRIBUTE_CONTEXT_PATH)
                    final cp = contextPathAttribute == null ? getContextPath() : contextPathAttribute
                    if (attrs.get(ATTRIBUTE_BASE) || cp == null) {
                        attrs.put(ATTRIBUTE_ABSOLUTE, true)
                        writer.append(handleAbsolute(attrs))
                    }
                    else if (includeContext) {
                        writer.append(cp)
                    }
                    writer.append(url)
                }
                else {
                    url = mapping.createRelativeURL(convertedControllerName, convertedActionName, namespace, pluginName, params, encoding, frag)
                    writer.append(handleAbsolute(attrs))
                    writer.append(url)
                }
            } else {
                writer.append(urlAttribute)
            }
        }
        return writer.toString()
    }

    /**
     * {@inheritDoc}
     */
    @Override
    String getDefaultNamespace(String controller, String pluginName) {
        if (controller == null) {
            return null
        }
        String currentControllerName = requestStateLookupStrategy.controllerName
        String currentNamespace = requestStateLookupStrategy.controllerNamespace
        // Preserve the historical behaviour of reusing the current request namespace when the link
        // targets the controller currently handling the request.
        if (controller == currentControllerName) {
            return currentNamespace
        }

        // A plugin-provided target is resolved within that plugin, so do not infer a namespace from
        // the application's own controllers, which could pick an unrelated same-named controller.
        if (pluginName != null) {
            return null
        }

        Set<ControllerRef> candidates = controllersNamed(controller)
        if (candidates.isEmpty()) {
            return null
        }
        ControllerRef nearest = nearestController(candidates, controller, currentNamespace, false)
        if (nearest == null) {
            reportAmbiguousNamespace(controller, candidates)
            return null
        }
        return nearest.namespace
    }

    /**
     * Warns, once per controller name, that a link named a controller defined in several namespaces
     * without saying which, from outside all of them, so no namespace could be inferred.
     */
    private void reportAmbiguousNamespace(String controller, Set<ControllerRef> candidates) {
        if (ambiguousControllersReported.add(controller)) {
            Set<String> namespaces = new TreeSet<>()
            for (ControllerRef candidate in candidates) {
                namespaces.add(candidate.namespace)
            }
            log.warn('A link to controller [{}] names no namespace, but the controller is defined in the namespaces {} and in neither the default namespace nor the namespace of the current request. No namespace was inferred; pass a namespace attribute to choose one.',
                    controller, namespaces)
        }
    }

    private Map<String, Set<String>> getControllerNamespacesByName() {
        GrailsApplication application = grailsApplication
        if (application == null) {
            return Collections.emptyMap()
        }
        // getArtefacts returns a cached array that is replaced with a new instance whenever the set of
        // controllers changes (late registration in tests, a development-mode reload, or a namespace
        // edit). Comparing the array identity rebuilds the index on any such change while staying O(1)
        // on the common path where nothing changed.
        GrailsClass[] controllers = application.getArtefacts(ControllerArtefactHandler.TYPE)
        ControllerIndex<String> index = controllerNamespacesByName
        if (index == null || !index.isFor(controllers)) {
            index = new ControllerIndex<String>(controllers, buildControllerNamespaceIndex(controllers))
            controllerNamespacesByName = index
        }
        return index.entries
    }

    private Map<String, Set<String>> buildControllerNamespaceIndex(GrailsClass[] controllers) {
        Map<String, Set<String>> index = new HashMap<>()
        for (GrailsClass gc in controllers) {
            GrailsControllerClass controllerClass = (GrailsControllerClass) gc
            String name = controllerClass.logicalPropertyName
            if (name == null) {
                continue
            }
            Set<String> namespaces = index.get(name)
            if (namespaces == null) {
                namespaces = new HashSet<>()
                index.put(name, namespaces)
            }
            namespaces.add(controllerClass.namespace)
        }
        return index
    }

    /**
     * Clears the cached controller-to-namespace and domain-class-to-controller indexes so they are
     * rebuilt on next use. Invoked when the set of controllers may have changed (for example during
     * development-mode reloads).
     */
    void resetControllerNamespaceCache() {
        controllerNamespacesByName = null
        controllersByDomainClass = null
        ambiguousControllersReported.clear()
    }

    /**
     * Resolves the controller a {@code resource} link for the given entity targets: the nearest controller
     * serving the domain class, as {@link #nearestController} chooses it. A controller serves a domain
     * class when it is named after it, or when it declares it as a generic type argument, as
     * {@code PeopleController extends RestfulController<Person>} does. The link targets the explicit
     * {@code namespace} when one is given, and otherwise the request's own namespace.
     *
     * <p>When no candidate is unambiguous, the link targets the controller name the candidates share, if
     * they share one, and otherwise the domain class name, as before; either way the namespace is then
     * inferred for that name.</p>
     *
     * @param entity the domain class being linked to
     * @param attrs the link attributes, which may carry an explicit {@code namespace}
     * @return the target controller, and its namespace when the scope chain found it
     */
    private ResourceTarget resolveResourceTarget(PersistentEntity entity, Map attrs) {
        String derivedName = entity.getDecapitalizedName()
        Set<ControllerRef> serving = servingControllers(entity, derivedName)
        if (serving.isEmpty()) {
            return new ResourceTarget(derivedName, null, false)
        }
        boolean explicitNamespace = attrs != null && attrs.containsKey(ATTRIBUTE_NAMESPACE)
        String targetNamespace = explicitNamespace ?
                resolveNamespace(derivedName, null, attrs) :
                requestStateLookupStrategy.controllerNamespace
        ControllerRef nearest = nearestController(serving, derivedName, targetNamespace, explicitNamespace)
        if (nearest != null) {
            // An explicit namespace is applied by the caller already; otherwise carry the one found.
            return new ResourceTarget(nearest.name, nearest.namespace, !explicitNamespace)
        }
        Set<String> names = new HashSet<>()
        for (ControllerRef ref in serving) {
            names.add(ref.name)
        }
        return new ResourceTarget(names.size() == 1 ? names.iterator().next() : derivedName, null, false)
    }

    /**
     * Chooses, among the controllers a link could target, the one nearest to where the link is rendered,
     * trying scopes from the most specific to the least, as code resolves a name:
     *
     * <ol>
     *   <li>the controller handling the current request, if it is a candidate in the targeted
     *       namespace</li>
     *   <li>the candidate in the targeted namespace</li>
     *   <li>the candidate in the default namespace</li>
     *   <li>the candidate in any namespace</li>
     * </ol>
     *
     * <p>A scope holding more than one candidate chooses the one with the conventional name, the
     * controller named after the domain class for a resource link, and is otherwise ambiguous, so the
     * next scope is tried rather than guessing. An explicit namespace confines the choice to that
     * namespace.</p>
     *
     * @param candidates the controllers the link could target
     * @param conventionalName the name that settles a tie within a scope
     * @param targetNamespace the namespace the link targets, explicitly or from the request
     * @param explicitNamespace whether the namespace was given explicitly
     * @return the chosen controller, or {@code null} when no scope settles on one
     */
    private ControllerRef nearestController(Set<ControllerRef> candidates, String conventionalName,
                                            String targetNamespace, boolean explicitNamespace) {
        String currentController = requestStateLookupStrategy.controllerName
        if (currentController != null) {
            ControllerRef current = new ControllerRef(currentController, requestStateLookupStrategy.controllerNamespace)
            if (current.namespace == targetNamespace && candidates.contains(current)) {
                return current
            }
        }
        ControllerRef chosen = chooseWithin(candidates, conventionalName, true, targetNamespace)
        if (chosen != null || explicitNamespace) {
            return chosen
        }
        if (targetNamespace != null) {
            chosen = chooseWithin(candidates, conventionalName, true, null)
            if (chosen != null) {
                return chosen
            }
        }
        return chooseWithin(candidates, conventionalName, false, null)
    }

    /**
     * @return the only candidate in the scope, or failing that the only one in it with the conventional
     *         name, or {@code null}; the scope is the given namespace, or every namespace when
     *         {@code inNamespace} is {@code false}
     */
    private static ControllerRef chooseWithin(Set<ControllerRef> candidates, String conventionalName,
                                              boolean inNamespace, String namespace) {
        ControllerRef only = null
        ControllerRef conventional = null
        int count = 0
        int conventionalCount = 0
        for (ControllerRef candidate in candidates) {
            if (inNamespace && candidate.namespace != namespace) {
                continue
            }
            count++
            only = candidate
            if (candidate.name == conventionalName) {
                conventionalCount++
                conventional = candidate
            }
        }
        if (count == 1) {
            return only
        }
        return conventionalCount == 1 ? conventional : null
    }

    private Set<ControllerRef> controllersNamed(String name) {
        Set<ControllerRef> named = new HashSet<>()
        Set<String> namespaces = getControllerNamespacesByName().get(name)
        if (namespaces != null) {
            for (String namespace in namespaces) {
                named.add(new ControllerRef(name, namespace))
            }
        }
        return named
    }

    private Set<ControllerRef> servingControllers(PersistentEntity entity, String derivedName) {
        Set<ControllerRef> serving = controllersNamed(derivedName)
        Set<ControllerRef> declaring = getControllersByDomainClass().get(entity.name)
        if (declaring != null) {
            serving.addAll(declaring)
        }
        return serving
    }

    private Map<String, Set<ControllerRef>> getControllersByDomainClass() {
        GrailsApplication application = grailsApplication
        if (application == null || mappingContext == null) {
            return Collections.emptyMap()
        }
        // Mirrors getControllerNamespacesByName(): getArtefacts returns a cached array replaced with a
        // new instance whenever the set of controllers changes, so comparing the array identity rebuilds
        // the index on any such change while staying O(1) on the common path.
        GrailsClass[] controllers = application.getArtefacts(ControllerArtefactHandler.TYPE)
        ControllerIndex<ControllerRef> index = controllersByDomainClass
        if (index == null || !index.isFor(controllers)) {
            index = new ControllerIndex<ControllerRef>(controllers, buildDomainClassControllerIndex(controllers, mappingContext))
            controllersByDomainClass = index
        }
        return index.entries
    }

    private Map<String, Set<ControllerRef>> buildDomainClassControllerIndex(GrailsClass[] controllers, MappingContext context) {
        Map<String, Set<ControllerRef>> index = new HashMap<>()
        for (GrailsClass gc in controllers) {
            GrailsControllerClass controllerClass = (GrailsControllerClass) gc
            String controllerName = controllerClass.logicalPropertyName
            if (controllerName == null) {
                continue
            }
            String domainClassName = domainClassNameFor(controllerClass.clazz, context)
            if (domainClassName == null) {
                continue
            }
            Set<ControllerRef> declaring = index.get(domainClassName)
            if (declaring == null) {
                declaring = new HashSet<>()
                index.put(domainClassName, declaring)
            }
            declaring.add(new ControllerRef(controllerName, controllerClass.namespace))
        }
        return index
    }

    /**
     * Walks a controller's supertypes looking for a generic type argument that the mapping context
     * recognises as a persistent entity. Superclasses and interfaces are both walked, so a domain class
     * declared by an intermediate base class or by a Groovy trait is still found, and matching on the
     * mapping context rather than on a known base type keeps this class free of any dependency on the
     * REST controller hierarchy.
     *
     * <p>A supertype that declares more than one persistent entity is ambiguous and is skipped rather
     * than guessed at, so a base class parameterised on both a parent and a child resource does not
     * index the controller under the wrong one.</p>
     */
    private String domainClassNameFor(Class<?> controllerClass, MappingContext context) {
        Deque<ResolvableType> queue = new ArrayDeque<>()
        Set<Class<?>> seen = new HashSet<>()
        queue.add(ResolvableType.forClass(controllerClass))
        while (!queue.isEmpty()) {
            ResolvableType type = queue.poll()
            Class<?> raw = type.resolve()
            if (raw == null || !seen.add(raw)) {
                continue
            }
            String found = singleEntityGeneric(type, context)
            if (found != null) {
                return found
            }
            queue.add(type.superType)
            queue.addAll(type.interfaces)
        }
        return null
    }

    /**
     * @return the name of the only persistent entity among the type's generic arguments, or
     *         {@code null} when there is none or more than one
     */
    private static String singleEntityGeneric(ResolvableType type, MappingContext context) {
        String found = null
        for (ResolvableType generic in type.generics) {
            Class<?> resolved = generic.resolve()
            if (resolved != null && context.getPersistentEntity(resolved.name) != null) {
                if (found != null) {
                    return null
                }
                found = resolved.name
            }
        }
        return found
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    protected String getResourceId(resourceAttribute) {
        try {
            // Three options for using indent():
            // 1. Check instanceof GormEntity, but that would require coupling web-common to grails-datastore-gorm
            // 2. GrailsMetaClassUtils.invokeMethodIfExists(o, "ident", new Object[0]); Slow?
            // 3. Just assuming resource is a GormEntity or has ident() implemented and catching an exception if it is not.
            def ident = resourceAttribute.ident()
            if (ident) {
                return ident.toString()
            }
        } catch (MissingMethodException | IllegalStateException ignored) {
            // An IllegalStateException occurs if GORM is not initialized.
            // A MissingMethodException if it is not a GormEntity
        }

        final id = resourceAttribute.id
        if (id) {
            return id.toString()
        }
        return null
    }

    protected boolean isAbsolute(Map attrs) {
        boolean absolute = false
        def o = attrs.get(ATTRIBUTE_ABSOLUTE)
        if (o instanceof Boolean) {
            absolute = o
        } else {
            if (o != null) {
                try {
                    def str = o.toString()
                    if (str) {
                        absolute = Boolean.parseBoolean(str)
                    }
                } catch (Exception e) {
                    log.debug('Unable to parse absolute link attribute', e)
                }
            }
        }
        return absolute
    }

    /**
     * {@inheritDoc }
     */
    String resource(Map attrs) {
        def absolutePath = handleAbsolute(attrs)

        final contextPathAttribute = attrs.contextPath?.toString()
        if (absolutePath == null) {
            final cp = contextPathAttribute == null ? getContextPath() : contextPathAttribute
            if (cp == null) {
                absolutePath = handleAbsolute(absolute: true)
            }
            else {
                absolutePath = cp
            }
        }

        StringBuilder url = new StringBuilder(absolutePath?.toString() ?: '')
        def dir = attrs.dir?.toString()
        if (attrs.plugin) {
            url.append(pluginManager?.getPluginPath(attrs.plugin?.toString()) ?: '')
        }
        else {
            if (contextPathAttribute == null) {
                def pluginContextPath = attrs.pluginContextPath?.toString()
                if (pluginContextPath != null && dir != pluginContextPath) {
                    url << pluginContextPath
                }
            }
        }

        def slash = '/'
        if (resourcePath != null) {
            url.append(resourcePath)
        }
        if (dir) {
            if (!dir.startsWith(slash)) {
                url.append(slash)
            }
            url.append(dir)
        }

        def file = attrs.file?.toString()
        if (file) {
            if (!(file.startsWith(slash) || (dir != null && dir.endsWith(slash)))) {
                url.append(slash)
            }
            url.append(file)
        }

        return url.toString()
    }

    String getContextPath() {
        if (contextPath == null) {
            contextPath = requestStateLookupStrategy.getContextPath()
        }
        return contextPath
    }

    /**
     * Check for "absolute" attribute and render server URL if available from Config or deducible in non-production.
     */
    private handleAbsolute(Map attrs) {
        def base = attrs.base
        if (base) {
            return base
        }

        if (isAbsolute(attrs)) {
            def u = makeServerURL()
            if (u) {
                return u
            }

            throw new IllegalStateException("Attribute absolute='true' specified but no grails.serverURL set in Config")
        }
    }

    private boolean isUriAbsolute(String uri) {
        // not using new URI(uri).absolute in order to avoid create the URI object, which is slow
        return absoluteUrlPattern.matcher(uri).matches()
    }

    /**
     * Get the declared URL of the server from config, or guess at localhost for non-production.
     */
    String makeServerURL() {
        def u = configuredServerBaseURL
        if (!u) {
            // Leave it null if we're in production so we can throw
            final webRequest = GrailsWebRequest.lookup()

            u = webRequest?.baseUrl
            if (!u && !Environment.isWarDeployed()) {
                u = "http://localhost:${System.getProperty('server.port') ?: '8080'}${contextPath ?: '' }"
            }
        }
        log.trace('Resolved base server URL: {}', u)
        return u
    }

    String getServerBaseURL() {
        return makeServerURL()
    }

    void setPluginManager(GrailsPluginManager pluginManager) {
        this.pluginManager = pluginManager
    }

    /**
     * An index over the registered controllers, paired with the artefact array it was built from.
     *
     * <p>The two are published together through a single volatile reference. Publishing them as two
     * separate fields let a request that raced a controller reload store an index built from the old
     * controllers next to the new array, after which the identity check passed and the stale index was
     * served until the controllers changed again.</p>
     */
    private static final class ControllerIndex<T> {

        final GrailsClass[] controllers
        final Map<String, Set<T>> entries

        ControllerIndex(GrailsClass[] controllers, Map<String, Set<T>> entries) {
            this.controllers = controllers
            this.entries = entries
        }

        boolean isFor(GrailsClass[] current) {
            current.is(controllers)
        }
    }

    /**
     * A controller as a link addresses it: by logical name and namespace, {@code null} being the
     * default namespace.
     */
    private static final class ControllerRef {

        final String name
        final String namespace

        ControllerRef(String name, String namespace) {
            this.name = name
            this.namespace = namespace
        }

        @Override
        boolean equals(Object other) {
            other instanceof ControllerRef &&
                    name == ((ControllerRef) other).name &&
                    namespace == ((ControllerRef) other).namespace
        }

        @Override
        int hashCode() {
            Objects.hash(name, namespace)
        }
    }

    /**
     * The controller a resource link resolved to. When the request context chose it, the namespace it
     * was found in is carried as well, so the link stays in that namespace.
     */
    private static final class ResourceTarget {

        final String controller
        final String namespace
        final boolean pinsNamespace

        ResourceTarget(String controller, String namespace, boolean pinsNamespace) {
            this.controller = controller
            this.namespace = namespace
            this.pinsNamespace = pinsNamespace
        }
    }
}

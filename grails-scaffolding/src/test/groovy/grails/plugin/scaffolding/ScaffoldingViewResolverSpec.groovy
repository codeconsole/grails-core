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
package grails.plugin.scaffolding

import java.nio.charset.StandardCharsets

import grails.core.GrailsControllerClass
import grails.plugin.scaffolding.annotation.Scaffold
import org.apache.grails.scaffolding.ScaffoldedPages
import org.grails.gsp.GroovyPageTemplate
import org.grails.gsp.GroovyPagesTemplateEngine
import org.grails.gsp.io.GroovyPageScriptSource
import org.grails.web.gsp.io.GrailsConventionGroovyPageLocator
import org.grails.web.servlet.mvc.GrailsWebRequest
import org.grails.web.servlet.view.GroovyPageView
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.core.io.support.ResourcePatternResolver
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.support.StaticWebApplicationContext
import spock.lang.Specification

class ScaffoldingViewResolverSpec extends Specification {

    static final String TEST_NAMESPACE = "admin"
    static final String TEST_VIEW_NAME = "/event/index"
    /** A view whose template exists nowhere on disk, so the tests decide what the resolver finds. */
    static final String LIST_VIEW_NAME = "/event/list"
    static final String LIST_TEMPLATE = 'list ${className}'

    ScaffoldingViewResolver resolver
    GrailsConventionGroovyPageLocator mockPageLocator
    GroovyPagesTemplateEngine mockTemplateEngine
    GrailsWebRequest mockWebRequest
    GrailsControllerClass mockControllerClass

    def setup() {
        resolver = new ScaffoldingViewResolver()
        mockPageLocator = Mock(GrailsConventionGroovyPageLocator)
        mockTemplateEngine = Mock(GroovyPagesTemplateEngine)
        mockControllerClass = Mock(GrailsControllerClass)

        // Create GrailsWebRequest with required constructor args
        def mockHttpRequest = Mock(jakarta.servlet.http.HttpServletRequest)
        def mockHttpResponse = Mock(jakarta.servlet.http.HttpServletResponse)
        def mockServletContext = Mock(jakarta.servlet.ServletContext)
        mockWebRequest = Stub(GrailsWebRequest, constructorArgs: [mockHttpRequest, mockHttpResponse, mockServletContext])

        resolver.groovyPageLocator = mockPageLocator
        resolver.templateEngine = mockTemplateEngine
        // a view is created against the servlet context the resolver runs in
        def context = new StaticWebApplicationContext()
        context.servletContext = Stub(jakarta.servlet.ServletContext) {
            getInitParameterNames() >> Collections.emptyEnumeration()
            getAttributeNames() >> Collections.emptyEnumeration()
        }
        context.refresh()
        resolver.applicationContext = context

        // Set up thread local
        RequestContextHolder.setRequestAttributes(mockWebRequest)
    }

    def cleanup() {
        RequestContextHolder.resetRequestAttributes()
        resolver.clearCache()
    }

    // Helper methods
    void setupScaffoldController(Class controllerClazz, Class scaffoldDomain = null) {
        mockControllerClass.clazz >> controllerClazz
        mockControllerClass.getPropertyValue('scaffold') >> scaffoldDomain
        mockWebRequest.controllerClass >> mockControllerClass
    }

    void setupNamespaceController(String namespace = TEST_NAMESPACE) {
        mockControllerClass.namespace >> namespace
    }

    static Resource template(String text) {
        new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8))
    }

    /** Serves each classpath template location from {@code templates}, and every copy of one from {@code copies}. */
    ResourcePatternResolver templates(Map<String, String> templates, Map<String, List<String>> copies = [:]) {
        Stub(ResourcePatternResolver) {
            getResource(_ as String) >> { String location ->
                String text = templates[location - 'classpath:META-INF/templates/scaffolding/' - '.gsp']
                text != null ? template(text) : Stub(Resource) { exists() >> false }
            }
            getResources(_ as String) >> { String pattern ->
                (copies[pattern - 'classpath*:META-INF/templates/scaffolding/' - '.gsp'] ?: []).collect { template(it) } as Resource[]
            }
        }
    }

    String className() {
        resolver.model(TestDomain).className
    }

    String pageFor(String templatePath, String text, Class domain = TestDomain) {
        ScaffoldedPages.uri(templatePath, resolver.model(domain).asMap(), text.getBytes(StandardCharsets.UTF_8))
    }

    /** A resolver as a production application or a native image has one, where compiled pages are used. */
    ScaffoldingViewResolver resolverUsingCompiledPages(boolean nativeImage = false) {
        ScaffoldingViewResolver compiled = new ScaffoldingViewResolver() {
            @Override
            protected boolean precompiledPagesInUse() { true }

            @Override
            protected boolean inNativeImage() { nativeImage }
        }
        compiled.groovyPageLocator = mockPageLocator
        compiled.templateEngine = mockTemplateEngine
        compiled.applicationContext = resolver.applicationContext
        compiled
    }

    GroovyPageView mockViewWithUrl(String url) {
        def view = Mock(GroovyPageView)
        view.url >> url
        return view
    }

    void "test enableNamespaceViewDefaults defaults to false"() {
        expect:
        !resolver.enableNamespaceViewDefaults
    }

    void "test scaffold value cache stores null values for non-scaffold controllers"() {
        given:
        setupScaffoldController(String)

        when:
        def result = resolver.getScaffoldValue(mockControllerClass)

        then:
        result == null
        resolver.scaffoldValueCache.containsKey(String)
        resolver.scaffoldValueCache.get(String) == ScaffoldingViewResolver.NULL_SCAFFOLD_VALUE
    }

    void "test scaffold value cache returns cached value"() {
        given:
        setupScaffoldController(String)
        def cachedValue = String
        resolver.scaffoldValueCache.put(String, cachedValue)

        when:
        def result = resolver.getScaffoldValue(mockControllerClass)

        then:
        result == cachedValue
        0 * mockControllerClass.getPropertyValue(_) // Uses cache
    }

    void "test scaffold value cache handles annotation"() {
        given:
        setupScaffoldController(TestScaffoldController)

        when:
        def result = resolver.getScaffoldValue(mockControllerClass)

        then:
        result == TestDomain
        resolver.scaffoldValueCache.containsKey(TestScaffoldController)
    }

    void "test clearCache clears both view and scaffold caches"() {
        given:
        resolver.generatedViewCache.put("test", Mock(GroovyPageView))
        resolver.scaffoldValueCache.put(String, String)

        when:
        resolver.clearCache()

        then:
        resolver.generatedViewCache.isEmpty()
        resolver.scaffoldValueCache.isEmpty()
    }

    void "test buildCacheKey includes view name"() {
        given:
        mockPageLocator.resolveViewFormat(TEST_VIEW_NAME) >> TEST_VIEW_NAME

        when:
        def cacheKey = resolver.buildCacheKey(TEST_VIEW_NAME)

        then:
        cacheKey != null
        cacheKey.contains(TEST_VIEW_NAME)
    }

    void "test namespace controller without scaffold annotation returns null scaffold value"() {
        given:
        resolver.enableNamespaceViewDefaults = true
        setupScaffoldController(String)
        setupNamespaceController()

        expect:
        resolver.getScaffoldValue(mockControllerClass) == null
    }

    void "test tryGenerateScaffoldedView returns null for non-scaffold controller"() {
        given:
        setupScaffoldController(String)

        when:
        def result = resolver.tryGenerateScaffoldedView(TEST_VIEW_NAME, mockControllerClass) { String shortViewName ->
            [shortViewName]
        }

        then:
        result == null
    }

    void "test tryGenerateScaffoldedView uses generated view cache"() {
        given:
        def cacheKey = "test-cache-key"
        def cachedView = Mock(GroovyPageView)
        resolver.generatedViewCache.put(cacheKey, cachedView)

        expect:
        resolver.generatedViewCache.get(cacheKey) == cachedView
    }

    void "test tryGenerateScaffoldedView returns null when resource does not exist"() {
        given:
        setupScaffoldController(TestScaffoldController, TestDomain)
        mockPageLocator.resolveViewFormat(LIST_VIEW_NAME) >> LIST_VIEW_NAME
        resolver.resourceLoader = templates([:])

        when:
        def result = resolver.tryGenerateScaffoldedView(LIST_VIEW_NAME, mockControllerClass) { String shortViewName ->
            [shortViewName]
        }

        then:
        result == null
    }

    void "test RestfulServiceController annotation without AST transformation returns null"() {
        given:
        setupScaffoldController(TestRestfulServiceScaffoldController)

        when:
        def result = resolver.getScaffoldValue(mockControllerClass)

        then:
        // This test validates RAW annotation behavior (pre-AST transformation).
        // In real applications, ScaffoldingControllerInjector AST transformation extracts
        // the generic type from RestfulServiceController<TestDomain> and sets domain()
        // at compile time, so @Scaffold(RestfulServiceController<User>) DOES work.
        // See grails-test-examples/scaffolding for working integration tests.
        result == null
        resolver.scaffoldValueCache.containsKey(TestRestfulServiceScaffoldController)
    }

    void "test Scaffold annotation with domain attribute works correctly"() {
        given:
        setupScaffoldController(TestScaffoldController, TestDomain)

        when:
        def result = resolver.getScaffoldValue(mockControllerClass)

        then:
        // This tests @Scaffold(domain = TestDomain) AND validates the post-AST behavior
        // of @Scaffold(RestfulServiceController<TestDomain>) since AST transformation
        // sets domain = TestDomain at compile time for both patterns
        result == TestDomain
        resolver.scaffoldValueCache.containsKey(TestScaffoldController)
    }

    void "test namespace view URL detection identifies namespace-specific views"() {
        given:
        def namespaceView = mockViewWithUrl("/grails-app/views/${TEST_NAMESPACE}/event/index.gsp")
        def nonNamespaceView = mockViewWithUrl("/grails-app/views/event/index.gsp")
        setupNamespaceController()

        expect:
        // Namespace view should contain namespace in URL
        namespaceView.url.contains("/${TEST_NAMESPACE}/")
        // Non-namespace view should not
        !nonNamespaceView.url.contains("/${TEST_NAMESPACE}/")
    }

    void "test cache prevents repeated reflection for non-scaffold controllers"() {
        given:
        setupScaffoldController(String) // Non-scaffold controller

        when: "First call performs reflection"
        def result1 = resolver.getScaffoldValue(mockControllerClass)

        then:
        result1 == null
        resolver.scaffoldValueCache.get(String) == ScaffoldingViewResolver.NULL_SCAFFOLD_VALUE

        when: "Second call uses cache"
        def result2 = resolver.getScaffoldValue(mockControllerClass)

        then:
        result2 == null
        0 * mockControllerClass.getPropertyValue(_) // No reflection on second call
    }

    void "a scaffolded view is served by the page compiled from its template and model"() {
        given:
        setupScaffoldController(TestScaffoldController, TestDomain)
        mockPageLocator.resolveViewFormat(LIST_VIEW_NAME) >> LIST_VIEW_NAME
        resolver.resourceLoader = templates(list: LIST_TEMPLATE)
        String expected = pageFor('list', LIST_TEMPLATE)
        def page = Stub(GroovyPageScriptSource)

        when:
        def view = resolver.tryGenerateScaffoldedView(LIST_VIEW_NAME, mockControllerClass) { String name -> [name] }

        then: 'the page is found under its template and domain class'
        expected.startsWith("/grails-scaffolded/${TestDomain.name}/list-")
        1 * mockPageLocator.findPage(expected) >> page

        and: 'rendered from the compiled page, with nothing expanded'
        1 * mockTemplateEngine.createTemplate(page)
        0 * mockTemplateEngine.createTemplate(_ as Resource, _)
        view instanceof GroovyPageView
        (view as GroovyPageView).url == expected
    }

    void "a template with no compiled page is expanded as before"() {
        given:
        setupScaffoldController(TestScaffoldController, TestDomain)
        mockPageLocator.resolveViewFormat(LIST_VIEW_NAME) >> LIST_VIEW_NAME
        mockTemplateEngine.gspEncoding >> 'UTF-8'
        resolver.resourceLoader = templates(list: LIST_TEMPLATE)

        when:
        def view = resolver.tryGenerateScaffoldedView(LIST_VIEW_NAME, mockControllerClass) { String name -> [name] }

        then:
        1 * mockPageLocator.findPage(pageFor('list', LIST_TEMPLATE)) >> null
        1 * mockTemplateEngine.createTemplate({ Resource expanded -> expanded.inputStream.text == "list ${className()}".toString() }, true) >> Stub(GroovyPageTemplate)
        view instanceof GroovyPageView
    }

    void "a namespace-specific template is looked for under its own path"() {
        given:
        setupScaffoldController(TestScaffoldController, TestDomain)
        mockPageLocator.resolveViewFormat(LIST_VIEW_NAME) >> LIST_VIEW_NAME
        resolver.resourceLoader = templates(list: LIST_TEMPLATE, 'admin/list': 'admin list ${className}')
        String expected = pageFor('admin/list', 'admin list ${className}')

        when:
        resolver.tryGenerateScaffoldedView(LIST_VIEW_NAME, mockControllerClass) { String name -> ["admin/${name}".toString(), name] }

        then:
        expected.startsWith("/grails-scaffolded/${TestDomain.name}/admin/list-")
        1 * mockPageLocator.findPage(expected) >> Stub(GroovyPageScriptSource)
        0 * mockPageLocator.findPage(pageFor('list', LIST_TEMPLATE))
    }

    void "a customised template is looked for under its own page, never the stock template's"() {
        given:
        setupScaffoldController(TestScaffoldController, TestDomain)
        mockPageLocator.resolveViewFormat(LIST_VIEW_NAME) >> LIST_VIEW_NAME
        mockTemplateEngine.gspEncoding >> 'UTF-8'
        String customised = 'customised list ${className}'
        resolver.resourceLoader = templates(list: customised)

        when:
        resolver.tryGenerateScaffoldedView(LIST_VIEW_NAME, mockControllerClass) { String name -> [name] }

        then:
        pageFor('list', customised) != pageFor('list', LIST_TEMPLATE)
        1 * mockPageLocator.findPage(pageFor('list', customised)) >> null
        0 * mockPageLocator.findPage(pageFor('list', LIST_TEMPLATE))
        1 * mockTemplateEngine.createTemplate(_ as Resource, true) >> Stub(GroovyPageTemplate)
    }

    void "with reloading enabled the template is always expanded, so an edit to it shows"() {
        given:
        resolver.enableReload = true
        setupScaffoldController(TestScaffoldController, TestDomain)
        mockPageLocator.resolveViewFormat(LIST_VIEW_NAME) >> LIST_VIEW_NAME
        mockTemplateEngine.gspEncoding >> 'UTF-8'
        resolver.resourceLoader = templates(list: LIST_TEMPLATE)

        when:
        resolver.tryGenerateScaffoldedView(LIST_VIEW_NAME, mockControllerClass) { String name -> [name] }

        then:
        0 * mockPageLocator.findPage(_)
        1 * mockTemplateEngine.createTemplate(_ as Resource, false) >> Stub(GroovyPageTemplate)
    }

    void "a compiled page is looked for once and the view kept"() {
        given:
        setupScaffoldController(TestScaffoldController, TestDomain)
        mockPageLocator.resolveViewFormat(LIST_VIEW_NAME) >> LIST_VIEW_NAME
        resolver.resourceLoader = templates(list: LIST_TEMPLATE)

        when:
        def first = resolver.tryGenerateScaffoldedView(LIST_VIEW_NAME, mockControllerClass) { String name -> [name] }
        def second = resolver.tryGenerateScaffoldedView(LIST_VIEW_NAME, mockControllerClass) { String name -> [name] }

        then:
        1 * mockPageLocator.findPage(pageFor('list', LIST_TEMPLATE)) >> Stub(GroovyPageScriptSource)
        second.is(first)
    }

    void "a template expanded where compiled pages are used is reported, once"() {
        given:
        def compiled = resolverUsingCompiledPages()
        setupScaffoldController(TestScaffoldController, TestDomain)
        mockPageLocator.resolveViewFormat(_ as String) >> { String name -> name }
        mockPageLocator.findPage(_) >> null
        mockTemplateEngine.gspEncoding >> 'UTF-8'
        mockTemplateEngine.createTemplate(_ as Resource, _) >> Stub(GroovyPageTemplate)
        compiled.resourceLoader = templates(list: LIST_TEMPLATE)

        when: 'two views expand the same template for the same domain class'
        compiled.tryGenerateScaffoldedView('/event/list', mockControllerClass) { String name -> [name] }
        compiled.tryGenerateScaffoldedView('/other/list', mockControllerClass) { String name -> [name] }

        then:
        compiled.reportedPages == ["${TestDomain.name}:list".toString()] as Set
    }

    void "during development an expanded template is not reported"() {
        given:
        setupScaffoldController(TestScaffoldController, TestDomain)
        mockPageLocator.resolveViewFormat(LIST_VIEW_NAME) >> LIST_VIEW_NAME
        mockTemplateEngine.gspEncoding >> 'UTF-8'
        mockTemplateEngine.createTemplate(_ as Resource, _) >> Stub(GroovyPageTemplate)
        def developing = new ScaffoldingViewResolver() {
            @Override
            protected boolean precompiledPagesInUse() { false }
        }
        developing.groovyPageLocator = mockPageLocator
        developing.templateEngine = mockTemplateEngine
        developing.applicationContext = resolver.applicationContext
        developing.resourceLoader = templates(list: LIST_TEMPLATE)

        when:
        developing.tryGenerateScaffoldedView(LIST_VIEW_NAME, mockControllerClass) { String name -> [name] }

        then:
        developing.reportedPages.isEmpty()
    }

    void "a native image with no page for its template is served the one compiled from another copy of it"() {
        given: 'the application resolves its override, but the build compiled the stock template'
        def image = resolverUsingCompiledPages(true)
        setupScaffoldController(TestScaffoldController, TestDomain)
        mockPageLocator.resolveViewFormat(LIST_VIEW_NAME) >> LIST_VIEW_NAME
        String override = 'override list ${className}'
        image.resourceLoader = templates([list: override], [list: [override, LIST_TEMPLATE]])
        def stockPage = Stub(GroovyPageScriptSource)

        when:
        def view = image.tryGenerateScaffoldedView(LIST_VIEW_NAME, mockControllerClass) { String name -> [name] }

        then: 'a native image cannot expand a template, so the compiled copy is served'
        1 * mockPageLocator.findPage(pageFor('list', override)) >> null
        1 * mockPageLocator.findPage(pageFor('list', LIST_TEMPLATE)) >> stockPage
        0 * mockTemplateEngine.createTemplate(_ as Resource, _)
        (view as GroovyPageView).url == pageFor('list', LIST_TEMPLATE)

        and: 'and the substitution is reported'
        image.reportedPages == ["${TestDomain.name}:list".toString()] as Set
    }

    void "on the JVM a template with no page of its own is expanded, never served another copy's page"() {
        given:
        def jvm = resolverUsingCompiledPages(false)
        setupScaffoldController(TestScaffoldController, TestDomain)
        mockPageLocator.resolveViewFormat(LIST_VIEW_NAME) >> LIST_VIEW_NAME
        mockTemplateEngine.gspEncoding >> 'UTF-8'
        String override = 'override list ${className}'
        jvm.resourceLoader = templates([list: override], [list: [override, LIST_TEMPLATE]])

        when:
        jvm.tryGenerateScaffoldedView(LIST_VIEW_NAME, mockControllerClass) { String name -> [name] }

        then:
        1 * mockPageLocator.findPage(pageFor('list', override)) >> null
        0 * mockPageLocator.findPage(pageFor('list', LIST_TEMPLATE))
        1 * mockTemplateEngine.createTemplate({ Resource expanded -> expanded.inputStream.text == "override list ${className()}".toString() }, true) >> Stub(GroovyPageTemplate)
    }

    // Test domain class for annotation testing
    static class TestDomain {}

    @Scaffold(domain = TestDomain)
    static class TestScaffoldController {}

    @Scaffold(RestfulServiceController<TestDomain>)
    static class TestRestfulServiceScaffoldController {}
}

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
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.support.StaticWebApplicationContext
import spock.lang.Specification

class ScaffoldingViewResolverSpec extends Specification {

    static final String TEST_NAMESPACE = "admin"
    static final String TEST_VIEW_NAME = "/event/index"
    static final String SHOW_TEMPLATE = 'show ${className}'

    /**
     * The page the build writes for {@link #SHOW_TEMPLATE} and a domain class {@code URLMapping} in the
     * default package, where the model is the same on every platform. GenerateScaffoldedViewsTaskSpec
     * in the Gradle plugin expects this same name, which is what keeps the two derivations together.
     */
    static final String URL_MAPPING_SHOW_PAGE = '/grails-scaffolded/URLMapping/90edd843a67c1f52a2400a029acfc69e.gsp'

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

    /** A domain class in the default package, which a test source cannot declare and still be referenced. */
    static Class urlMapping() {
        new GroovyClassLoader().parseClass('class URLMapping {}')
    }

    static Resource template(String text) {
        new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8))
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
        def result = resolver.tryGenerateScaffoldedView(TEST_VIEW_NAME, mockControllerClass) { shortViewName ->
            Mock(Resource)
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
        mockPageLocator.resolveViewFormat(TEST_VIEW_NAME) >> TEST_VIEW_NAME

        when:
        def result = resolver.tryGenerateScaffoldedView(TEST_VIEW_NAME, mockControllerClass) { shortViewName ->
            def resource = Mock(Resource)
            resource.exists() >> false
            return resource
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
        setupScaffoldController(TestScaffoldController, urlMapping())
        mockPageLocator.resolveViewFormat(TEST_VIEW_NAME) >> TEST_VIEW_NAME
        def page = Stub(GroovyPageScriptSource)

        when:
        def view = resolver.tryGenerateScaffoldedView(TEST_VIEW_NAME, mockControllerClass) { String name -> template(SHOW_TEMPLATE) }

        then: 'the page is found by the name the build gave it'
        1 * mockPageLocator.findPage(URL_MAPPING_SHOW_PAGE) >> page

        and: 'rendered from the compiled page, with nothing expanded'
        1 * mockTemplateEngine.createTemplate(page)
        0 * mockTemplateEngine.createTemplate(_ as Resource, _)
        view instanceof GroovyPageView
        (view as GroovyPageView).url == URL_MAPPING_SHOW_PAGE
    }

    void "a template with no compiled page is expanded as before"() {
        given:
        setupScaffoldController(TestScaffoldController, urlMapping())
        mockPageLocator.resolveViewFormat(TEST_VIEW_NAME) >> TEST_VIEW_NAME
        mockTemplateEngine.gspEncoding >> 'UTF-8'

        when:
        def view = resolver.tryGenerateScaffoldedView(TEST_VIEW_NAME, mockControllerClass) { String name -> template(SHOW_TEMPLATE) }

        then:
        1 * mockPageLocator.findPage(URL_MAPPING_SHOW_PAGE) >> null
        1 * mockTemplateEngine.createTemplate({ Resource expanded -> expanded.inputStream.text == 'show URLMapping' }, true) >> Stub(GroovyPageTemplate)
        view instanceof GroovyPageView
    }

    void "a different template is looked for under its own page, never another template's"() {
        given:
        setupScaffoldController(TestScaffoldController, urlMapping())
        mockPageLocator.resolveViewFormat(TEST_VIEW_NAME) >> TEST_VIEW_NAME
        mockTemplateEngine.gspEncoding >> 'UTF-8'
        String customised = 'customised show ${className}'
        String expected = ScaffoldedPages.uri(resolver.model(urlMapping()).asMap(), customised.getBytes(StandardCharsets.UTF_8))

        when:
        resolver.tryGenerateScaffoldedView(TEST_VIEW_NAME, mockControllerClass) { String name -> template(customised) }

        then:
        expected != URL_MAPPING_SHOW_PAGE
        1 * mockPageLocator.findPage(expected) >> null
        0 * mockPageLocator.findPage(URL_MAPPING_SHOW_PAGE)
        1 * mockTemplateEngine.createTemplate(_ as Resource, true) >> Stub(GroovyPageTemplate)
    }

    void "with reloading enabled the template is always expanded, so an edit to it shows"() {
        given:
        resolver.enableReload = true
        setupScaffoldController(TestScaffoldController, urlMapping())
        mockPageLocator.resolveViewFormat(TEST_VIEW_NAME) >> TEST_VIEW_NAME
        mockTemplateEngine.gspEncoding >> 'UTF-8'

        when:
        resolver.tryGenerateScaffoldedView(TEST_VIEW_NAME, mockControllerClass) { String name -> template(SHOW_TEMPLATE) }

        then:
        0 * mockPageLocator.findPage(_)
        1 * mockTemplateEngine.createTemplate(_ as Resource, false) >> Stub(GroovyPageTemplate)
    }

    void "a compiled page is looked for once and the view kept"() {
        given:
        setupScaffoldController(TestScaffoldController, urlMapping())
        mockPageLocator.resolveViewFormat(TEST_VIEW_NAME) >> TEST_VIEW_NAME
        def page = Stub(GroovyPageScriptSource)

        when:
        def first = resolver.tryGenerateScaffoldedView(TEST_VIEW_NAME, mockControllerClass) { String name -> template(SHOW_TEMPLATE) }
        def second = resolver.tryGenerateScaffoldedView(TEST_VIEW_NAME, mockControllerClass) { String name -> template(SHOW_TEMPLATE) }

        then:
        1 * mockPageLocator.findPage(URL_MAPPING_SHOW_PAGE) >> page
        second.is(first)
    }

    // Test domain class for annotation testing
    static class TestDomain {}

    @Scaffold(domain = TestDomain)
    static class TestScaffoldController {}

    @Scaffold(RestfulServiceController<TestDomain>)
    static class TestRestfulServiceScaffoldController {}
}

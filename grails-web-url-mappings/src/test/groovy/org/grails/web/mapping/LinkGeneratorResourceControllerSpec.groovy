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

import grails.core.DefaultGrailsApplication
import grails.util.GrailsWebMockUtil
import grails.web.CamelCaseUrlConverter
import grails.web.mapping.UrlCreator
import grails.web.mapping.UrlMappingsHolder
import org.grails.datastore.mapping.keyvalue.mapping.config.KeyValueMappingContext
import org.grails.datastore.mapping.model.MappingContext
import org.grails.web.mapping.domainlink.AdminGadgetsController
import org.grails.web.mapping.domainlink.Assessment
import org.grails.web.mapping.domainlink.AssessmentController
import org.grails.web.mapping.domainlink.AuditBallotController
import org.grails.web.mapping.domainlink.Ballot
import org.grails.web.mapping.domainlink.BallotController
import org.grails.web.mapping.domainlink.Chapter
import org.grails.web.mapping.domainlink.ChapterApiController
import org.grails.web.mapping.domainlink.ChapterController
import org.grails.web.mapping.domainlink.Chronicle
import org.grails.web.mapping.domainlink.ChroniclesController
import org.grails.web.mapping.domainlink.Gadget
import org.grails.web.mapping.domainlink.GadgetsController
import org.grails.web.mapping.domainlink.HomeController
import org.grails.web.mapping.domainlink.ManageAssessmentController
import org.grails.web.mapping.domainlink.ManageBallotController
import org.grails.web.mapping.domainlink.ManageDashboardController
import org.grails.web.mapping.domainlink.Note
import org.grails.web.mapping.domainlink.NoteController
import org.grails.web.mapping.domainlink.PeopleController
import org.grails.web.mapping.domainlink.Person
import org.grails.web.mapping.domainlink.Tag
import org.grails.web.mapping.domainlink.TagsController
import org.grails.web.mapping.domainlink.Widget
import org.grails.web.mapping.domainlink.WidgetsController
import org.grails.web.util.WebUtils
import org.springframework.web.context.request.RequestContextHolder

import spock.lang.Specification

/**
 * Tests that a {@code resource} link targets the controller that actually exposes the domain class,
 * rather than assuming the controller is named after the domain class.
 */
class LinkGeneratorResourceControllerSpec extends Specification {

    static final String BASE_URL = 'https://myserver.com/foo'
    static final String CONTEXT = '/bar'

    DefaultGrailsApplication grailsApplication

    def setup() {
        WebUtils.clearGrailsWebRequest()
        GrailsWebMockUtil.bindMockWebRequest()
        grailsApplication = new DefaultGrailsApplication(
                PeopleController,
                WidgetsController,
                GadgetsController,
                AdminGadgetsController,
                NoteController,
                ChapterController,
                ChapterApiController,
                TagsController,
                ChroniclesController,
                AssessmentController,
                ManageAssessmentController,
                ManageDashboardController,
                HomeController,
                BallotController,
                ManageBallotController,
                AuditBallotController
        ).tap {
            initialise()
        }
    }

    void cleanup() {
        RequestContextHolder.resetRequestAttributes()
        WebUtils.clearGrailsWebRequest()
    }

    def "a resource link targets the controller declaring the domain class, not the domain class name"() {
        given: 'PeopleController is the only controller declaring Person'
        def generator = createGenerator()

        expect: 'the link targets people rather than person'
        generator.link(resource: new Person(id: 1), action: 'show') == '/bar/people/show/1'
    }

    def "the domain class is resolved through an intermediate base class"() {
        given: 'WidgetsController reaches Widget through WidgetControllerBase'
        def generator = createGenerator()

        expect: 'the intermediate class does not hide the domain class'
        generator.link(resource: new Widget(id: 2), action: 'show') == '/bar/widgets/show/2'
    }

    def "an ambiguous domain class falls back to the domain class name"() {
        given: 'both GadgetsController and AdminGadgetsController declare Gadget'
        def generator = createGenerator()

        expect: 'no controller is inferred, preserving the existing behaviour'
        generator.link(resource: new Gadget(id: 3), action: 'show') == '/bar/gadget/show/3'
    }

    def "a domain class no controller declares falls back to the domain class name"() {
        given: 'NoteController declares no domain class'
        def generator = createGenerator()

        expect: 'the domain class name is used, as before'
        generator.link(resource: new Note(id: 4), action: 'show') == '/bar/note/show/4'
    }

    def "an explicit controller attribute overrides the resolved controller"() {
        given: 'Person would otherwise resolve to people'
        def generator = createGenerator()

        expect: 'the explicit controller wins'
        generator.link(resource: new Person(id: 5), controller: 'note', action: 'show') == '/bar/note/show/5'
    }

    def "a controller named after the domain class wins over one that declares it"() {
        given: 'ChapterController is named for Chapter, and ChapterApiController declares it'
        def generator = createGenerator()

        expect: 'the naming convention wins, so an application relying on it is unaffected'
        generator.link(resource: new Chapter(id: 6), action: 'show') == '/bar/chapter/show/6'
    }

    def "the domain class is resolved through a generic interface"() {
        given: 'TagsController declares Tag through an interface rather than a superclass'
        def generator = createGenerator()

        expect: 'interfaces are walked too, as Groovy traits compile to interfaces'
        generator.link(resource: new Tag(id: 7), action: 'show') == '/bar/tags/show/7'
    }

    def "a base class declaring two domain classes does not claim the wrong one"() {
        given: 'ChroniclesController extends a base parameterised on both Person and Chronicle'
        def generator = createGenerator()

        expect: 'the ambiguous level is skipped and the resource comes from further up the hierarchy'
        generator.link(resource: new Chronicle(id: 8), action: 'show') == '/bar/chronicles/show/8'

        and: 'the other type argument is not claimed by that controller'
        generator.link(resource: new Person(id: 9), action: 'show') == '/bar/people/show/9'
    }

    def "a domain class passed instead of an instance resolves the same controller"() {
        given: 'the domain class rather than an instance, as used for an uninitialised association'
        def generator = createGenerator()

        expect: 'the same controller is resolved, so a lazy proxy and a loaded instance agree'
        generator.link(resource: Person, id: 1, action: 'show') == '/bar/people/show/1'
        generator.link(resource: new Person(id: 1), action: 'show') == '/bar/people/show/1'

        and: 'the naming convention still wins for a domain class'
        generator.link(resource: Chapter, id: 2, action: 'show') == '/bar/chapter/show/2'
    }

    def "resolution requires a mapping context"() {
        given: 'the same link generated with and without a mapping context'
        def person = new Person(id: 10)

        expect: 'the mapping context is what enables resolution'
        createGenerator(true).link(resource: person, action: 'show') == '/bar/people/show/10'
        createGenerator(false).link(resource: person, action: 'show') != '/bar/people/show/10'
    }

    def "a link rendered by a controller serving the domain class stays in that controller"() {
        given: 'ManageAssessmentController is handling the request, although AssessmentController is named after the domain'
        bindRequest('manageAssessment', 'manage')
        def generator = createGenerator()

        expect: 'the link targets the current controller in its namespace'
        generator.link(resource: new Assessment(id: 1), action: 'show') == '/bar/manage/manageAssessment/show/1'

        and: 'so does a link from the domain class, as an uninitialised association renders it'
        generator.link(resource: Assessment, id: 2, action: 'show') == '/bar/manage/manageAssessment/show/2'
    }

    def "a link rendered by the controller named after the domain class stays there"() {
        given: 'AssessmentController is handling the request'
        bindRequest('assessment', null)
        def generator = createGenerator()

        expect:
        generator.link(resource: new Assessment(id: 3), action: 'show') == '/bar/assessment/show/3'
    }

    def "a link rendered elsewhere in a namespace targets the controller serving the domain class there"() {
        given: 'ManageDashboardController, which serves no domain class, is handling a request in manage'
        bindRequest('manageDashboard', 'manage')
        def generator = createGenerator()

        expect: 'the only manage controller serving Assessment is chosen over the root one'
        generator.link(resource: new Assessment(id: 4), action: 'show') == '/bar/manage/manageAssessment/show/4'
    }

    def "a namespace with more than one controller serving the domain class falls through to the naming convention"() {
        given: 'two manage controllers declare Ballot'
        bindRequest('manageDashboard', 'manage')
        def generator = createGenerator()

        expect: 'the namespace is ambiguous, so the controller named after the domain class is used'
        generator.link(resource: new Ballot(id: 5), action: 'show') == '/bar/ballot/show/5'
    }

    def "a link rendered outside the namespace targets the controller serving the domain class outside it"() {
        given: 'HomeController, in the default namespace, is handling the request'
        bindRequest('home', null)
        def generator = createGenerator()

        expect: 'AssessmentController is the only default-namespace controller serving Assessment'
        generator.link(resource: new Assessment(id: 6), action: 'show') == '/bar/assessment/show/6'
    }

    def "a link generated outside a request uses the naming convention"() {
        given: 'no request is bound, as for a background job'
        RequestContextHolder.resetRequestAttributes()
        def generator = createGenerator()

        expect:
        generator.link(resource: new Assessment(id: 7), action: 'show') == '/bar/assessment/show/7'
    }

    def "an explicit namespace chooses the controller serving the domain class in that namespace"() {
        given: 'HomeController is handling the request'
        bindRequest('home', null)
        def generator = createGenerator()

        expect: 'the explicit namespace outranks the request context'
        generator.link(resource: new Assessment(id: 8), action: 'show', namespace: 'manage') == '/bar/manage/manageAssessment/show/8'
    }

    def "an explicit controller outranks the controller handling the request"() {
        given: 'ManageAssessmentController is handling the request'
        bindRequest('manageAssessment', 'manage')
        def generator = createGenerator()

        expect: 'the named controller is used, in its own namespace rather than the request one'
        generator.link(resource: new Assessment(id: 9), action: 'show', controller: 'assessment') == '/bar/assessment/show/9'
    }

    def "a cached link rendered by one controller is not served to another"() {
        given: 'two default-namespace controllers serve Chapter, and no controller is namespaced'
        def application = new DefaultGrailsApplication(ChapterController, ChapterApiController).tap { initialise() }
        def generator = createCachingGenerator(application)

        and: 'one instance, as a page rendering it repeatedly would pass'
        def chapter = new Chapter(id: 10)

        when: 'the same link is generated while each controller handles the request'
        bindRequest('chapterApi', null)
        def fromApi = generator.link(resource: chapter, action: 'show')
        bindRequest('chapter', null)
        def fromChapter = generator.link(resource: chapter, action: 'show')

        then: 'each gets its own controller rather than the first cached URL'
        fromApi == '/bar/chapterApi/show/10'
        fromChapter == '/bar/chapter/show/10'
    }

    def "a redirect, which names its own namespace, stays in the controller serving the domain class"() {
        given: 'ChapterApiController is handling the request, although ChapterController is named after the domain'
        bindRequest('chapterApi', null)
        def generator = createGenerator()

        expect: 'the explicit default namespace a controller redirect carries still resolves to the current controller'
        generator.link(resource: new Chapter(id: 11), method: 'GET', namespace: null) == '/bar/chapterApi/show/11'
    }

    def "a cached link naming a namespace, as a controller redirect does, is not served to another controller"() {
        given: 'two default-namespace controllers serve Chapter'
        def application = new DefaultGrailsApplication(ChapterController, ChapterApiController).tap { initialise() }
        def generator = createCachingGenerator(application)
        def chapter = new Chapter(id: 12)

        when: 'each controller links to the same instance with an action, naming its own namespace as a redirect does'
        bindRequest('chapterApi', null)
        def fromApi = generator.link(resource: chapter, action: 'show', namespace: null)
        bindRequest('chapter', null)
        def fromChapter = generator.link(resource: chapter, action: 'show', namespace: null)

        then:
        fromApi == '/bar/chapterApi/show/12'
        fromChapter == '/bar/chapter/show/12'
    }

    def "re-registering controllers rebuilds the index"() {
        given: 'an index built while PeopleController is registered'
        def generator = createGenerator()

        expect: 'it resolves'
        generator.link(resource: new Person(id: 11), action: 'show') == '/bar/people/show/11'

        when: 'PeopleController is no longer registered'
        generator.grailsApplication = new DefaultGrailsApplication(NoteController).tap { initialise() }

        then: 'the stale mapping is not reused'
        generator.link(resource: new Person(id: 11), action: 'show') != '/bar/people/show/11'
    }

    private void bindRequest(String controllerName, String namespace) {
        def webRequest = GrailsWebMockUtil.bindMockWebRequest()
        webRequest.setControllerName(controllerName)
        webRequest.setControllerNamespace(namespace)
    }

    private CachingLinkGenerator createCachingGenerator(DefaultGrailsApplication application) {
        def generator = new CachingLinkGenerator(BASE_URL, CONTEXT)
        configure(generator, application, true)
        generator
    }

    private MappingContext createMappingContext() {
        def context = new KeyValueMappingContext('')
        context.addPersistentEntity(Person)
        context.addPersistentEntity(Widget)
        context.addPersistentEntity(Gadget)
        context.addPersistentEntity(Note)
        context.addPersistentEntity(Chapter)
        context.addPersistentEntity(Tag)
        context.addPersistentEntity(Chronicle)
        context.addPersistentEntity(Assessment)
        context.addPersistentEntity(Ballot)
        context
    }

    private DefaultLinkGenerator createGenerator(boolean withMappingContext = true) {
        def generator = new DefaultLinkGenerator(BASE_URL, CONTEXT)
        configure(generator, grailsApplication, withMappingContext)
        generator
    }

    private void configure(DefaultLinkGenerator generator, DefaultGrailsApplication application, boolean withMappingContext) {
        generator.grailsUrlConverter = new CamelCaseUrlConverter()
        generator.grailsApplication = application
        if (withMappingContext) {
            generator.mappingContext = createMappingContext()
        }
        final callable = { String controller, String action, String namespace, String pluginName, String httpMethod, Map params ->
            [createRelativeURL: { String c, String a, String n, String p, Map parameterValues, String encoding, String fragment ->
                "${namespace ? '/' + namespace : ''}/$controller/$action${parameterValues.id ? '/' + parameterValues.id : ''}".toString()
            }] as UrlCreator
        }
        generator.urlMappingsHolder = [getReverseMapping: callable, getReverseMappingNoDefault: callable] as UrlMappingsHolder
    }
}

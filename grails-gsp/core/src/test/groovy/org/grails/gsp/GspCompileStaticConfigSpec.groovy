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

package org.grails.gsp

import spock.lang.Specification

import org.springframework.context.support.GenericApplicationContext

import grails.config.Config
import grails.core.GrailsApplication
import grails.core.GrailsClass
import grails.core.gsp.GrailsTagLibClass
import org.grails.config.PropertySourcesConfig
import org.grails.core.gsp.DefaultGrailsTagLibClass
import org.grails.gsp.compiler.GroovyPageParser
import org.grails.taglib.TagLibraryLookup

/**
 * Verifies that `grails.views.gsp.compileStatic` acts as an application-wide default,
 * so that pages compile statically without each one carrying a `compileStatic` directive.
 */
class GspCompileStaticConfigSpec extends Specification {

    void 'every page compiles statically when the config default is enabled'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor('grails.views.gsp.compileStatic': true)

        when:
        GroovyPageTemplate template = compile(engine, '${message(code:\'World\')}')

        then:
        template.metaInfo.compilationException == null
        template.metaInfo.compileStaticMode
        render(template) == 'Hello World'
    }

    void 'pages compile dynamically when the config default is absent'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor([:])

        when:
        GroovyPageTemplate template = compile(engine, '${message(code:\'World\')}')

        then:
        !template.metaInfo.compileStaticMode
        render(template) == 'Hello World'
    }

    void 'a page carrying an unrelated directive still honours the config default'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor('grails.views.gsp.compileStatic': true)

        when:
        GroovyPageTemplate template = compile(engine, '<%@ page contentType="text/html" %>${message(code:\'World\')}')

        then:
        template.metaInfo.compileStaticMode
        render(template) == 'Hello World'
    }

    void 'a page can opt out of the config default'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor('grails.views.gsp.compileStatic': true)

        when:
        GroovyPageTemplate template = compile(engine, '<%@ page compileStatic="false" %>${message(code:\'World\')}')

        then:
        !template.metaInfo.compileStaticMode
        render(template) == 'Hello World'
    }

    void 'additional tag library namespaces can be allowed from config as a comma separated string'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor(
                'grails.views.gsp.compileStatic': true,
                'grails.views.gsp.compileStaticConfig.taglibs': 'firsttaglib, sometaglib')

        when:
        GroovyPageTemplate template = compile(engine, '${sometaglib.something([a: 1])}')

        then:
        template.metaInfo.compilationException == null
        template.metaInfo.compileStaticMode
    }

    void 'additional tag library namespaces can be allowed from config as a list'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor(
                'grails.views.gsp.compileStatic': true,
                'grails.views.gsp.compileStaticConfig.taglibs': ['firsttaglib', 'sometaglib'])

        when:
        GroovyPageTemplate template = compile(engine, '${sometaglib.something([a: 1])}')

        then:
        template.metaInfo.compilationException == null
        template.metaInfo.compileStaticMode
    }

    void 'namespaces allowed by config are added to the default namespaces'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor(
                'grails.views.gsp.compileStatic': true,
                'grails.views.gsp.compileStaticConfig.taglibs': ['sometaglib'])

        when:
        GroovyPageTemplate template = compile(engine, '${g.message(code:\'World\')}')

        then:
        template.metaInfo.compilationException == null
        render(template) == 'Hello World'
    }

    void 'the model directive declares the model of a page and implies static compilation'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor([:])

        when:
        GroovyPageTemplate template = compile(engine, '<%@ page model="Date date" %>${date.time}')

        then:
        template.metaInfo.compileStaticMode
        render(template, [date: new Date(123L)]) == '123'
    }

    void 'the model directive declares several variables separated by semicolons'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor([:])

        when:
        GroovyPageTemplate template = compile(engine, '<%@ page model="Date first; Date second" %>${first.time}-${second.time}')

        then:
        render(template, [first: new Date(123L), second: new Date(456L)]) == '123-456'
    }

    void 'the taglibs directive allows extra namespaces for a single page'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor([:])

        when:
        GroovyPageTemplate template = compile(engine,
                '<%@ page compileStatic="true" taglibs="sometaglib" %>${sometaglib.something([a: 1])}')

        then:
        template.metaInfo.compilationException == null
        template.metaInfo.compileStaticMode
    }

    void 'the framework supplied names a page never declares are typed'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor('grails.views.gsp.compileStatic': true)

        when:
        GroovyPageTemplate template = compile(engine, source)

        then:
        template.metaInfo.compilationException == null

        where:
        source << [
                '${params.id}',
                '${params.int(\'max\')}',
                '${params.long(\'id\')}',
                '${params.boolean(\'active\')}',
                '${flash.message}',
                '${controllerName}',
                '${actionName}',
                '${namespace}',
                '${grailsApplication.config}',
                '${applicationContext.displayName}',
                '<% if (params.id) { %>x<% } %>',
                '${params.id ? \'y\' : \'n\'}',
        ]
    }

    void 'a framework supplied name reads the value bound for the page'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor('grails.views.gsp.compileStatic': true)

        when:
        GroovyPageTemplate template = compile(engine, '${params.id}-${controllerName}')

        then:
        render(template, [params: [id: '7'], controllerName: 'book']) == '7-book'
    }

    void 'a framework supplied name is null when nothing bound it'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor('grails.views.gsp.compileStatic': true)

        when:
        GroovyPageTemplate template = compile(engine, '[${controllerName}]')

        then:
        render(template) == '[null]'
    }

    void 'a page may still declare a model variable named after a framework supplied name'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor('grails.views.gsp.compileStatic': true)

        when:
        GroovyPageTemplate template = compile(engine, '<%@ page model="Map flashOfMine" %>${flashOfMine.id}')

        then:
        template.metaInfo.compilationException == null
        render(template, [flashOfMine: [id: '9']]) == '9'
    }

    void 'a page declaring a framework supplied name in its model gets its own declaration'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor('grails.views.gsp.compileStatic': true)

        when: 'the page has spoken for the name, so nothing is written over it'
        GroovyPageTemplate template = compile(engine, '<%@ page model="Map params" %>${params.id}')

        then:
        template.metaInfo.compilationException == null
        render(template, [params: [id: '9']]) == '9'
    }

    void 'the build can state compileStatic as a system property so both compile paths agree'() {
        given:
        System.setProperty(GroovyPageParser.CONFIG_PROPERTY_GSP_COMPILESTATIC, 'true')
        GroovyPagesTemplateEngine engine = engineFor([:])

        when: 'no configuration says anything'
        GroovyPageTemplate template = compile(engine, '${message(code:\'World\')}')

        then:
        template.metaInfo.compileStaticMode
        render(template) == 'Hello World'

        cleanup:
        System.clearProperty(GroovyPageParser.CONFIG_PROPERTY_GSP_COMPILESTATIC)
    }

    void 'the system property replaces what configuration said'() {
        given:
        System.setProperty(GroovyPageParser.CONFIG_PROPERTY_GSP_COMPILESTATIC, 'false')
        GroovyPagesTemplateEngine engine = engineFor('grails.views.gsp.compileStatic': true)

        when:
        GroovyPageTemplate template = compile(engine, '${message(code:\'World\')}')

        then:
        !template.metaInfo.compileStaticMode

        cleanup:
        System.clearProperty(GroovyPageParser.CONFIG_PROPERTY_GSP_COMPILESTATIC)
    }

    void 'a page directive still decides for the page that carries it'() {
        given:
        System.setProperty(GroovyPageParser.CONFIG_PROPERTY_GSP_COMPILESTATIC, 'true')
        GroovyPagesTemplateEngine engine = engineFor([:])

        when:
        GroovyPageTemplate template = compile(engine, '<%@ page compileStatic="false" %>${message(code:\'World\')}')

        then:
        !template.metaInfo.compileStaticMode

        cleanup:
        System.clearProperty(GroovyPageParser.CONFIG_PROPERTY_GSP_COMPILESTATIC)
    }

    void 'a name a page introduces with a tag does not have to be declared again'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor('grails.views.gsp.compileStatic': true)

        when:
        GroovyPageTemplate template = compile(engine, source)

        then:
        template.metaInfo.compilationException == null

        where:
        source << [
                '<g:set var="total" value="${1}"/>${total}',
                '<g:set var="total" value="${1}"/>${total.intValue()}',
                '<g:set var="rows" value="${[]}"/><g:each in="${rows}" var="row">${row.name}</g:each>',
                '<g:each in="${[]}" var="book" status="i">${i}. ${book.title}</g:each>',
                '<g:eachError bean="${[:]}" var="error">${error.field}</g:eachError>',
                '<g:set var="showNav" value="${true}"/><g:if test="${showNav}">x</g:if>',
                '<g:set var="a" value="${[:]}"/><g:set var="b" value="${a.k}"/>${b.deeper}',
        ]
    }

    void 'a page that declares nothing reads what it is rendered with'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor('grails.views.gsp.compileStatic': true)

        when: 'nothing said what these are, so nothing here can say they are wrong'
        GroovyPageTemplate template = compile(engine, source)

        then:
        template.metaInfo.compilationException == null

        where:
        source << [
                '${undeclaredModelVariable}',
                '${undeclaredModelVariable.id}',
                '${undeclaredModelVariable.some(1)}',
                '${sometaglib.something([a: 1])}',
                '${grailsApplication.controllerClasses.toList().sort { it.fullName }}',
        ]
    }

    void 'a page that declares its model is held to it'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor([:])

        when: 'declaring a model states what the page is rendered with'
        GroovyPageTemplate template = compile(engine, '<%@ page model="Date date" %>${undeclaredModelVariable}')

        then:
        template.metaInfo.compilationException.message.contains('The variable [undeclaredModelVariable] is undeclared.')
    }

    void 'strict holds a page to what it declares even where it declares no model'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor(
                'grails.views.gsp.compileStatic': true,
                'grails.views.gsp.compileStaticConfig.strict': true)

        when:
        GroovyPageTemplate template = compile(engine, '${undeclaredModelVariable}')

        then:
        template.metaInfo.compilationException.message.contains('The variable [undeclaredModelVariable] is undeclared.')
    }

    void 'the servlet scopes carry their own types'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor(
                'grails.views.gsp.compileStatic': true,
                'grails.views.gsp.compileStaticConfig.strict': true)

        when: 'read under strict, so nothing is resolving these dynamically'
        GroovyPageTemplate template = compile(engine, source)

        then:
        template.metaInfo.compilationException == null

        where:
        source << [
                '${request.contextPath}',
                '${request.getAttribute(\'x\')}',
                '${response.status}',
                '${session?.id}',
                '${application.serverInfo}',
        ]
    }

    void 'a mistyped member of a scope that carries its type is reported under strict'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor(
                'grails.views.gsp.compileStatic': true,
                'grails.views.gsp.compileStaticConfig.strict': true)

        when:
        GroovyPageTemplate template = compile(engine, '${request.contextPathTypo}')

        then:
        template.metaInfo.compilationException.message.contains('No such property: contextPathTypo')
    }

    void 'an operator on a closure parameter of no known type is reported by type checking'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor('grails.views.gsp.compileStatic': true)

        when: 'nothing declared or resolved it -- type checking simply inferred Object'
        GroovyPageTemplate template = compile(engine, source)

        then: 'reported here, rather than reaching the class writer'
        template.metaInfo.compilationException.message.contains('is not known here')
        !template.metaInfo.compilationException.message.contains('BUG!')
        !template.metaInfo.compilationException.message.contains('Cannot access method')

        where:
        source << [
                '<g:set var="rows" value="${[[a: 1]]}"/>${rows.withIndex().collect { r, i -> r + [n: i] }}',
                '<g:set var="rows" value="${[1]}"/>${rows.collect { r -> r * 2 }}',
        ]
    }

    void 'an operator on a name the page never declared is reported, not crashed on'() {
        given:
        GroovyPagesTemplateEngine engine = engineFor('grails.views.gsp.compileStatic': true)

        when: 'a subscript is written into the class rather than left to a call site'
        GroovyPageTemplate template = compile(engine, source)

        then: 'an error a page can act on, rather than a GroovyBugError out of the class writer'
        template.metaInfo.compilationException != null
        !template.metaInfo.compilationException.message.contains('BUG!')

        where:
        source << [
                '<g:set var="rows" value="${[1,2]}"/>${rows[0]}',
                '<g:set var="i" value="${1}"/>${i + 1}',
        ]
    }

    private static GroovyPagesTemplateEngine engineFor(Map<String, Object> configValues) {
        GenericApplicationContext context = new GenericApplicationContext().tap {
            beanFactory.registerSingleton(GrailsApplication.APPLICATION_ID,
                    grailsApplicationWith(new PropertySourcesConfig(configValues)))
            refresh()
        }
        GroovyPagesTemplateEngine engine = new GroovyPagesTemplateEngine()
        engine.applicationContext = context
        engine.afterPropertiesSet()
        TagLibraryLookup tagLibraryLookup = new TagLibraryLookup() {
            @Override
            protected void putTagLib(Map<String, Object> tags, String name, GrailsTagLibClass taglib) {
                tags.put(name, taglib.newInstance())
            }
        }
        tagLibraryLookup.registerTagLib(new DefaultGrailsTagLibClass(ConfigSampleTagLib))
        engine.tagLibraryLookup = tagLibraryLookup
        engine
    }

    private static GrailsApplication grailsApplicationWith(Config config) {
        [getMainContext: { -> null },
         getConfig: { -> config },
         getFlatConfig: { -> config.flatten() },
         getArtefacts: { String artefactType -> [] as GrailsClass[] },
         getArtefactByLogicalPropertyName: { String type, String logicalName -> null }] as GrailsApplication
    }

    private static GroovyPageTemplate compile(GroovyPagesTemplateEngine engine, String source) {
        engine.createTemplate(source, "template${source.hashCode()}") as GroovyPageTemplate
    }

    private static String render(GroovyPageTemplate template, Map model = [:]) {
        StringWriter output = new StringWriter()
        template.make(model).writeTo(new PrintWriter(output, true))
        output.toString()
    }
}

class ConfigSampleTagLib {

    static returnObjectForTags = ['message']

    Closure message = { attrs ->
        "Hello ${attrs.code}"
    }
}

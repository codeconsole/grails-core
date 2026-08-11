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
package org.grails.web.taglib

import org.grails.gsp.GroovyPagesTemplateEngine
import org.grails.gsp.compiler.GroovyPageTypeCheckingExtension
import org.grails.taglib.index.TagLibraryIndex
import spock.lang.Specification

/**
 * With the framework tag libraries on the compile classpath, their compile-time descriptors let a
 * statically compiled GSP be checked against the tags that actually exist, rather than deferring every
 * tag call to runtime dispatch.
 */
class GspStaticTagResolutionSpec extends Specification {

    GroovyPagesTemplateEngine gpte

    def setup() {
        gpte = new GroovyPagesTemplateEngine()
        gpte.afterPropertiesSet()
    }

    void 'the framework tag libraries are visible through their compile-time descriptors'() {
        given:
        TagLibraryIndex index = TagLibraryIndex.load(getClass().classLoader)

        expect:
        index.hasNamespace('g')
        index.lookup('g', 'message') != null
        index.lookup('g', 'link') != null
    }

    void 'a statically compiled page calling a known tag compiles'() {
        given:
        String template = '''<%@ page compileStatic="true" %>${g.message(code: 'some.code')}'''

        when:
        def t = gpte.createTemplate(template, 'known-tag')

        then:
        t.metaInfo.compilationException == null
    }

    void 'an unrecognised tag does not fail the build by default'() {
        given: 'a namespace can hold tag libraries the index never saw, so absence is not a misspelling'
        String template = '''<%@ page compileStatic="true" %>${g.mesage(code: 'typo')}'''

        when:
        def t = gpte.createTemplate(template, 'unknown-tag-lenient')

        then: 'it is reported as a warning and the page still compiles'
        t.metaInfo.compilationException == null
    }

    void 'an unrecognised tag fails compilation under strict checking'() {
        given:
        System.setProperty(GroovyPageTypeCheckingExtension.STRICT_TAG_CHECKING_PROPERTY, 'true')
        String template = '''<%@ page compileStatic="true" %>${g.mesage(code: 'typo')}'''

        when:
        def t = gpte.createTemplate(template, 'unknown-tag-strict')

        then: 'the misspelling is reported when the page is compiled rather than when it renders'
        t.metaInfo.compilationException != null
        t.metaInfo.compilationException.message.contains('No such tag [mesage]')
        t.metaInfo.compilationException.message.contains('namespace [g]')

        cleanup:
        System.clearProperty(GroovyPageTypeCheckingExtension.STRICT_TAG_CHECKING_PROPERTY)
    }

    void 'a tag declared by two tag libraries is never reported as unknown'() {
        given: 'ambiguity means the tag exists but which one runs is decided at runtime'
        System.setProperty(GroovyPageTypeCheckingExtension.STRICT_TAG_CHECKING_PROPERTY, 'true')
        String template = '''<%@ page compileStatic="true" %>${g.link(controller: 'book')}'''

        when:
        def t = gpte.createTemplate(template, 'ambiguous-not-unknown')

        then: 'a resolvable tag still compiles under strict checking'
        t.metaInfo.compilationException == null

        cleanup:
        System.clearProperty(GroovyPageTypeCheckingExtension.STRICT_TAG_CHECKING_PROPERTY)
    }

    void 'a namespace with no compiled tag library still resolves dynamically'() {
        given: 'a namespace the index knows nothing about, as a runtime-registered tag library would be'
        String template = '''<%@ page compileStatic="true" taglibs="somepluginns" %>${somepluginns.anything(a: 1)}'''

        when:
        def t = gpte.createTemplate(template, 'unindexed-namespace')

        then: 'compilation succeeds and the call is left to runtime dispatch'
        t.metaInfo.compilationException == null
    }
}

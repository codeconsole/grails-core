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

import grails.testing.web.taglib.TagLibUnitTest
import org.grails.plugins.web.taglib.FormTagLib
import spock.lang.Specification

/**
 * Tests the rendering modes of {@code g:localeSelect}: the enhanced native {@code <select>}, the
 * framework-agnostic {@code links} render, and the body form that lets a caller supply its own
 * markup (e.g. the Bootstrap dropdown in the create-app layout) while reusing the tag's locale
 * resolution, sorting, autonym labels and active/default detection.
 */
class LocaleSelectRenderingSpec extends Specification implements TagLibUnitTest<FormTagLib> {

    private void publish(List<Locale> locales) {
        request.servletContext.setAttribute('availableLocales', locales)
    }

    void 'body mode renders the body once per locale and exposes the model'() {
        given: 'ASCII autonyms so assertions are not tripped by HTML entity encoding'
        publish([Locale.forLanguageTag('en'), Locale.forLanguageTag('it')])

        when: 'value fixes the current locale so active is deterministic'
        String output = applyTemplate(
                '<g:localeSelect available="true" value="it" var="loc">[${loc.tag}|${loc.autonym}|${loc.active}]</g:localeSelect>')

        then: 'each locale yields a block with BCP-47 tag, autonym label and active flag'
        output.contains('[en|English|false]')
        output.contains('[it|italiano|true]')
    }

    void 'labelType="autonym" labels each option in its own language'() {
        given:
        publish([Locale.forLanguageTag('en'), Locale.forLanguageTag('it')])

        when:
        String output = applyTemplate('<g:localeSelect name="lang" available="true" labelType="autonym"/>')

        then: 'the Italian option reads "italiano" (autonym), not the legacy "it, Italian"'
        output.contains('<select')
        output.contains('italiano')
        !output.contains('it, ')
    }

    void 'type="links" renders anchors with ?lang= hrefs'() {
        given:
        publish([Locale.forLanguageTag('en'), Locale.forLanguageTag('it')])

        when:
        String output = applyTemplate('<g:localeSelect available="true" type="links" labelType="autonym"/>')

        then:
        output.contains('<a href="?lang=en">English</a>')
        output.contains('<a href="?lang=it">italiano</a>')
    }

    void 'tags="true" uses BCP-47 language tags as keys instead of the legacy form'() {
        given:
        publish([Locale.forLanguageTag('en'), Locale.forLanguageTag('pt-BR')])

        when:
        String output = applyTemplate('<g:localeSelect available="true" type="links" tags="true"/>')

        then:
        output.contains('?lang=pt-BR')
        !output.contains('?lang=pt_BR')
    }

    void 'sort orders the locales by their label using a collator'() {
        given: 'insertion order nl, it, en; autonyms Nederlands, italiano, English'
        publish([Locale.forLanguageTag('nl'), Locale.forLanguageTag('it'), Locale.forLanguageTag('en')])

        when:
        String output = applyTemplate('<g:localeSelect available="true" type="links" labelType="autonym" sort="true"/>')

        then: 'a case-insensitive collator yields English, italiano, Nederlands — code-point order would put Nederlands before italiano'
        output.indexOf('English') < output.indexOf('italiano')
        output.indexOf('italiano') < output.indexOf('Nederlands')
    }

    void 'pinDefault emits the default locale first in body mode'() {
        given:
        publish([Locale.forLanguageTag('es'), Locale.forLanguageTag('en'), Locale.forLanguageTag('cs')])

        when: 'no spring.web.locale is configured, so the default falls back to English'
        String output = applyTemplate(
                '<g:localeSelect available="true" pinDefault="true" sort="true" var="loc">[${loc.tag}:${loc.default}]</g:localeSelect>')

        then: 'en is first even though a collator sort would otherwise place čeština first'
        output.contains('[en:true]')
        output.indexOf('[en:true]') < output.indexOf('[cs:false]')
        output.indexOf('[en:true]') < output.indexOf('[es:false]')
    }

    void 'the body form drives the create-app Bootstrap dropdown: pinned default, one divider, active row'() {
        given: 'the resolver publishes an autonym-sorted list; en is the default, it is current'
        publish([Locale.forLanguageTag('en'), Locale.forLanguageTag('it'), Locale.forLanguageTag('nl')])

        when: 'the exact snippet the generated main.gsp layout uses'
        String output = applyTemplate('''
            <ul class="dropdown-menu">
                <g:localeSelect available="true" pinDefault="true" value="it" var="loc">
                    <g:if test="${loc.index == 1}"><li><hr class="dropdown-divider"></li></g:if>
                    <li><a class="dropdown-item${loc.active ? ' active' : ''}" href="?lang=${loc.tag}">${loc.autonym}</a></li>
                </g:localeSelect>
            </ul>
        '''.stripIndent())

        then: 'the default (en) is pinned first, above a single divider'
        output.indexOf('href="?lang=en"') < output.indexOf('dropdown-divider')
        output.count('dropdown-divider') == 1

        and: 'only the current locale (it) row is marked active'
        output.contains('class="dropdown-item active" href="?lang=it"')
        output.contains('class="dropdown-item" href="?lang=en"')

        and: 'the non-default locales follow the divider in the resolver order'
        output.indexOf('dropdown-divider') < output.indexOf('?lang=it')
        output.indexOf('?lang=it') < output.indexOf('?lang=nl')
    }

    void 'default rendering is unchanged: a <select> with the legacy label and key'() {
        given:
        publish([Locale.forLanguageTag('en'), Locale.forLanguageTag('es')])

        when:
        String output = applyTemplate('<g:localeSelect name="lang" available="true"/>')

        then: 'the backwards-compatible select still renders the legacy "language, name" label'
        output.contains('<select')
        output.contains('name="lang"')
        output.contains('value="es"')
        output.contains('es, ')
    }
}

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
package org.apache.grails.scaffolding

import java.nio.charset.StandardCharsets

import spock.lang.Specification

class ScaffoldedPagesSpec extends Specification {

    static final byte[] TEMPLATE = 'show ${className}'.getBytes(StandardCharsets.UTF_8)

    static byte[] bytes(String text) {
        text.getBytes(StandardCharsets.UTF_8)
    }

    static Map<String, Object> model(Map<String, Object> changes = [:]) {
        [className: 'Book', fullName: 'com.example.Book', propertyName: 'book', modelName: 'book',
         packageName: 'com.example', packagePath: 'com/example', simpleName: 'Book', lowerCaseName: 'book'] + changes
    }

    void 'a page is kept under the directory of its domain class, apart from any controller view'() {
        expect:
        ScaffoldedPages.uri(model(), TEMPLATE) ==~ /\/grails-scaffolded\/com\.example\.Book\/[0-9a-f]{32}\.gsp/
    }

    void 'the same template and model always name the same page, whatever order the model is in'() {
        given:
        Map<String, Object> reversed = new LinkedHashMap<>()
        model().keySet().toList().reverse().each { reversed[it] = model()[it] }

        expect:
        ScaffoldedPages.uri(reversed, TEMPLATE) == ScaffoldedPages.uri(model(), TEMPLATE)
    }

    void 'a different template names a different page'() {
        expect:
        ScaffoldedPages.uri(model(), 'show ${className} '.getBytes(StandardCharsets.UTF_8)) != ScaffoldedPages.uri(model(), TEMPLATE)
    }

    void 'a model differing only in #name names a different page'() {
        expect:
        ScaffoldedPages.uri(model((name): 'x'), TEMPLATE) != ScaffoldedPages.uri(model(), TEMPLATE)

        where:
        name << model().keySet().findAll { it != 'fullName' }
    }

    void 'a name added to the model names a different page'() {
        expect:
        ScaffoldedPages.uri(model(extra: ''), TEMPLATE) != ScaffoldedPages.uri(model(), TEMPLATE)
    }

    void 'where one entry ends and the next begins is part of the name'() {
        expect:
        ScaffoldedPages.uri(model(a: 'bc'), TEMPLATE) != ScaffoldedPages.uri(model(ab: 'c'), TEMPLATE)
    }

    void 'a template is expanded with the model'() {
        expect:
        ScaffoldedPages.expand(bytes('list of ${propertyName} for ${className} in ${packageName}'), model()) ==
                'list of book for Book in com.example'
    }

    void 'a template is read as UTF-8 wherever it is expanded'() {
        expect:
        ScaffoldedPages.expand(bytes('Título ${className}'), model()) == 'Título Book'
    }
}

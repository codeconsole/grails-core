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
import spock.lang.TempDir

class ScaffoldedPagesGeneratorSpec extends Specification {

    @TempDir
    File dir

    File templates
    File output

    void setup() {
        templates = new File(dir, 'templates')
        output = new File(dir, 'out')
        template('show', 'show ${className}')
        template('admin/show', 'admin show ${className}')
    }

    void template(String path, String text) {
        File file = new File(templates, "${path}.gsp")
        file.parentFile.mkdirs()
        file.setText(text, 'UTF-8')
    }

    /** Where the resolver looks for the page, which is where the generator must have written it. */
    File page(String templatePath, String domain, String text) {
        Map<String, Object> model = new ScaffoldedPagesGenerator().model(domain).asMap()
        new File(output, ScaffoldedPages.uri(model, text.getBytes(StandardCharsets.UTF_8)).substring(1))
    }

    void 'every template is expanded for every domain class, where the resolver looks for it'() {
        when:
        int written = new ScaffoldedPagesGenerator().generate(templates, ['com.example.Book', 'com.example.Author'], output)

        then:
        written == 4
        page('show', 'com.example.Book', 'show ${className}').getText('UTF-8') == 'show Book'
        page('admin/show', 'com.example.Book', 'admin show ${className}').getText('UTF-8') == 'admin show Book'
        page('show', 'com.example.Author', 'show ${className}').getText('UTF-8') == 'show Author'
        page('admin/show', 'com.example.Author', 'admin show ${className}').getText('UTF-8') == 'admin show Author'
    }

    void 'a template that cannot be expanded is skipped and the others are still written'() {
        given:
        template('broken', 'broken ${noSuchName}')

        when:
        int written = new ScaffoldedPagesGenerator().generate(templates, ['com.example.Book'], output)

        then:
        written == 2
        page('show', 'com.example.Book', 'show ${className}').exists()
        new File(output, 'grails-scaffolded/com.example.Book').list().length == 2
    }

    void 'the command line reads the domain classes from a list'() {
        given:
        File list = new File(dir, 'domains.txt')
        list.setText('com.example.Book\n\n  com.example.Author  \n', 'UTF-8')

        when:
        ScaffoldedPagesGenerator.main(templates.path, list.path, output.path)

        then:
        page('show', 'com.example.Book', 'show ${className}').exists()
        page('show', 'com.example.Author', 'show ${className}').exists()
    }
}

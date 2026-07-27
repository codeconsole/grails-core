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
package org.grails.doc.gradle

import spock.lang.Specification
import spock.lang.TempDir

class GroovydocLinkAuditorSpec extends Specification {

    @TempDir
    File apiDir

    void "reports no violations for clean inner class links"() {
        given:
        writeHtml('index.html', """
            <a href="deprecated-list.html">Deprecated</a>
            <a href="help-doc.html">Help</a>
            <a href='SomePackage.SomeClass.html'>SomeClass</a>
        """)

        expect:
        GroovydocLinkAuditor.findViolations(apiDir).empty
    }

    void "flags an inner class link using a slash-separated path when the dotted file actually exists"() {
        given: 'the real generated file uses the dotted Groovydoc naming convention'
        writeHtml('Query.Order.Direction.html', '<p>Direction</p>')
        and: 'another page links to it using a malformed slash-separated path'
        writeHtml('index.html', "<a href='./Query/Order.Direction.html'>Direction</a>")

        when:
        List<String> violations = GroovydocLinkAuditor.findViolations(apiDir)

        then:
        violations.size() == 1
        violations[0].contains('Query/Order.Direction.html')
    }

    void "does not flag a slash-separated inner class link when no dotted file exists to resolve to"() {
        given: 'the referenced target was never generated, so this cannot be the known malformed-path case'
        writeHtml('index.html', "<a href='./Query/Order.Direction.html'>Direction</a>")

        expect:
        GroovydocLinkAuditor.findViolations(apiDir).empty
    }

    private File writeHtml(String relativePath, String content) {
        File file = new File(apiDir, relativePath)
        file.parentFile.mkdirs()
        file.text = content
        file
    }
}

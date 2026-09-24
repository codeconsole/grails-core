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
package org.grails.io.support

import java.nio.file.Files
import java.nio.file.Path

import javax.xml.parsers.SAXParser

import org.xml.sax.helpers.DefaultHandler

import spock.lang.Specification
import spock.lang.TempDir

/**
 * Asserts the parser hardening applied by {@link SpringIOUtils} through observable parsing
 * behaviour rather than by reading feature flags back off the factory, so the assertions do not
 * depend on how the feature identifiers are spelled.
 */
class SpringIOUtilsSpec extends Specification {

    /** Shape of a JSP 1.2 tag library descriptor, as shipped inside jakarta jstl. */
    private static final String TLD = '''<!DOCTYPE taglib
  PUBLIC "-//Sun Microsystems, Inc.//DTD JSP Tag Library 1.2//EN"
  "http://java.sun.com/dtd/web-jsptaglibrary_1_2.dtd">
<taglib>
  <uri>jakarta.tags.core</uri>
  <tag><name>out</name><tag-class>org.example.OutTag</tag-class></tag>
</taglib>'''

    private static final String SECRET = 'top-secret-token'

    @TempDir
    Path tempDir

    private String externalEntityDocument() {
        Path secret = tempDir.resolve('secret.txt')
        Files.writeString(secret, SECRET)
        """<!DOCTYPE root [
<!ENTITY ext SYSTEM '${secret.toUri().toASCIIString()}'>
]>
<root>&ext;</root>"""
    }

    private static String parseWithSaxParser(SAXParser parser, String xml) {
        StringBuilder text = new StringBuilder()
        parser.parse(new ByteArrayInputStream(xml.getBytes('UTF-8')), new DefaultHandler() {
            @Override
            void characters(char[] chars, int start, int length) {
                text.append(chars, start, length)
            }
        })
        text.toString()
    }

    void 'createXmlSlurper parses a document without a doctype'() {
        when:
        def xml = SpringIOUtils.createXmlSlurper().parseText('<root><child>ok</child></root>')

        then:
        xml.child.text() == 'ok'
    }

    void 'createXmlSlurper parses a descriptor that declares a doctype without retrieving its dtd'() {
        when:
        def parsed = SpringIOUtils.createXmlSlurper().parseText(TLD)

        then:
        parsed.uri.text() == 'jakarta.tags.core'
        parsed.tag.name.text() == 'out'
    }

    void 'createXmlSlurper expands internal entities'() {
        when:
        def parsed = SpringIOUtils.createXmlSlurper().parseText('''<!DOCTYPE root [
<!ENTITY msg "safe">
]>
<root>&msg;</root>''')

        then:
        parsed.text() == 'safe'
    }

    void 'createXmlSlurper does not resolve external general entities'() {
        given: 'a document whose entity points at a readable file on disk'
        String xml = externalEntityDocument()

        when:
        def parsed = SpringIOUtils.createXmlSlurper().parseText(xml)

        then:
        !parsed.text().contains(SECRET)
    }

    void 'createXmlSlurper does not resolve external parameter entities'() {
        given: 'a parameter entity that would pull a file into the internal subset'
        Path secret = tempDir.resolve('secret.dtd')
        Files.writeString(secret, "<!ENTITY leaked '${SECRET}'>")
        String xml = """<!DOCTYPE root [
<!ENTITY % ext SYSTEM '${secret.toUri().toASCIIString()}'>
%ext;
]>
<root>ok</root>"""

        when:
        def parsed = SpringIOUtils.createXmlSlurper().parseText(xml)

        then:
        parsed.text() == 'ok'
    }

    void 'createXmlSlurper skips an external dtd rather than retrieving it'() {
        given: 'a document naming a DTD that does not exist, so retrieval would fail loudly'
        String xml = """<!DOCTYPE root SYSTEM '${tempDir.resolve('missing.dtd').toUri().toASCIIString()}'>
<root>ok</root>"""

        expect:
        SpringIOUtils.createXmlSlurper().parseText(xml).text() == 'ok'
    }

    void 'newSAXParser applies the same entity hardening'() {
        given:
        String xml = externalEntityDocument()

        when:
        String text = parseWithSaxParser(SpringIOUtils.newSAXParser(), xml)

        then:
        !text.contains(SECRET)
    }

    void 'createXmlSlurper is namespace aware'() {
        given:
        String xml = '<t:root xmlns:t="urn:test"><t:child>ok</t:child></t:root>'

        expect:
        SpringIOUtils.createXmlSlurper().parseText(xml).child.text() == 'ok'
    }
}

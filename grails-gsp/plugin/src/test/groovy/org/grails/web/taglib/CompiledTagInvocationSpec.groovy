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
import org.grails.plugins.web.taglib.ApplicationTagLib
import org.grails.taglib.CompiledTagInvocation
import org.grails.taglib.GrailsTagException
import org.grails.taglib.TagLibraryLookup
import spock.lang.Specification

/**
 * A tag whose namespace and name are already known is invoked as an ordinary method call rather than
 * through Groovy's dispatch, and must behave exactly as the dynamic route does.
 */
class CompiledTagInvocationSpec extends Specification implements TagLibUnitTest<ApplicationTagLib> {

    private TagLibraryLookup getLookup() {
        applicationContext.getBean(TagLibraryLookup)
    }

    void 'a tag that writes to the output returns what it wrote'() {
        when:
        Object output = CompiledTagInvocation.invoke(
                lookup, 'g', 'link', [controller: 'book', action: 'show'], null)

        then:
        output.toString() == applyTemplate('<g:link controller="book" action="show"/>')
    }

    void 'a tag called with a body receives it'() {
        given:
        Closure body = { 'inside' }

        when:
        Object output = CompiledTagInvocation.invoke(
                lookup, 'g', 'link', [controller: 'book'], body)

        then:
        output.toString().contains('inside')
    }

    void 'attributes may be omitted'() {
        expect: 'a null attribute map is treated as empty rather than failing'
        CompiledTagInvocation.invoke(lookup, 'g', 'link', null, { 'x' }) != null
    }

    void 'invoking without a tag library lookup is reported clearly'() {
        when:
        CompiledTagInvocation.invoke(null, 'g', 'link', [:], null)

        then:
        GrailsTagException e = thrown()
        e.message.contains('link')
    }
}

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
package org.grails.web.databinding.bindingsource.json

import org.grails.web.databinding.bindingsource.JsonDataBindingSourceCreator
import grails.web.mime.MimeType

import spock.lang.Specification

import java.nio.charset.StandardCharsets

import org.grails.web.databinding.bindingsource.InvalidRequestBodyException

import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.json.JsonMapper

class JsonDataBindingSourceCreatorSpec extends Specification {

    void 'Test JSON parsing'() {
        given:
        def json = '''{
  "category": {"name":"laptop", "shouldBeNull": null, "shouldBeTrue": true, "shouldBeFalse": false, "someNumber": 42},
  "name": "MacBook",
  "languages" : [ {"name": "Groovy", "company": "GoPivotal"}, {"name": "Java", "company": "Oracle"}]
}'''

        def inputStream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))
        def bindingSource = new JsonDataBindingSourceCreator().createDataBindingSource(MimeType.JSON, Object, inputStream)

        when:
        def propertyNames = bindingSource.propertyNames

        then:
        propertyNames.contains 'category'
        propertyNames.contains 'name'
        bindingSource.containsProperty 'category'
        bindingSource.containsProperty 'name'
        bindingSource['name'] == 'MacBook'
        bindingSource['category'] instanceof Map
//        !(bindingSource['category'] instanceof JsonObjectMap)
        bindingSource['category'].size() == 5
        bindingSource['category']['name'] == 'laptop'
        bindingSource['category']['shouldBeTrue'] == true
        bindingSource['category']['shouldBeFalse'] == false
        bindingSource['category']['someNumber'].intValue() == 42
        bindingSource['category']['shouldBeNull'] == null
        bindingSource['languages'] instanceof List
        bindingSource['languages'][0] instanceof Map
//        !(bindingSource['languages'][0] instanceof JsonObjectMap)
        bindingSource['languages'][1] instanceof Map
//        !(bindingSource['languages'][1] instanceof JsonObjectMap)
        bindingSource['languages'][0]['name'] == 'Groovy'
        bindingSource['languages'][0]['company'] == 'GoPivotal'
        bindingSource['languages'][1]['name'] == 'Java'
        bindingSource['languages'][1]['company'] == 'Oracle'
    }

    void 'Test malformed JSON parsing'() {
        given:
        def json = '''{"mapData": {"name":"Jeff{{{'''

        def inputStream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))


        when:
        def bindingSource = new JsonDataBindingSourceCreator().createDataBindingSource(MimeType.JSON, Object, inputStream)

        then:
        thrown InvalidRequestBodyException
    }

    void 'uses an injected Boot JsonMapper for request parsing'() {
        given:
        def jsonMapper = JsonMapper.builder()
                .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                .build()
        def creator = new JsonDataBindingSourceCreator(jsonMapper: jsonMapper)
        def inputStream = new ByteArrayInputStream('{/* configured mapper */"name":"Grails"}'.bytes)

        when:
        def bindingSource = creator.createDataBindingSource(MimeType.JSON, Object, inputStream)

        then:
        bindingSource['name'] == 'Grails'
    }
}

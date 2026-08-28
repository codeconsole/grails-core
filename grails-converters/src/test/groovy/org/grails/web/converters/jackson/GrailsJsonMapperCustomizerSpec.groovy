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
package org.grails.web.converters.jackson

import org.springframework.validation.BeanPropertyBindingResult

import tools.jackson.databind.json.JsonMapper

import spock.lang.Specification

class GrailsJsonMapperCustomizerSpec extends Specification {

    void 'Boot JsonMapper receives the Grails validation errors serializer'() {
        given:
        def builder = JsonMapper.builder()
        new GrailsJsonMapperCustomizer().customize(builder)
        def mapper = builder.build()
        def errors = new BeanPropertyBindingResult(new JsonCommand(), 'command')
        errors.rejectValue('name', 'blank', 'must not be blank')

        expect:
        mapper.readValue(mapper.writeValueAsString(errors), Map) == [
                errors: [[object: 'command', field: 'name', code: 'blank', message: 'must not be blank']]
        ]
    }
}

class JsonCommand {
    String name
}

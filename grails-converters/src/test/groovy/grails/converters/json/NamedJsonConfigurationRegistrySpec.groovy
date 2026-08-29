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
package grails.converters.json

import tools.jackson.core.JacksonException
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.json.JsonMapper

import spock.lang.Specification

class NamedJsonConfigurationRegistrySpec extends Specification {

    void 'named serializers are isolated and support direct string and writer output'() {
        given:
        JsonMapper mapper = JsonMapper.builder().build()
        def registry = new NamedJsonConfigurationRegistry(mapper)
        registry.register('deep') {
            it.serializer(NamedJsonValue, new NamedJsonValueSerializer())
        }
        def value = new NamedJsonValue(name: 'Grails')
        def output = new StringWriter()

        when:
        String named = registry.writeValueAsString('deep', value)
        registry.writeValue('deep', output, value)

        then:
        registry.contains('deep')
        named == '{"configured":"GRAILS"}'
        output.toString() == named
        mapper.writeValueAsString(value) == '{"name":"Grails"}'
    }

    void 'unknown configurations fail clearly'() {
        given:
        def registry = new NamedJsonConfigurationRegistry(JsonMapper.builder().build())

        when:
        registry.writeValueAsString('missing', [:])

        then:
        def error = thrown(IllegalArgumentException)
        error.message == 'Named JSON configuration [missing] is not registered.'
    }
}

class NamedJsonValue {
    String name
}

class NamedJsonValueSerializer extends ValueSerializer<NamedJsonValue> {

    @Override
    void serialize(NamedJsonValue value, JsonGenerator generator, SerializationContext context) throws JacksonException {
        generator.writeStartObject()
        generator.writeStringProperty('configured', value.name.toUpperCase(Locale.ROOT))
        generator.writeEndObject()
    }
}

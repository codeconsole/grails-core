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
package grails.converters

import java.time.Instant

import org.grails.web.converters.configuration.ConvertersConfigurationHolder
import org.grails.web.converters.configuration.ConvertersConfigurationInitializer

import spock.lang.Specification

class JsonCompatibilitySpec extends Specification {

    void setup() {
        new ConvertersConfigurationInitializer().initialize()
    }

    void cleanup() {
        ConvertersConfigurationHolder.clear()
    }

    void 'primitive, temporal, enum, array, collection, map and byte values retain their JSON shape'() {
        given:
        def value = [
                text: 'grails',
                count: 2,
                enabled: true,
                missing: null,
                instant: Instant.parse('2020-01-02T03:04:05Z'),
                mode: JsonCompatibilityMode.FAST,
                array: [1, 2] as int[],
                collection: ['a', 'b'],
                nested: [answer: 42],
                bytes: [65, 66] as byte[],
        ]

        when:
        def parsed = JSON.parse(new JSON(value).toString())

        then:
        parsed.text == 'grails'
        parsed.count == 2
        parsed.enabled
        parsed.missing == null
        parsed.instant == '2020-01-02T03:04:05Z'
        parsed.mode == 'FAST'
        parsed.array == [1, 2]
        parsed.collection == ['a', 'b']
        parsed.nested.answer == 42
        parsed.bytes == [65, 66]
    }

    void 'custom marshaller priority and closure output remain public extension points'() {
        given:
        JSON.registerObjectMarshaller(JsonCompatibilityBean, 100) { JsonCompatibilityBean bean ->
            [renamed: bean.value.toUpperCase(Locale.ROOT)]
        }

        expect:
        JSON.parse(new JSON(new JsonCompatibilityBean(value: 'custom')).toString()) == [renamed: 'CUSTOM']
    }
}

enum JsonCompatibilityMode {
    FAST
}

class JsonCompatibilityBean {
    String value
}

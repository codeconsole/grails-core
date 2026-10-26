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
package grails.config

import org.grails.config.NavigableMap
import spock.lang.Specification
import spock.lang.Unroll
import spock.util.environment.RestoreSystemProperties

@RestoreSystemProperties
class NavigableMapSpec extends Specification {

    void 'merge preserves profile metadata as ordinary map values'() {
        given:
        System.setProperty('spring.profiles.active', 'beta')
        def input = [
                spring: [profiles: [active: 'alpha', include: ['shared']], config: [activate: ['on-profile': 'alpha']]],
                sample: [message: 'original']
        ]
        def output = new NavigableMap()

        when:
        output.merge(input, true)

        then:
        output.get('sample.message') == 'original'
        output.get('spring.profiles.active') == 'alpha'
        output.get('spring.profiles.include') == ['shared']
        output.get('spring.config.activate.on-profile') == 'alpha'
    }

    @Unroll
    def "merge navigable map for #input "(Map input) {

        when:
        Map output = new NavigableMap()
        output.merge(input)

        then:
        output['js'] == 'text/javascript'
        output['json'] == ['application/json', 'text/json']
        output['xml'] == ['application/hal+xml', 'text/xml', 'application/xml']
        output['grails.cors.mappings[/api/**]'] == 'default'

        where:
        input << [
                [js: 'text/javascript', json: ['application/json', 'text/json'], xml: ['application/hal+xml', 'text/xml', 'application/xml'], 'grails.cors.mappings[/api/**]': 'default'],
                [js: 'text/javascript', 'json[0]': 'application/json', 'json[1]': 'text/json', 'xml[0]': 'application/hal+xml', 'xml[1]':'text/xml', 'xml[2]':'application/xml', 'grails.cors.mappings[/api/**]': 'default'],
                ]
    }

    def "multiple subscript entries are collapse to a list of maps"() {
        given:
        Map input = [
                'rabbitmq.connections[0].name': 'main',
                'rabbitmq.connections[0].host': '1109201498',
                'rabbitmq.connections[0].host2': '635494740',
                'rabbitmq.connections[0].username': 'guest',
                'rabbitmq.connections[0].password': 'guest',
        ]

        when:
        Map output = new NavigableMap()
        output.merge(input)

        then:
        output.keySet() as List<String> == ['rabbitmq.connections']
        output['rabbitmq.connections'] instanceof List
        output['rabbitmq.connections'].size() == 1
        output['rabbitmq.connections'][0].name == 'main'
        output['rabbitmq.connections'][0].host == '1109201498'
        output['rabbitmq.connections'][0].host2 == '635494740'
        output['rabbitmq.connections'][0].username == 'guest'
        output['rabbitmq.connections'][0].password == 'guest'
    }

    def "multiple subscript entries are collapse to a map of maps"() {
        given:
        Map input = [
                'rabbitmq.connections[foo].name': 'main',
                'rabbitmq.connections[foo].host': '1109201498',
                'rabbitmq.connections[foo].host2': '635494740',
                'rabbitmq.connections[foo].username': 'guest',
                'rabbitmq.connections[foo].password': 'guest',
        ]

        when:
        Map output = new NavigableMap()
        output.merge(input)

        then:
        output.keySet() as List<String> == ['rabbitmq.connections']
        output['rabbitmq.connections'] instanceof Map
        output['rabbitmq.connections'].size() == 1
        output['rabbitmq.connections']['foo'].name == 'main'
        output['rabbitmq.connections']['foo'].host == '1109201498'
        output['rabbitmq.connections']['foo'].host2 == '635494740'
        output['rabbitmq.connections']['foo'].username == 'guest'
        output['rabbitmq.connections']['foo'].password == 'guest'
    }

    def "an indexed key with a property after the index builds the list in the nested map, written #description"() {
        given:
        NavigableMap output = new NavigableMap()

        when:
        output.merge(input, true)

        then: 'the nested map holds the list, so navigation reads it'
        output.navigate(path as String[]) == [[name: 'row', port: '8080']]

        and: 'the flat key holds the same list'
        output.get(path.join('.')).is(output.navigate(path as String[]))

        where:
        description                  | input                                                                 | path
        'as a dotted key'            | ['a.rows[0].name': 'row', 'a.rows[0].port': '8080']                   | ['a', 'rows']
        'as a dotted key two deep'   | ['a.b.rows[0].name': 'row', 'a.b.rows[0].port': '8080']               | ['a', 'b', 'rows']
    }

    def "a map written by an indexed key with a property after the index keeps its entries when a dotted key is merged after it"() {
        given:
        NavigableMap output = new NavigableMap()

        when:
        output.merge(['a.mappings[foo].name': 'x'], true)
        output.merge(['a.mappings.bar': 'y'], true)

        then: 'the nested map and the flat key hold the same map'
        output.navigate('a', 'mappings') == [foo: [name: 'x'], bar: 'y']
        output.get('a.mappings').is(output.navigate('a', 'mappings'))
    }
}

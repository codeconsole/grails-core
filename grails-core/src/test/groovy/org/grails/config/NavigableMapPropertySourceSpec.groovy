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
package org.grails.config

import org.grails.config.yaml.YamlPropertySourceLoader
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.PropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ByteArrayResource
import spock.lang.Specification

/**
 * @author graemerocher
 */
class NavigableMapPropertySourceSpec extends Specification {

    def "Ensure navigable maps are not returned from a NavigableMapPropertySource when using map syntax"() {
        given:"A navigable map"
            def map = new NavigableMap()
            map.foo = [bar: "myval"]
        when:"A NavigableMapPropertySource is created"
            def ps = new NavigableMapPropertySource("test", map)
        then:"Nulls are returned for submaps"
        map.keySet() == ['foo.bar', 'foo'] as Set
        ps.getPropertyNames() == ['foo.bar'] as String[]
        ps.getNavigablePropertyNames() == ['foo.bar', 'foo'] as String[]
        ps.getProperty('foo') == null
        ps.getNavigableProperty('foo') instanceof NavigableMap

    }
    def "Ensure navigable maps are not returned from a NavigableMapPropertySource when using dot syntax"() {
        given:"A navigable map"
        def map = new NavigableMap()
        map.foo.bar = "myval"
        when:"A NavigableMapPropertySource is created"
        def ps = new NavigableMapPropertySource("test", map)
        then:"Nulls are returned for submaps"
        map.keySet() == ['foo', 'foo.bar' ] as Set
        ps.getPropertyNames() == ['foo.bar'] as String[]
        ps.getNavigablePropertyNames() == ['foo' , 'foo.bar'] as String[]
        ps.getProperty('foo') == null
        ps.getNavigableProperty('foo') instanceof NavigableMap

    }

    def "a list and a map written by indexed keys are bound as Spring Boot binds them from application.yml"() {
        given: "a list and a map written by indexed keys, and the same list and map in application.yml"
        def map = new NavigableMap()
        map.merge(['app.rows[0].name': 'first', 'app.rows[0].db.url': 'jdbc:x', 'app.rows[1].name': 'second', 'app.mappings[foo].name': 'x'], true)
        def yaml = "app:\n  rows:\n    - name: first\n      db:\n        url: jdbc:x\n    - name: second\n  mappings:\n    foo:\n      name: x\n"

        when:
        def ps = new NavigableMapPropertySource("test", map)
        def fromKeys = binderOf(ps)
        def fromYaml = binderOf(new YamlPropertySourceLoader().load('application.yml', new ByteArrayResource(yaml.bytes)).first())

        then: "the map is a nested map, so it is not a property itself, as for any nested map"
        !ps.getPropertyNames().contains('app.mappings')
        ps.getProperty('app.mappings') == null

        and: "both are bound as they are from application.yml"
        fromKeys.bind('app.rows', Bindable.listOf(Map)).get() == fromYaml.bind('app.rows', Bindable.listOf(Map)).get()
        fromKeys.bind('app.mappings', Bindable.mapOf(String, Object)).get() == [foo: [name: 'x']]
        fromYaml.bind('app.mappings', Bindable.mapOf(String, Object)).get() == [foo: [name: 'x']]
    }

    private static Binder binderOf(PropertySource source) {
        def environment = new StandardEnvironment()
        environment.propertySources.addFirst(source)
        Binder.get(environment)
    }
}

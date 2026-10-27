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
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.io.ByteArrayResource
import spock.lang.Ignore
import spock.lang.Issue
import spock.lang.Specification

import jakarta.persistence.FlushModeType

/**
 * @author graemerocher
 */
class PropertySourcesConfigSpec extends Specification {

    void "Test that PropertySourcesConfig works as expected"() {
        given:"A PropertySourcesConfig instance"
            def propertySource = new MapPropertySource("foo", [one:1, two:2, 'flush.mode': 'commit', 'three.four': 34, 'empty.value':null])
            def propertySources = new MutablePropertySources()
            propertySources.addLast(propertySource)
            def config = new PropertySourcesConfig(propertySources)

        expect:"The config to be accessible"
            config.one == 1
            config.two == 2
            config.three.four == 34
            !config.four.five
            config.getProperty('one', String) == '1'
            config.getProperty('three.four', String) == '34'
            config.getProperty('three', String) == null
            config.get('three.four') == 34
            config.getProperty('three.four') == '34'
            config.getProperty('three.four', Date) == null
            config.getProperty('flush.mode', FlushModeType) == FlushModeType.COMMIT
            !config.empty.value
    }

    @Issue("https://github.com/apache/grails-spring-security-core/issues/724")
    void "Test accessing a NavigableMap property as Map class"() {
        given:
        def source = new NavigableMap()
        source.merge(["grails": ["plugin": ["springsecurity": ["userLookup": ["usernamePropertyName": "username"]]]]])
        def propertySource = new MapPropertySource("springsecurity", source)
        def propertySources = new MutablePropertySources()
        propertySources.addLast(propertySource)
        def config = new PropertySourcesConfig(propertySources)

        expect:
        config.getProperty("grails.plugin.springsecurity", ConfigObject.class)
    }

    void "Test accessing a NavigableMap with nested NavigableMap property as Map class"() {
        given:
        def source = new NavigableMap()
        def chainMap = new NavigableMap()
        chainMap.merge(["chainMap" :[
                [pattern: '/assets/**', filters: 'none'],
                [pattern: '/**/js/**', filters: 'none'],
                [pattern: '/**/css/**', filters: 'none'],
                [pattern: '/**/images/**', filters: 'none'],
                [pattern: '/**/favicon.ico', filters: 'none'],
                [pattern: '/**', filters: 'JOINED_FILTERS']
        ]])
        source.merge(["grails": ["plugin": ["springsecurity": ["filterChain": chainMap, "userLookup": ["usernamePropertyName": "username"]]]]])
        def propertySource = new MapPropertySource("springsecurity", source)
        def propertySources = new MutablePropertySources()
        propertySources.addLast(propertySource)
        def config = new PropertySourcesConfig(propertySources)

        when:
        def securityConfig = config.getProperty("grails.plugin.springsecurity", ConfigObject.class)

        then:
        !(securityConfig.userLookup instanceof NavigableMap)
        !(securityConfig.filterChain.chainMap instanceof NavigableMap)
    }

    /*

      We need to settle on whether the following is a bug or not.
      There are some tests in ConfigMapSpec that indirectly assert some
      behavior that I think would be inconsistent with making the following
      test pass.

     */
    @Ignore("Cannot set property 'bar' on null object")
    @Issue('apache/grails-core#10188')
    void 'test replacing nested property values'() {
        given: 'a PropertySourcesConfig'
        def cfg = new PropertySourcesConfig()

        when: 'a nested property is assigned a value'
        cfg.foo.bar = ['one': '1']

        and: 'later is assigned a different value'
        cfg.foo.bar = ['two': '2']

        then: 'the second value is not merged with the first value'
        cfg.foo.bar == ['two': '2']
    }

    void 'a list merged as indexed keys with a property after the index is read through the nested maps'() {
        given: "a config"
        def config = new PropertySourcesConfig()

        when: "a list of maps is merged as the indexed keys a flattened application.yml holds"
        config.merge(['app.rows[0].name': 'first', 'app.rows[0].port': '8080', 'app.rows[1].name': 'second'])

        then: "the nested maps hold the list, so every read of it agrees"
        config.navigate('app', 'rows') == [[name: 'first', port: '8080'], [name: 'second']]
        config.getProperty('app.rows', List) == [[name: 'first', port: '8080'], [name: 'second']]
        config.getProperty('app.rows[0].name') == 'first'
        config.getProperty('app', Map).rows == [[name: 'first', port: '8080'], [name: 'second']]
    }

    void 'an element of a list written by indexed keys is read through dot notation as deep as it goes'() {
        given: "a list of maps from a source that holds indexed keys, as application.properties and system properties do"
        def sources = new MutablePropertySources()
        sources.addFirst(new MapPropertySource('application.properties', [
                'app.rows[0].name': 'first', 'app.rows[0].db.url': 'jdbc:x', 'app.rows[1].name': 'second',
        ]))
        def config = new PropertySourcesConfig(sources)

        expect: "a property two levels into an element is read, and a miss inside one is null-safe"
        config.app.rows[0].name == 'first'
        config.app.rows[0].db.url == 'jdbc:x'
        (config.app.rows[0].db.url ?: 'default') == 'jdbc:x'
        (config.app.rows[1].missing.deeper ?: 'default') == 'default'
        config.navigate('app', 'rows')*.name == ['first', 'second']
    }

    void 'a map written by indexed keys is read through dot notation, and a key it does not hold is null-safe'() {
        given:
        def config = new PropertySourcesConfig()

        when:
        config.merge(['app.mappings[foo].name': 'x'])

        then:
        config.app.mappings.foo.name == 'x'
        (config.app.mappings.bar.deeper ?: 'default') == 'default'

        when: "a dotted key under the same map is merged after it"
        config.merge(['app.mappings.bar': 'y'])

        then: "the map holds both, read through the nested maps or the flat key"
        config.navigate('app', 'mappings') == [foo: [name: 'x'], bar: 'y']
        config['app.mappings'] == [foo: [name: 'x'], bar: 'y']
    }

    void 'a list replaced after an indexed key wrote it keeps none of its old elements'() {
        given:
        def config = new PropertySourcesConfig()

        when: "a list is written by an indexed key, replaced, and then written to by an indexed key again"
        config.merge(['app.rows[0].name': 'old'])
        config.merge(['app.rows': [[name: 'new']]])
        config.merge(['app.rows[0].port': '8080'])

        then: "every read gives the element of the list that replaced it"
        config.navigate('app', 'rows') == [[name: 'new', port: '8080']]
        config.getProperty('app.rows', List) == [[name: 'new', port: '8080']]
        config['app.rows'] == [[name: 'new', port: '8080']]
        config.getProperty('app.rows[0].name') == 'new'
        config.app.rows[0].name == 'new'
    }

    void 'a list of maps is given as plain maps by getProperty and read through dot notation, from #description'() {
        given:
        def config = new PropertySourcesConfig(source)

        expect: "getProperty gives the maps as plain maps, as it gives a map"
        List rows = config.getProperty('app.rows', List)
        rows*.name == ['first', 'second']
        rows.every { !(it instanceof NavigableMap) }

        and: "dot notation reads into an element, and a miss inside one is null-safe"
        config.app.rows[0].db.url == 'jdbc:x'
        (config.app.rows[1].missing.deeper ?: 'default') == 'default'

        where:
        description         | source
        'indexed keys'      | new MapPropertySource('application.properties', ['app.rows[0].name': 'first', 'app.rows[0].db.url': 'jdbc:x', 'app.rows[1].name': 'second'])
        'application.yml'   | new YamlPropertySourceLoader().load('application.yml', new ByteArrayResource("app:\n  rows:\n    - name: first\n      db:\n        url: jdbc:x\n    - name: second\n".bytes)).first()
    }

    void 'a list of maps written by indexed keys gives the same list as application.yml'() {
        given:
        def fromKeys = new PropertySourcesConfig(new MapPropertySource('application.properties', [
                'app.rows[0].name': 'first', 'app.rows[0].db.url': 'jdbc:x', 'app.rows[1].name': 'second',
        ]))
        def fromYaml = new PropertySourcesConfig(new YamlPropertySourceLoader().load('application.yml',
                new ByteArrayResource("app:\n  rows:\n    - name: first\n      db:\n        url: jdbc:x\n    - name: second\n".bytes)).first())

        expect:
        fromKeys.getProperty('app.rows', List) == fromYaml.getProperty('app.rows', List)
        fromKeys.getProperty('app.rows', List)[0] == [name: 'first', db: [url: 'jdbc:x'], 'db.url': 'jdbc:x']
        fromKeys.navigate('app', 'rows') == fromYaml.navigate('app', 'rows')
        fromKeys['app.rows'] == fromYaml['app.rows']
    }
}

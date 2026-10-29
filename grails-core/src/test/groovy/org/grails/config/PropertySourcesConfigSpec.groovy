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
import org.springframework.core.env.PropertySource
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

        then: "the map holds both"
        config.navigate('app', 'mappings') == [foo: [name: 'x'], bar: 'y']
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

    void 'an element of a list in application.yml is read by its indexed key'() {
        given:
        def config = new PropertySourcesConfig(new YamlPropertySourceLoader().load('application.yml',
                new ByteArrayResource("app:\n  rows:\n    - name: first\n      db:\n        url: jdbc:x\n".bytes)).first())

        expect:
        config.getProperty('app.rows[0].name') == 'first'
        config.getProperty('app.rows[0].db.url') == 'jdbc:x'
        config.app.rows[0].db.url == 'jdbc:x'
    }

    void 'a list in a source of higher precedence is merged over the list below it as before, also on refresh, #description'() {
        given: "application-dev.yml over application.yml"
        def sources = new MutablePropertySources()
        sources.addLast(new YamlPropertySourceLoader().load('application-dev.yml', new ByteArrayResource(higher.bytes)).first())
        sources.addLast(new YamlPropertySourceLoader().load('application.yml', new ByteArrayResource(lower.bytes)).first())
        def config = new PropertySourcesConfig(sources)

        expect:
        config.navigate('app', name) == merged
        config.getProperty("app.${name}".toString(), List) == merged

        when:
        config.refresh()

        then:
        config.navigate('app', name) == merged

        where:
        description        | name   | higher                             | lower                                                     | merged
        'a list of maps'   | 'rows' | "app:\n  rows:\n    - name: dev\n" | "app:\n  rows:\n    - name: first\n    - name: second\n" | [[name: 'dev'], [name: 'second']]
        'a list of values' | 'tags' | "app:\n  tags:\n    - dev\n"       | "app:\n  tags:\n    - first\n    - second\n"             | ['dev']
    }

    void 'a placeholder in an indexed key with a property after the index is resolved alike for every read of it, #description'() {
        given: "a property source that holds the value of the placeholder"
        def sources = new MutablePropertySources()
        sources.addFirst(new MapPropertySource('env', [x: 'resolved']))
        def config = new PropertySourcesConfig(sources)

        when:
        merges.each { Map merged -> config.merge(merged) }

        then: "the indexed key, the list, the nested maps and the flat keys agree"
        config.getProperty('app.rows[0].name') == 'resolved'
        config.getProperty('app.rows', List)[0].name == 'resolved'
        config.navigate('app', 'rows')[0].name == 'resolved'
        config['app.rows[0].name'] == 'resolved'
        config['app.rows[0]'].is(config.navigate('app', 'rows')[0])

        where:
        description                   | merges
        'alone'                       | [['app.rows[0].name': '${x}']]
        'beside other fields'         | [['app.rows[0].name': '${x}', 'app.rows[0].port': '8080', 'app.rows[0].db.url': 'jdbc:x']]
        'after the list was replaced' | [['app.rows[0].name': 'old'], ['app.rows': [[name: 'new']]], ['app.rows[0].name': '${x}']]
    }

    void 'a placeholder in a dotted property after a map subscript is resolved alike for every read of it'() {
        given: "a property source that holds the value of the placeholder, and a nested map the subscript names"
        def sources = new MutablePropertySources()
        sources.addFirst(new MapPropertySource('env', [x: 'resolved']))
        def config = new PropertySourcesConfig(sources)
        config.merge(['a.b.c.d': 'old'])

        when:
        config.merge(['a[b].c.d': '${x}'])

        then:
        config.navigate('a', 'b', 'c', 'd') == 'resolved'
        config.getProperty('a.b.c.d') == 'resolved'
        config['a.b.c.d'] == 'resolved'
        config.getProperty('a[b].c.d') == 'resolved'
    }

    void 'a list written by indexed keys is merged into another config as a list'() {
        given: "a list written by indexed keys"
        def config = new PropertySourcesConfig()
        config.merge(['app.rows[0].name': 'first', 'app.rows[1].name': 'second'])

        when: "the list is merged into another config as the value of a nested map"
        def other = new PropertySourcesConfig()
        other.merge([app: [rows: config.app.rows]])

        then:
        other.navigate('app', 'rows')*.name == ['first', 'second']
        other.getProperty('app.rows', List)*.name == ['first', 'second']
        other.app.rows[1].name == 'second'
    }

    void 'an indexed key over a list that a profile replaced keeps the rows below it'() {
        given: "application-dev.yml over application.yml, and an indexed key from a source above them"
        def sources = new MutablePropertySources()
        sources.addFirst(new YamlPropertySourceLoader().load('application.yml', new ByteArrayResource("app:\n  rows:\n    - name: first\n    - name: second\n".bytes)).first())
        sources.addFirst(new YamlPropertySourceLoader().load('application-dev.yml', new ByteArrayResource("app:\n  rows:\n    - name: dev\n".bytes)).first())
        sources.addFirst(new MapPropertySource('commandLineArgs', ['app.rows[0].port': '9']))

        when:
        def config = new PropertySourcesConfig(sources)

        then: "the list keeps the row the profile does not replace, as before"
        config.navigate('app', 'rows') == [[name: 'dev', port: '9'], [name: 'second']]
        config.getProperty('app.rows', List) == [[name: 'dev', port: '9'], [name: 'second']]
        config.getProperty('app.rows[1].name') == 'second'

        when:
        config.refresh()

        then:
        config.navigate('app', 'rows') == [[name: 'dev', port: '9'], [name: 'second']]
    }

    void 'indexed keys with a property after the index replace values of a list that are not maps'() {
        given:
        def config = new PropertySourcesConfig()
        config.merge(['app.rows': ['one', 'two']])

        when:
        config.merge(['app.rows[0].name': 'first', 'app.rows[1].name': 'second'])

        then: "the list, the indexed keys and dot notation read the maps"
        config.navigate('app', 'rows') == [[name: 'first'], [name: 'second']]
        config.getProperty('app.rows', List) == [[name: 'first'], [name: 'second']]
        config.getProperty('app.rows[1].name') == 'second'
        config.app.rows[1].name == 'second'
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
    }

    void 'a placeholder in a value merged #description is resolved alike for the flat key and the nested entry'() {
        given: "a property source that answers a new value each time it is read, and a section the config already holds"
        def counter = new CountingPropertySource()
        def config = new PropertySourcesConfig(counter)
        config.merge([app: [name: 'demo']])

        when: "a value holding a placeholder is merged into the section"
        config.merge(merged)

        then: "the dotted key, the nested map and the export all read one resolved value"
        String resolved = config.navigate('app', 'url')
        resolved ==~ /http:\/\/host\d+/
        config.toProperties().getProperty('app.url') == resolved
        config['app.url'] == resolved
        config.getProperty('app.url') == resolved

        where:
        description       | merged
        'in a nested map' | [app: [url: 'http://${host}']]
        'as a dotted key' | ['app.url': 'http://${host}']
    }

    void 'a placeholder in a value merged in a nested map is resolved once'() {
        given:
        def counter = new CountingPropertySource()
        def config = new PropertySourcesConfig(counter)

        when:
        config.merge([app: [url: 'http://${host}']])

        then:
        config['app.url'] == 'http://host1'
        config.navigate('app', 'url') == 'http://host1'
        counter.readCount() == 1
    }

    void 'a dotted key inside a nested map is exported under its full path and overwrites no other property'() {
        given: "a map holding a property and, in a nested map, a dotted key that alone names the same property"
        def config = new PropertySourcesConfig([a: [b: 'root'], x: ['a.b': 'nested'], logging: [level: ['org.springframework': 'DEBUG']]])

        expect: "each property is read and exported under its own name"
        config['a.b'] == 'root'
        config.getProperty('a.b') == 'root'
        config.getProperty('x.a.b') == 'nested'
        config.getProperty('logging.level.org.springframework') == 'DEBUG'
        config.toProperties().getProperty('a.b') == 'root'
        config.toProperties().getProperty('x.a.b') == 'nested'
        config.toProperties().getProperty('logging.level.org.springframework') == 'DEBUG'

        and: "no property is written under the dotted key alone"
        config.getProperty('org.springframework') == null
        !config.toProperties().containsKey('org.springframework')
    }

    void 'a dotted key inside a nested map writes no property that the nested maps do not hold'() {
        given: "a map with no b of its own, and a nested map holding a dotted key that alone names a.b"
        def config = new PropertySourcesConfig([a: [c: '1'], x: ['a.b': 'nested']])

        expect: "a.b is absent, and the dotted key is read under its full path"
        config.getProperty('a.b') == null
        !config.toProperties().containsKey('a.b')
        config.navigate('a', 'b') == null
        config.getProperty('x.a.b') == 'nested'
        config.getProperty('a.c') == '1'
    }

    void 'a placeholder in a dotted key inside a nested map is read alike for the flat key and the nested entry'() {
        given: "a property source that answers a new value each time it is read"
        def config = new PropertySourcesConfig(new CountingPropertySource())

        when: "a nested map holding a dotted key with a placeholder is merged"
        config.merge([grails: ['gorm.url': 'http://${host}']])

        then: "the dotted key, the nested map and the export all read one resolved value"
        String resolved = config.navigate('grails', 'gorm', 'url')
        resolved ==~ /http:\/\/host\d+/
        config['grails.gorm.url'] == resolved
        config.getProperty('grails.gorm.url') == resolved
        config.toProperties().getProperty('grails.gorm.url') == resolved

        and: "no property is written under the dotted key alone"
        config.getProperty('gorm.url') == null
        !config.containsKey('gorm.url')
    }

    static class CountingPropertySource extends PropertySource<Object> {

        private int reads

        CountingPropertySource() {
            super('counting', new Object())
        }

        @Override
        Object getProperty(String name) {
            name == 'host' ? "host${++reads}".toString() : null
        }

        int readCount() {
            reads
        }
    }
}

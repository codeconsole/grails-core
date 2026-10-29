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
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertySource
import org.springframework.core.env.StandardEnvironment
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

    void 'a map written by map subscripts is exported by its bracketed keys only, which Spring Boot binds as written'() {
        given: "a map written by map subscripts, as application.yml writes '[/api/**]'"
        def config = new PropertySourcesConfig(new MapPropertySource('yaml', [
                'app.routes[/api/**]': 'x', 'app.routes[/v1.0/**]': 'y', 'app.routes[k]': 'z',
                'app.cors[/admin/**].allowedOrigins[0]': 'https://a.example.com',
        ]))

        when:
        Properties exported = config.toProperties()

        then: "no entry is exported a second time by a dotted name"
        exported.stringPropertyNames().findAll { it.startsWith('app.routes') } as Set == ['app.routes[/api/**]', 'app.routes[/v1.0/**]', 'app.routes[k]'] as Set
        !exported.stringPropertyNames().any { it.startsWith('app.cors./') }

        and: "Spring Boot binds the exported map with the keys the file wrote"
        def published = new StandardEnvironment()
        published.propertySources.addFirst(new MapPropertySource('published', exported as Map<String, Object>))
        Binder.get(published).bind('app.routes', Bindable.mapOf(String, String)).get() == ['/api/**': 'x', '/v1.0/**': 'y', k: 'z']
        Binder.get(published).bind('app.cors', Bindable.mapOf(String, Object)).get().keySet() == ['/admin/**'] as Set
    }

    void 'a map put as a value is exported by the dotted names of its entries'() {
        given:
        def config = new PropertySourcesConfig()
        config.put('plain', [nested: 'value'])

        expect:
        config.toProperties().getProperty('plain.nested') == 'value'
    }

    void 'a map written by map subscripts is exported once whatever precedes it in application.yml, #description'() {
        given: "application.yml as Grails loads it, which keeps the order of the file"
        def config = new PropertySourcesConfig(new YamlPropertySourceLoader().load('application.yml', new ByteArrayResource(yaml.bytes)).first())

        when:
        def published = new StandardEnvironment()
        published.propertySources.addFirst(new MapPropertySource('published', config.toProperties() as Map<String, Object>))

        then:
        !config.toProperties().stringPropertyNames().any { it.contains('./') }
        Binder.get(published).bind(prefix, Bindable.mapOf(String, Object)).get() == bound

        where:
        description                          | yaml                                                                         | prefix        | bound
        'a plain key before a bracketed one' | "app:\n  routes:\n    default: z\n    '[/api/**]': x\n"                       | 'app.routes'  | [default: 'z', '/api/**': 'x']
        'a subscript after a subscript'      | "app:\n  tenants:\n    '[acme]':\n      routes:\n        '[/api/**]': x\n" | 'app.tenants' | [acme: [routes: ['/api/**': 'x']]]
    }

    void 'Spring Boot binds every field of an entry written in brackets that a later merge changed, #description'() {
        given:
        def config = new PropertySourcesConfig()
        merges.each { Map merged -> config.merge(merged) }

        when:
        def published = new StandardEnvironment()
        published.propertySources.addFirst(new MapPropertySource('published', config.toProperties() as Map<String, Object>))

        then:
        Binder.get(published).bind(prefix, Bindable.mapOf(String, Object)).get() == bound

        where:
        description                                      | merges                                                                         | prefix       | bound
        'a field merged after a map written in brackets' | [['routes[api]': [target: 'http://localhost'], 'routes[api].enabled': 'true']] | 'routes'     | [api: [target: 'http://localhost', enabled: 'true']]
        'a dotted key merged after a null in brackets'   | [['app.routes[k]': null], ['app.routes.k': 'new']]                             | 'app.routes' | [k: 'new']
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

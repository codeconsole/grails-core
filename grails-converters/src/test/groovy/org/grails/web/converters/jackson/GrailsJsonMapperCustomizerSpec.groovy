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

import grails.core.DefaultGrailsApplication
import grails.core.support.proxy.DefaultProxyHandler
import grails.core.support.proxy.ProxyHandler
import grails.persistence.Entity
import org.grails.datastore.mapping.keyvalue.mapping.config.KeyValueMappingContext
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.validation.BeanPropertyBindingResult

import tools.jackson.databind.json.JsonMapper

import spock.lang.Specification

class GrailsJsonMapperCustomizerSpec extends Specification {

    void 'Boot JsonMapper receives the Grails validation errors serializer'() {
        given:
        def errors = new BeanPropertyBindingResult(new JsonCommand(), 'command')
        errors.rejectValue('name', 'blank', 'must not be blank')
        def context = new AnnotationConfigApplicationContext()
        context.registerBean(GrailsJsonMapperCustomizer)
        context.register(JacksonAutoConfiguration)
        context.refresh()

        expect:
        def mapper = context.getBean(JsonMapper)
        mapper.readValue(mapper.writeValueAsString(errors), Map) == [
                errors: [[object: 'command', field: 'name', code: 'blank', message: 'must not be blank']]
        ]

        cleanup:
        context.close()
    }

    void 'Boot JsonMapper uses persistent metadata and the configured identity policy for domain objects'() {
        given:
        def mapper = domainMapper(true, true)
        def author = new JacksonAuthor(name: 'Douglas').tap { id = 2 }
        def book = new JacksonBook(title: 'Mostly Harmless', authors: [author], authorsByName: [douglas: author]).tap {
            id = 1
            version = 3
        }

        expect:
        mapper.readValue(mapper.writeValueAsString(book), Map) == [
                class: JacksonBook.name,
                id: 1,
                version: 3,
                title: 'Mostly Harmless',
                authors: [[class: JacksonAuthor.name, id: 2]],
                authorsByName: [douglas: [class: JacksonAuthor.name, id: 2]],
        ]
    }

    void 'Boot JsonMapper unwraps domain proxies before reading persistent properties'() {
        given:
        def target = new JacksonBook(title: 'Unwrapped').tap { id = 1 }
        def proxy = new JacksonBookProxy(target: target)
        def proxyHandler = Stub(ProxyHandler) {
            isProxy(proxy) >> true
            unwrapIfProxy(_ as Object) >> { arguments -> arguments[0].is(proxy) ? target : arguments[0] }
        }
        def mapper = domainMapper(false, false, proxyHandler)

        expect:
        mapper.readValue(mapper.writeValueAsString(proxy), Map) == [
                id: 1, title: 'Unwrapped', authors: null, authorsByName: null,
        ]
    }

    void 'writer attributes apply includes and excludes to one domain write'() {
        given:
        def mapper = domainMapper(false, false)
        def book = new JacksonBook(title: 'Filtered').tap {
            id = 1
            version = 3
        }

        expect:
        mapper.writer()
                .withAttribute(GrailsJsonMapperCustomizer.INCLUDES_ATTRIBUTE, [(JacksonBook): ['id', 'title']])
                .withAttribute(GrailsJsonMapperCustomizer.EXCLUDES_ATTRIBUTE, [(JacksonBook): ['title']])
                .writeValueAsString(book) == '{"id":1}'
        mapper.readValue(mapper.writeValueAsString(book), Map) == [
                id: 1, title: 'Filtered', authors: null, authorsByName: null,
        ]
    }

    private JsonMapper domainMapper(boolean includeVersion, boolean includeClass, ProxyHandler proxyHandler = null) {
        def mappingContext = new KeyValueMappingContext('jackson')
        mappingContext.addPersistentEntities(JacksonBook, JacksonAuthor)
        def application = new DefaultGrailsApplication(JacksonBook, JacksonAuthor)
        application.mappingContext = mappingContext
        application.config.setAt('grails.converters.domain.include.version', includeVersion)
        application.config.setAt('grails.converters.domain.include.class', includeClass)
        def builder = JsonMapper.builder()
        new GrailsJsonMapperCustomizer(application, proxyHandler ?: new DefaultProxyHandler()).customize(builder)
        builder.build()
    }
}

class JsonCommand {
    String name
}

@Entity
class JacksonBook {
    static hasMany = [authors: JacksonAuthor, authorsByName: JacksonAuthor]

    Long id
    Long version
    String title
    List<JacksonAuthor> authors
    Map<String, JacksonAuthor> authorsByName
}

@Entity
class JacksonAuthor {
    Long id
    Long version
    String name
}

class JacksonBookProxy extends JacksonBook {
    JacksonBook target
}

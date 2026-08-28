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
package org.grails.plugins.web.rest.render.json

import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter

import grails.core.DefaultGrailsApplication
import grails.util.GrailsWebMockUtil
import org.grails.plugins.web.rest.render.ServletRenderContext
import org.grails.web.converters.configuration.ConvertersConfigurationHolder
import org.grails.web.converters.configuration.ConvertersConfigurationInitializer

import spock.lang.Specification

class DefaultJsonRendererSpec extends Specification {

    void setup() {
        new ConvertersConfigurationInitializer(grailsApplication: new DefaultGrailsApplication()).initialize()
    }

    void cleanup() {
        ConvertersConfigurationHolder.clear()
    }

    void 'selects the first MVC converter that can write the negotiated type'() {
        given:
        def first = Mock(HttpMessageConverter)
        def selected = Mock(HttpMessageConverter)
        def renderer = new DefaultJsonRenderer<Map>(Map)
        renderer.useSpringJson = true
        renderer.springHttpMessageConverters = [first, selected]
        def webRequest = GrailsWebMockUtil.bindMockWebRequest()

        when:
        renderer.render([title: 'Spring'], new ServletRenderContext(webRequest))

        then:
        1 * first.canWrite(LinkedHashMap, MediaType.APPLICATION_JSON) >> false
        1 * selected.canWrite(LinkedHashMap, MediaType.APPLICATION_JSON) >> true
        1 * selected.write(_, MediaType.APPLICATION_JSON, _) >> { arguments ->
            arguments[2].body.write('{"title":"MVC"}'.bytes)
        }
        webRequest.response.contentAsString == '{"title":"MVC"}'
    }

    void 'falls back to the legacy converter when MVC cannot write the response'() {
        given:
        def converter = Mock(HttpMessageConverter)
        def renderer = new DefaultJsonRenderer<Map>(Map)
        renderer.useSpringJson = true
        renderer.springHttpMessageConverters = [converter]
        def webRequest = GrailsWebMockUtil.bindMockWebRequest()

        when:
        renderer.render([title: 'Legacy'], new ServletRenderContext(webRequest))

        then:
        1 * converter.canWrite(LinkedHashMap, MediaType.APPLICATION_JSON) >> false
        0 * converter.write(_, _, _)
        webRequest.response.contentAsString == '{"title":"Legacy"}'
    }

    void 'per-response projections retain the legacy converter path'() {
        given:
        def converter = Mock(HttpMessageConverter)
        def renderer = new DefaultJsonRenderer<ProjectionBody>(ProjectionBody)
        renderer.useSpringJson = true
        renderer.springHttpMessageConverters = [converter]
        def webRequest = GrailsWebMockUtil.bindMockWebRequest()

        when:
        renderer.render(new ProjectionBody(title: 'Included', hidden: true),
                new ServletRenderContext(webRequest, [includes: ['title']]))

        then:
        0 * converter._
        webRequest.response.contentAsString == '{"title":"Included"}'
    }
}

class ProjectionBody {
    String title
    boolean hidden
}

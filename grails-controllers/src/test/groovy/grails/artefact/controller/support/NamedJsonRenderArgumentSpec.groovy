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
package grails.artefact.controller.support

import grails.artefact.Controller
import grails.util.GrailsWebMockUtil
import grails.web.render.NamedJsonRenderer

import spock.lang.Specification

class NamedJsonRenderArgumentSpec extends Specification {

    def webRequest = GrailsWebMockUtil.bindMockWebRequest()

    void 'render json: selects a named configuration'() {
        given:
        def renderer = Mock(NamedJsonRenderer)
        def controller = new NamedJsonArgumentController(namedJsonRenderer: renderer)

        when:
        controller.render(json: [title: 'Grails'], jsonConfiguration: 'deep')

        then:
        1 * renderer.contains('deep') >> true
        1 * renderer.render('deep', [title: 'Grails'], _, null, null) >> { arguments ->
            arguments[2].write('{"title":"Grails"}')
        }
        webRequest.response.contentAsString == '{"title":"Grails"}'
        !webRequest.renderView
    }

    void 'render json: carries a projection through'() {
        given:
        def renderer = Mock(NamedJsonRenderer)
        def controller = new NamedJsonArgumentController(namedJsonRenderer: renderer)

        when:
        controller.render(json: [title: 'Grails'], jsonConfiguration: 'deep', includes: ['title'])

        then:
        1 * renderer.contains('deep') >> true
        1 * renderer.render('deep', [title: 'Grails'], _, ['title'], null) >> { arguments ->
            arguments[2].write('{"title":"Grails"}')
        }
    }

    void 'render json: without a configuration says which argument is missing'() {
        when:
        new NamedJsonArgumentController().render(json: [title: 'Grails'])

        then:
        IllegalArgumentException e = thrown()
        e.message.contains('jsonConfiguration')
    }

    void 'render json: names an unregistered configuration'() {
        given:
        def renderer = Mock(NamedJsonRenderer)
        def controller = new NamedJsonArgumentController(namedJsonRenderer: renderer)

        when:
        controller.render(json: [title: 'Grails'], jsonConfiguration: 'absent')

        then:
        1 * renderer.contains('absent') >> false
        IllegalArgumentException e = thrown()
        e.message.contains('absent')
    }

    void 'an ordinary render argument map is untouched by the json branch'() {
        when: "a map with no json key, which must not be captured by this branch"
        new NamedJsonArgumentController().render(text: 'plain', contentType: 'text/plain')

        then:
        webRequest.response.contentAsString == 'plain'
    }
}

class NamedJsonArgumentController implements Controller {
}

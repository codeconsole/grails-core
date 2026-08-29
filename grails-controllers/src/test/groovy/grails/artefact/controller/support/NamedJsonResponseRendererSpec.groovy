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

class NamedJsonResponseRendererSpec extends Specification {

    void 'render selects a named JSON configuration through its public controller API'() {
        given:
        def renderer = Mock(NamedJsonRenderer)
        def controller = new NamedJsonController(namedJsonRenderer: renderer)
        def webRequest = GrailsWebMockUtil.bindMockWebRequest()

        when:
        controller.render([name: 'Grails'], jsonConfiguration: 'deep', status: 201)

        then:
        1 * renderer.contains('deep') >> true
        1 * renderer.render('deep', [name: 'Grails'], _) >> { arguments ->
            arguments[2].write('{"configured":true}')
        }
        webRequest.response.status == 201
        webRequest.response.contentType == 'application/json;charset=utf-8'
        webRequest.response.contentAsString == '{"configured":true}'
        !webRequest.renderView
    }
}

class NamedJsonController implements Controller {
}

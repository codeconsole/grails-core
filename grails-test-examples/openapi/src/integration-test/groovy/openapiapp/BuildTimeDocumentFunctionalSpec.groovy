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
package openapiapp

import io.swagger.v3.core.util.Yaml31
import spock.lang.Shared
import spock.lang.Specification

import grails.testing.mixin.integration.Integration
import org.apache.grails.testing.http.client.HttpClientSupport

/**
 * Confirms the documents the generate-open-api command wrote at build time describe what the
 * running application serves.
 */
@Integration
class BuildTimeDocumentFunctionalSpec extends Specification implements HttpClientSupport {

    @Shared
    File directory = new File(System.getProperty('openapi.generated.directory'))

    void 'the default document and each group are written'() {
        expect:
        new File(directory, 'openapi.yaml').file
        new File(directory, 'openapi-catalogue.yaml').file
    }

    void 'the default document describes what springdoc serves'() {
        given:
        Map generated = read('openapi.yaml')
        Map served = http('/v3/api-docs').json()

        expect:
        generated.paths.keySet() == served.paths.keySet()
        generated.components.schemas.keySet() == served.components.schemas.keySet()
        generated.info == served.info
    }

    void 'a group describes what springdoc serves for it'() {
        given:
        Map generated = read('openapi-catalogue.yaml')
        Map served = http('/v3/api-docs/catalogue').json()

        expect:
        generated.paths.keySet() == served.paths.keySet()
    }

    private Map read(String name) {
        Yaml31.mapper().readValue(new File(directory, name), Map)
    }
}

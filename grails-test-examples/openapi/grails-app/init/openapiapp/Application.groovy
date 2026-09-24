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

import java.lang.reflect.Method

import org.springdoc.core.filters.OpenApiMethodFilter
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.context.annotation.Bean

import grails.boot.GrailsApp
import grails.boot.config.GrailsAutoConfiguration

class Application extends GrailsAutoConfiguration {

    static void main(String[] args) {
        GrailsApp.run(Application, args)
    }

    /**
     * Leaves the actions that are not part of the published API out of the default document.
     */
    @Bean
    OpenApiMethodFilter internalActionFilter() {
        { Method action -> !action.isAnnotationPresent(Internal) } as OpenApiMethodFilter
    }

    /**
     * A group of the books API that only reads.
     */
    @Bean
    GroupedOpenApi bookReads() {
        GroupedOpenApi.builder()
                .group('book-reads')
                .pathsToMatch('/books/**')
                .addOpenApiMethodFilter { Method action -> !(action.name in ['save', 'update', 'patch', 'delete']) }
                .build()
    }
}

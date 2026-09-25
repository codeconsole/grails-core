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

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import org.springdoc.core.customizers.GlobalOpenApiCustomizer
import org.springdoc.core.customizers.GlobalOperationCustomizer
import org.springdoc.core.filters.OpenApiMethodFilter
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.context.annotation.Bean
import org.springframework.web.method.HandlerMethod

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
     * Counts, in every document, the operations it describes.
     */
    @Bean
    GlobalOpenApiCustomizer operationCounter() {
        { OpenAPI openApi ->
            int count = (openApi.paths?.values() ?: []).sum(0) { PathItem item -> item.readOperations().size() } as int
            openApi.addExtension('x-operation-count', count)
        } as GlobalOpenApiCustomizer
    }

    /**
     * Names, on every operation, the action that serves it.
     */
    @Bean
    GlobalOperationCustomizer actionNameCustomizer() {
        { Operation operation, HandlerMethod handlerMethod ->
            operation.addExtension('x-action', "${handlerMethod.beanType.simpleName}.${handlerMethod.method.name}".toString())
            operation
        } as GlobalOperationCustomizer
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
                .addOperationCustomizer { Operation operation, HandlerMethod handlerMethod ->
                    operation.addExtension('x-group', 'book-reads')
                    operation
                }
                .build()
    }
}

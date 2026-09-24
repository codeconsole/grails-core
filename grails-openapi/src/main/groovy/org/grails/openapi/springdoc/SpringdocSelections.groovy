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
package org.grails.openapi.springdoc

import java.lang.reflect.Method
import java.util.function.Predicate

import groovy.transform.CompileStatic

import org.springdoc.core.customizers.SpringDocCustomizers
import org.springdoc.core.filters.GlobalOpenApiMethodFilter
import org.springdoc.core.filters.OpenApiMethodFilter
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.beans.factory.BeanFactory

import grails.openapi.OpenApiSelection

/**
 * What a document springdoc serves selects: its criteria, and the method filters springdoc applies
 * to its handler methods, applied to the Grails actions too.
 */
@CompileStatic
class SpringdocSelections {

    /**
     * The default document selects what the criteria select, among the actions every springdoc
     * method filter includes.
     */
    static OpenApiSelection defaultSelection(OpenApiSelection criteria, SpringDocCustomizers customizers) {
        OpenApiSelection selection = criteria.copy()
        selection.actionFilters.addAll(actionFilters(customizers?.methodFilters?.orElse(null)))
        selection
    }

    /**
     * A group selects what its criteria select, among the actions its method filters and the
     * global method filters include.
     */
    static OpenApiSelection groupSelection(GroupedOpenApi group, SpringDocCustomizers customizers) {
        OpenApiSelection selection = GroupedOpenApiContributor.selectionOf(group)
        // springdoc adds the global filters to each group as it builds the group's document, which
        // it has not where the document is generated without serving it.
        Set<OpenApiMethodFilter> filters = new LinkedHashSet<>()
        Set<GlobalOpenApiMethodFilter> globals = customizers?.globalOpenApiMethodFilters?.orElse(null)
        if (globals) {
            filters.addAll(globals)
        }
        if (group.openApiMethodFilters) {
            filters.addAll(group.openApiMethodFilters)
        }
        selection.actionFilters.addAll(actionFilters(filters))
        selection
    }

    /**
     * springdoc's customizers, where springdoc is configured in the context.
     */
    static SpringDocCustomizers customizers(BeanFactory beanFactory) {
        beanFactory?.getBeanProvider(SpringDocCustomizers)?.getIfAvailable()
    }

    private static List<Predicate<Method>> actionFilters(Collection<? extends OpenApiMethodFilter> filters) {
        (filters ?: []).collect { OpenApiMethodFilter filter ->
            { Method action -> filter.isMethodToInclude(action) } as Predicate<Method>
        }
    }
}

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

package org.grails.gorm.graphql.plugin

import groovy.transform.CompileStatic

import org.springframework.beans.factory.BeanRegistrar
import org.springframework.beans.factory.BeanRegistry
import org.springframework.context.MessageSource
import org.springframework.core.env.Environment

import grails.plugins.Plugin
import grails.web.mime.MimeType
import graphql.GraphQL
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLSchema
import org.grails.datastore.mapping.model.MappingContext
import org.grails.gorm.graphql.GraphQLServiceManager
import org.grails.gorm.graphql.Schema
import org.grails.gorm.graphql.binding.GraphQLDataBinder
import org.grails.gorm.graphql.binding.manager.DefaultGraphQLDataBinderManager
import org.grails.gorm.graphql.binding.manager.GraphQLDataBinderManager
import org.grails.gorm.graphql.entity.GraphQLEntityNamingConvention
import org.grails.gorm.graphql.entity.property.manager.DefaultGraphQLDomainPropertyManager
import org.grails.gorm.graphql.entity.property.manager.GraphQLDomainPropertyManager
import org.grails.gorm.graphql.fetcher.manager.DefaultGraphQLDataFetcherManager
import org.grails.gorm.graphql.fetcher.manager.GraphQLDataFetcherManager
import org.grails.gorm.graphql.interceptor.manager.DefaultGraphQLInterceptorManager
import org.grails.gorm.graphql.interceptor.manager.GraphQLInterceptorManager
import org.grails.gorm.graphql.plugin.binding.GrailsGraphQLDataBinder
import org.grails.gorm.graphql.response.delete.DefaultGraphQLDeleteResponseHandler
import org.grails.gorm.graphql.response.delete.GraphQLDeleteResponseHandler
import org.grails.gorm.graphql.response.errors.DefaultGraphQLErrorsResponseHandler
import org.grails.gorm.graphql.response.errors.GraphQLErrorsResponseHandler
import org.grails.gorm.graphql.response.pagination.DefaultGraphQLPaginationResponseHandler
import org.grails.gorm.graphql.response.pagination.GraphQLPaginationResponseHandler
import org.grails.gorm.graphql.types.DefaultGraphQLTypeManager
import org.grails.gorm.graphql.types.GraphQLTypeManager

@CompileStatic
class GormGraphqlGrailsPlugin extends Plugin {

    def license = 'Apache 2.0 License'
    def organization = [name: 'Grails', url: 'https://grails.apache.org/']
    def issueManagement = [system: 'Github', url: 'https://github.com/apache/grails-core/issues']
    def scm = [url: 'https://github.com/apache/grails-core']
    def grailsVersion = '7.1.0 > *'
    def profiles = ['web']
    def title = 'GORM GraphQL'
    def description = 'Generates a GraphQL schema based on entities in GORM'
    def documentation = 'https://grails.apache.org/docs/latest/grails-data/graphql/manual/'

    public static final MimeType GRAPHQL_MIME = new MimeType('application/graphql')

    @Override
    BeanRegistrar beanRegistrar() {
        return { BeanRegistry registry, Environment environment ->
            registry.registerBean('grailsGraphQLConfiguration', GrailsGraphQLConfiguration)

            if (!environment.getProperty('grails.gorm.graphql.enabled', Boolean, true)) {
                return
            }

            registry.registerBean('graphQLContextBuilder', DefaultGraphQLContextBuilder)

            registry.registerBean('graphQLDataBinder', GrailsGraphQLDataBinder)
            registry.registerBean('graphQLCodeRegistry', GraphQLCodeRegistry.Builder) { BeanRegistry.Spec<GraphQLCodeRegistry.Builder> spec ->
                spec.supplier { BeanRegistry.SupplierContext context ->
                    GraphQLCodeRegistry.newCodeRegistry()
                }
            }
            registry.registerBean('graphQLErrorsResponseHandler', DefaultGraphQLErrorsResponseHandler) { BeanRegistry.Spec<DefaultGraphQLErrorsResponseHandler> spec ->
                spec.supplier { BeanRegistry.SupplierContext context ->
                    new DefaultGraphQLErrorsResponseHandler(
                            context.bean('messageSource', MessageSource),
                            context.bean('graphQLCodeRegistry', GraphQLCodeRegistry.Builder))
                }
            }
            registry.registerBean('graphQLEntityNamingConvention', GraphQLEntityNamingConvention)
            registry.registerBean('graphQLDomainPropertyManager', DefaultGraphQLDomainPropertyManager)
            registry.registerBean('graphQLPaginationResponseHandler', DefaultGraphQLPaginationResponseHandler)

            registry.registerBean('graphQLTypeManager', DefaultGraphQLTypeManager) { BeanRegistry.Spec<DefaultGraphQLTypeManager> spec ->
                spec.supplier { BeanRegistry.SupplierContext context ->
                    new DefaultGraphQLTypeManager(
                            context.bean('graphQLCodeRegistry', GraphQLCodeRegistry.Builder),
                            context.bean('graphQLEntityNamingConvention', GraphQLEntityNamingConvention),
                            context.bean('graphQLErrorsResponseHandler', GraphQLErrorsResponseHandler),
                            context.bean('graphQLDomainPropertyManager', GraphQLDomainPropertyManager),
                            context.bean('graphQLPaginationResponseHandler', GraphQLPaginationResponseHandler))
                }
            }
            registry.registerBean('graphQLDataBinderManager', DefaultGraphQLDataBinderManager) { BeanRegistry.Spec<DefaultGraphQLDataBinderManager> spec ->
                spec.supplier { BeanRegistry.SupplierContext context ->
                    new DefaultGraphQLDataBinderManager(context.bean('graphQLDataBinder', GraphQLDataBinder))
                }
            }
            registry.registerBean('graphQLDeleteResponseHandler', DefaultGraphQLDeleteResponseHandler)
            registry.registerBean('graphQLDataFetcherManager', DefaultGraphQLDataFetcherManager)
            registry.registerBean('graphQLInterceptorManager', DefaultGraphQLInterceptorManager)
            registry.registerBean('graphQLServiceManager', GraphQLServiceManager)

            registry.registerBean('graphQLSchemaGenerator', Schema) { BeanRegistry.Spec<Schema> spec ->
                spec.supplier { BeanRegistry.SupplierContext context ->
                    // The Schema's varargs MappingContext constructor was previously satisfied by
                    // Spring's implicit single-constructor autowiring, collecting every MappingContext
                    // bean; the supplier must resolve them the same way or generate() yields an empty
                    // schema (and returns null when there are no query fields)
                    List<MappingContext> mappingContexts = context.beanProvider(MappingContext).orderedStream().toList()
                    Schema schema = new Schema(mappingContexts as MappingContext[])
                    schema.codeRegistry = context.bean('graphQLCodeRegistry', GraphQLCodeRegistry.Builder)
                    schema.deleteResponseHandler = context.bean('graphQLDeleteResponseHandler', GraphQLDeleteResponseHandler)
                    schema.namingConvention = context.bean('graphQLEntityNamingConvention', GraphQLEntityNamingConvention)
                    schema.typeManager = context.bean('graphQLTypeManager', GraphQLTypeManager)
                    schema.dataBinderManager = context.bean('graphQLDataBinderManager', GraphQLDataBinderManager)
                    schema.dataFetcherManager = context.bean('graphQLDataFetcherManager', GraphQLDataFetcherManager)
                    schema.interceptorManager = context.bean('graphQLInterceptorManager', GraphQLInterceptorManager)
                    schema.paginationResponseHandler = context.bean('graphQLPaginationResponseHandler', GraphQLPaginationResponseHandler)
                    schema.serviceManager = context.bean('graphQLServiceManager', GraphQLServiceManager)

                    GrailsGraphQLConfiguration configuration = context.bean('grailsGraphQLConfiguration', GrailsGraphQLConfiguration)
                    schema.dateFormats = configuration.dateFormats
                    schema.dateFormatLenient = configuration.dateFormatLenient ?: false
                    schema.listArguments = configuration.listArguments
                    return schema
                }
            }

            registry.registerBean('graphQLSchema', GraphQLSchema) { BeanRegistry.Spec<GraphQLSchema> spec ->
                spec.supplier { BeanRegistry.SupplierContext context ->
                    context.bean('graphQLSchemaGenerator', Schema).generate()
                }
            }
            registry.registerBean('graphQLBuilder', GraphQL.Builder) { BeanRegistry.Spec<GraphQL.Builder> spec ->
                spec.supplier { BeanRegistry.SupplierContext context ->
                    GraphQL.newGraphQL(context.bean('graphQLSchema', GraphQLSchema))
                }
            }
            registry.registerBean('graphQL', GraphQL) { BeanRegistry.Spec<GraphQL> spec ->
                spec.supplier { BeanRegistry.SupplierContext context ->
                    context.bean('graphQLBuilder', GraphQL.Builder).build()
                }
            }
        }
    }
}

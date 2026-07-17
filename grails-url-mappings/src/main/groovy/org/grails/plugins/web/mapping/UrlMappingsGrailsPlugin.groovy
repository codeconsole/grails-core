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
package org.grails.plugins.web.mapping

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic

import org.springframework.aop.framework.ProxyFactoryBean
import org.springframework.aop.target.HotSwappableTargetSource
import org.springframework.beans.factory.BeanRegistrar
import org.springframework.beans.factory.BeanRegistry
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment

import grails.config.Settings
import grails.plugins.Plugin
import grails.util.GrailsUtil
import grails.web.mapping.LinkGenerator
import grails.web.mapping.UrlMappings
import grails.web.mapping.UrlMappingsHolder
import grails.web.mapping.cors.GrailsCorsConfiguration
import org.grails.core.artefact.UrlMappingsArtefactHandler
import org.grails.spring.beans.factory.HotSwappableTargetSourceFactoryBean
import org.grails.web.mapping.CachingLinkGenerator
import org.grails.web.mapping.UrlMappingsHolderFactoryBean
import org.grails.web.mapping.mvc.UrlMappingsHandlerMapping

/**
 * Handles the configuration of URL mappings.
 *
 * @author Graeme Rocher
 * @since 0.4
 */
@CompileStatic
class UrlMappingsGrailsPlugin extends Plugin {

    def watchedResources = ['file:./grails-app/controllers/*UrlMappings.groovy']

    def version = GrailsUtil.getGrailsVersion()
    def dependsOn = [core: version]
    def loadAfter = ['controllers']

    @Override
    BeanRegistrar beanRegistrar() {
        return { BeanRegistry registry, Environment environment ->
            if (!grailsApplication.getArtefacts(UrlMappingsArtefactHandler.TYPE)) {
                grailsApplication.addArtefact(UrlMappingsArtefactHandler.TYPE, DefaultUrlMappings)
            }

            boolean isReloadEnabled = grails.util.Environment.isDevelopmentMode() ||
                    grails.util.Environment.current.isReloadEnabled()
            boolean corsFilterEnabled = environment.getProperty(Settings.SETTING_CORS_FILTER, Boolean, true)

            registry.registerBean('urlMappingsHandlerMapping', UrlMappingsHandlerMapping) { BeanRegistry.Spec<UrlMappingsHandlerMapping> spec ->
                spec.supplier { BeanRegistry.SupplierContext context ->
                    UrlMappingsHandlerMapping handlerMapping =
                            new UrlMappingsHandlerMapping(context.bean('grailsUrlMappingsHolder', UrlMappingsHolder))
                    if (!corsFilterEnabled) {
                        handlerMapping.grailsCorsConfiguration = context.bean('grailsCorsConfiguration', GrailsCorsConfiguration)
                    }
                    return handlerMapping
                }
            }

            if (isReloadEnabled) {
                registry.registerBean('urlMappingsTargetSource', HotSwappableTargetSourceFactoryBean) { BeanRegistry.Spec<HotSwappableTargetSourceFactoryBean> spec ->
                    spec.lazyInit().supplier { BeanRegistry.SupplierContext context ->
                        UrlMappingsHolderFactoryBean urlMappingsFactory = new UrlMappingsHolderFactoryBean()
                        urlMappingsFactory.grailsApplication = grailsApplication
                        urlMappingsFactory.applicationContext = applicationContext
                        urlMappingsFactory.afterPropertiesSet()
                        HotSwappableTargetSourceFactoryBean targetSourceFactory = new HotSwappableTargetSourceFactoryBean()
                        targetSourceFactory.target = urlMappingsFactory.object
                        return targetSourceFactory
                    }
                }
                registry.registerBean('grailsUrlMappingsHolder', ProxyFactoryBean) { BeanRegistry.Spec<ProxyFactoryBean> spec ->
                    spec.lazyInit().supplier { BeanRegistry.SupplierContext context ->
                        ProxyFactoryBean proxyFactory = new ProxyFactoryBean()
                        proxyFactory.targetSource = context.bean('urlMappingsTargetSource', HotSwappableTargetSource)
                        proxyFactory.proxyInterfaces = [UrlMappings] as Class[]
                        return proxyFactory
                    }
                }
            } else {
                registry.registerBean('grailsUrlMappingsHolder', UrlMappingsHolderFactoryBean) { BeanRegistry.Spec<UrlMappingsHolderFactoryBean> spec ->
                    spec.lazyInit()
                }
            }
        }
    }

    @Override
    @CompileDynamic
    void onChange(Map<String, Object> event) {
        def application = grailsApplication
        if (!application.isArtefactOfType(UrlMappingsArtefactHandler.TYPE, event.source)) {
            return
        }

        application.addArtefact(UrlMappingsArtefactHandler.TYPE, event.source)

        ApplicationContext ctx = applicationContext
        UrlMappingsHolder urlMappingsHolder = createUrlMappingsHolder(applicationContext)

        HotSwappableTargetSource ts = ctx.getBean('urlMappingsTargetSource', HotSwappableTargetSource)
        ts.swap(urlMappingsHolder)

        LinkGenerator linkGenerator = ctx.getBean('grailsLinkGenerator', LinkGenerator)
        if (linkGenerator instanceof CachingLinkGenerator) {
            linkGenerator.clearCache()
        }
    }

    @CompileStatic
    private static UrlMappingsHolder createUrlMappingsHolder(ApplicationContext applicationContext) {
        def factory = new UrlMappingsHolderFactoryBean(applicationContext: applicationContext)
        factory.afterPropertiesSet()
        return factory.getObject()
    }

    @CompileDynamic
    static class DefaultUrlMappings {
        static mappings = {
            "/$controller/$action?/$id?(.$format)?"()
        }
    }
}

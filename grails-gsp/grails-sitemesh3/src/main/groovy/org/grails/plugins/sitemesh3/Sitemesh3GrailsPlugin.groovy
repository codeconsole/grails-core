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
package org.grails.plugins.sitemesh3

import groovy.transform.CompileStatic

import org.springframework.beans.factory.BeanRegistrar
import org.springframework.beans.factory.BeanRegistry
import org.springframework.core.env.Environment

import grails.plugins.Plugin
import org.grails.plugins.web.taglib.RenderSitemeshTagLib

/**
 * Configures Grails to use SiteMesh 3 instead of SiteMesh 2.
 *
 * <p>The heavy lifting lives outside this class: default configuration
 * properties are contributed by {@link Sitemesh3EnvironmentPostProcessor}
 * (registered in {@code META-INF/spring.factories}) and the view-resolver
 * decoration is applied by {@link Sitemesh3AutoConfiguration}. The only bean
 * this plugin itself contributes is registered through {@link #beanRegistrar()},
 * the modern replacement for the deprecated {@code doWithSpring()} bean DSL.</p>
 *
 * <p>With no {@code doWithSpring()} bean-builder closure — whose dynamic
 * dispatch against the bean builder prevents static compilation of descriptor
 * classes — this plugin compiles statically as a whole.</p>
 */
@CompileStatic
class Sitemesh3GrailsPlugin extends Plugin {

    def grailsVersion = '7.0.0-SNAPSHOT > *'

    def title = 'SiteMesh 3'
    def author = 'Scott Murphy'
    def authorEmail = ''
    def description = 'Configures Grails to use SiteMesh 3 instead of SiteMesh 2'
    def profiles = ['web']

    def license = 'APACHE'

    def developers = [[name: 'Scott Murphy']]

    def loadBefore = ['groovyPages']

    def providedArtefacts = [
            RenderSitemeshTagLib,
            Sitemesh3LayoutTagLib,
    ]

    @Override
    BeanRegistrar beanRegistrar() {
        { BeanRegistry registry, Environment environment ->
            // Unwraps the SiteMesh view for "render template:" partials so
            // they are never decorated with a layout (the SiteMesh 2 plugin
            // does the same with its GrailsLayoutRenderViewMutator).
            registry.registerBean('grailsRenderViewMutator', Sitemesh3RenderViewMutator)
        } as BeanRegistrar
    }
}

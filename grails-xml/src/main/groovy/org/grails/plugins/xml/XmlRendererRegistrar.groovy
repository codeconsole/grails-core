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
package org.grails.plugins.xml

import java.util.function.Supplier

import groovy.transform.CompileStatic

import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.validation.Errors

import grails.rest.render.RendererRegistry
import org.grails.plugins.web.rest.render.SpringMessageConverters
import org.grails.plugins.web.rest.render.xml.DefaultXmlRenderer
import org.grails.web.gsp.io.GrailsConventionGroovyPageLocator

/**
 * Adds the legacy XML renderers only when the optional XML module is present.
 *
 * @since 8.0
 */
@CompileStatic
class XmlRendererRegistrar implements InitializingBean {

    @Autowired
    RendererRegistry rendererRegistry

    @Autowired(required = false)
    GrailsConventionGroovyPageLocator groovyPageLocator

    @Autowired(required = false)
    SpringMessageConverters springMessageConverters

    @Value('${grails.converters.encoding:UTF-8}')
    String encoding

    private Supplier<List<HttpMessageConverter<?>>> converterSupplier() {
        SpringMessageConverters holder = springMessageConverters
        return holder == null ? null : (Supplier<List<HttpMessageConverter<?>>>) holder::getConverters
    }

    @Override
    void afterPropertiesSet() {
        DefaultXmlRenderer<Object> defaultRenderer =
                new DefaultXmlRenderer<Object>(Object, groovyPageLocator, rendererRegistry)
        defaultRenderer.encoding = encoding
        defaultRenderer.springHttpMessageConvertersSupplier = converterSupplier()
        rendererRegistry.addDefaultRenderer(defaultRenderer)

        DefaultXmlRenderer<Errors> errorsRenderer = new DefaultXmlRenderer<Errors>(Errors)
        errorsRenderer.encoding = encoding
        errorsRenderer.springHttpMessageConvertersSupplier = converterSupplier()
        rendererRegistry.addContainerRenderer(Object, errorsRenderer)
    }
}

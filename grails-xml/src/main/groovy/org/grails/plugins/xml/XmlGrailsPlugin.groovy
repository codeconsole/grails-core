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

import groovy.transform.CompileStatic

import org.springframework.beans.factory.BeanRegistrar
import org.springframework.beans.factory.BeanRegistry
import org.springframework.core.env.Environment

import grails.converters.XML
import grails.plugins.Plugin
import grails.util.GrailsUtil
import org.grails.plugins.codecs.XMLCodec
import org.grails.web.converters.configuration.ObjectMarshallerRegisterer
import org.grails.web.converters.configuration.XmlConvertersConfigurationInitializer
import org.grails.web.converters.marshaller.xml.ValidationErrorsMarshaller
import org.grails.web.databinding.bindingsource.HalXmlDataBindingSourceCreator
import org.grails.web.databinding.bindingsource.XmlDataBindingSourceCreator

/**
 * Provides optional XML conversion, rendering, and request binding support.
 *
 * @since 8.0
 */
@CompileStatic
class XmlGrailsPlugin extends Plugin {

    def version = GrailsUtil.getGrailsVersion()
    def dependsOn = [converters: version, dataBinding: version, restResponder: version]
    def providedArtefacts = [XMLCodec]

    @Override
    BeanRegistrar beanRegistrar() {
        return { BeanRegistry registry, Environment environment ->
            registry.registerBean('xmlErrorsMarshaller', ValidationErrorsMarshaller)
            registry.registerBean('xmlConvertersConfigurationInitializer', XmlConvertersConfigurationInitializer)
            registry.registerBean('xmlDataBindingSourceCreator', XmlDataBindingSourceCreator)
            registry.registerBean('halXmlDataBindingSourceCreator', HalXmlDataBindingSourceCreator)
            registry.registerBean('xmlRendererRegistrar', XmlRendererRegistrar)
            registry.registerBean('errorsXmlMarshallerRegisterer', ObjectMarshallerRegisterer) {
                it.supplier {
                    new ObjectMarshallerRegisterer(
                            marshaller: it.bean('xmlErrorsMarshaller', ValidationErrorsMarshaller),
                            converterClass: XML
                    )
                }
            }
        }
    }
}

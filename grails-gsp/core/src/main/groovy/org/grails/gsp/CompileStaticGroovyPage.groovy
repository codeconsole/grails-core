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

package org.grails.gsp

import groovy.transform.CompileStatic

import org.springframework.context.ApplicationContext

import grails.core.GrailsApplication
import org.grails.taglib.TagLibNamespaceMethodDispatcher
import org.grails.taglib.encoder.OutputContext

/**
 * Base class for staticly compiled GSPs
 *
 * getProperty and invokeMethod calls are a result of GroovyPageTypeCheckingExtension
 *
 */
@CompileStatic
abstract class CompileStaticGroovyPage extends GroovyPage {

    TagLibNamespaceMethodDispatcher defaultTagDispatcher

    @Override
    void initRun(Writer target, OutputContext outputContext, GroovyPageMetaInfo metaInfo) {
        super.initRun(target, outputContext, metaInfo)
        defaultTagDispatcher = TagLibNamespaceMethodDispatcher.cast(lookupTagDispatcher(DEFAULT_NAMESPACE))
    }

    @Override
    protected Object lookupTagDispatcher(String namespace) {
        gspTagLibraryLookup != null && gspTagLibraryLookup.hasNamespace(namespace) ? new TagLibNamespaceMethodDispatcher(namespace, gspTagLibraryLookup, outputContext) : null
    }

    /**
     * The request parameters, as {@code ${params.id}} reads them.
     *
     * <p>A statically compiled page resolves a name it does not declare through {@link #getProperty},
     * which is typed {@code Object}, so reading a property of one is a type error. The names a page
     * never declares because the framework supplies them are declared here instead, typed as far as
     * this module can see them: enough for the type checker to accept reading from them, and no
     * further. Each returns what dynamic resolution returns, including {@code null} where a page is
     * rendered outside a web request and the name was never bound.</p>
     */
    Map getParams() {
        (Map) resolveProperty('params')
    }

    /**
     * The flash scope, as {@code ${flash.message}} reads it. See {@link #getParams}.
     */
    Map getFlash() {
        (Map) resolveProperty('flash')
    }

    /**
     * The name of the controller that rendered this page. See {@link #getParams}.
     */
    String getControllerName() {
        (String) resolveProperty('controllerName')
    }

    /**
     * The name of the action that rendered this page. See {@link #getParams}.
     */
    String getActionName() {
        (String) resolveProperty('actionName')
    }

    /**
     * The namespace of the controller that rendered this page. See {@link #getParams}.
     */
    String getNamespace() {
        (String) resolveProperty('namespace')
    }

    /**
     * The running application. See {@link #getParams}.
     */
    GrailsApplication getGrailsApplication() {
        (GrailsApplication) resolveProperty('grailsApplication')
    }

    /**
     * The application context. See {@link #getParams}.
     */
    ApplicationContext getApplicationContext() {
        (ApplicationContext) resolveProperty('applicationContext')
    }

    @Override
    Object getProperty(String property) {
        return resolveProperty(property)
    }

    @Override
    Object invokeMethod(String name, Object args) {
        return defaultTagDispatcher.invokeMethod(name, args)
    }
}

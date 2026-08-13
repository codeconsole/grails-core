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

import jakarta.servlet.ServletContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession

import groovy.transform.CompileStatic

import grails.util.TypeConvertingMap
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
    TypeConvertingMap getParams() {
        // TypeConvertingMap rather than Map: the parameters are a GrailsParameterMap, and typing them
        // as a plain Map would compile params.id but reject params.int('max') and its siblings, which
        // are declared on TypeConvertingMap. It implements Map, so reading a parameter by name still
        // compiles to a get().
        (TypeConvertingMap) resolveProperty('params')
    }

    /**
     * The current request, as {@code ${request.contextPath}} reads it. See {@link #getParams}.
     */
    HttpServletRequest getRequest() {
        (HttpServletRequest) resolveProperty('request')
    }

    /**
     * The current response. See {@link #getParams}.
     */
    HttpServletResponse getResponse() {
        (HttpServletResponse) resolveProperty('response')
    }

    /**
     * The current session, or {@code null} where there is none. See {@link #getParams}.
     */
    HttpSession getSession() {
        (HttpSession) resolveProperty('session')
    }

    /**
     * The servlet context, which a page reads as {@code application}. See {@link #getParams}.
     */
    ServletContext getApplication() {
        (ServletContext) resolveProperty('application')
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

    // grailsApplication and applicationContext are deliberately not declared here. What pages read
    // from them is not declared anywhere either: grailsApplication.controllerClasses is matched at
    // runtime against (\w+)(Classes) and answered from the artefact handlers, an open set that no
    // interface can enumerate, and applicationListeners belongs to an implementation rather than the
    // ApplicationContext interface. Typing them only changes which type the failure names; they are
    // resolved dynamically by GroovyPageTypeCheckingExtension instead, which lets them work.

    @Override
    Object getProperty(String property) {
        return resolveProperty(property)
    }

    @Override
    Object invokeMethod(String name, Object args) {
        return defaultTagDispatcher.invokeMethod(name, args)
    }
}

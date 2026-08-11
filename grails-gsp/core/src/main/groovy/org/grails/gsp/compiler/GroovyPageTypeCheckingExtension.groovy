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

package org.grails.gsp.compiler

import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.transform.stc.GroovyTypeCheckingExtensionSupport
import org.codehaus.groovy.transform.stc.StaticTypesMarker

import org.grails.gsp.GroovyPage
import org.grails.taglib.index.TagLibraryIndex

/**
 * CompileStatic type checking extension for GSPs
 *
 * This makes all unresolved property, variable and method calls dynamic
 *
 */
class GroovyPageTypeCheckingExtension extends GroovyTypeCheckingExtensionSupport.TypeCheckingDSL {

    /**
     * Tag libraries compiled ahead of this page, discovered from their compile-time descriptors.
     * <p>
     * Where the {@code taglibs} directive only states which namespaces a page is permitted to use,
     * this states which tags actually exist. That turns a call to a misspelled tag from something
     * deferred to runtime dispatch into a compilation error, and removes the need to declare
     * namespaces by hand for tag libraries that were on the compile classpath.
     */
    private static final TagLibraryIndex TAG_LIBRARY_INDEX = TagLibraryIndex.load(
            GroovyPageTypeCheckingExtension.classLoader)

    @Override
    Object run() {
        ClassNode configAnnotationClassNode = ClassHelper.make(GroovyPageTypeCheckingConfig)

        beforeVisitClass { ClassNode classNode ->
            newScope {
                allowedTagLibs = [] as Set
                dynamicProperties = [] as Set
                undeclaredDynamicVariables = [] as Set
            }
            AnnotationNode configAnnotation = classNode.getAnnotations(configAnnotationClassNode)?.find { it }
            if (configAnnotation) {
                Expression taglibsExpression = configAnnotation.getMember('taglibs')
                if (taglibsExpression instanceof ListExpression) {
                    currentScope.allowedTagLibs = ListExpression.cast(taglibsExpression).expressions.collect([] as Set) { it.text.trim() }
                }
            }
            // Namespaces backed by a compiled tag library need no declaration: their tags are known.
            currentScope.allowedTagLibs.addAll(TAG_LIBRARY_INDEX.namespaces)
        }

        unresolvedProperty { PropertyExpression pe ->
            if (isThisTheReceiver(pe) && currentScope.allowedTagLibs.contains(pe.propertyAsString)) {
                currentScope.dynamicProperties << pe
                return makeDynamic(pe)
            }
        }

        unresolvedVariable { VariableExpression ve ->
            if (currentScope.allowedTagLibs.contains(ve.name)) {
                currentScope.dynamicProperties << ve
                return makeDynamic(ve)
            }
        }

        methodNotFound { receiver, name, argList, argTypes, call ->
            if (isThisTheReceiver(call)) {
                // An unqualified call in a GSP is a tag in the default namespace. When that namespace
                // has compiled tag libraries, the tag has to be one of them.
                reportUnknownTagIfIndexed(GroovyPage.DEFAULT_NAMESPACE, name, call)
                return makeDynamic(call)
            }
            def objectExpression = call.objectExpression
            if (objectExpression == null) {
                return null
            }
            if (currentScope.dynamicProperties.contains(objectExpression)) {
                reportUnknownTagIfIndexed(namespaceNameOf(objectExpression), name, call)
                return makeDynamic(call)
            }
            // GROOVY-12041: Groovy 5 resolves receivers inherited through getProperty(String) as dynamic
            // before unresolvedVariable/unresolvedProperty can record them. Use the marker Groovy places on
            // those expressions, but still require the receiver name to be an allowed taglib namespace.
            if (isAllowedDynamicTaglibNamespace(objectExpression)) {
                reportUnknownTagIfIndexed(namespaceNameOf(objectExpression), name, call)
                return makeDynamic(call)
            }
            if (objectExpression instanceof VariableExpression && isUndeclaredDynamicVariable(objectExpression)) {
                reportUndeclaredDynamicVariable(objectExpression)
                return makeDynamic(call)
            }
        }

        afterVisitMethod { MethodNode methodNode ->
            reportUndeclaredDynamicVariables(methodNode)
        }
    }

    private void reportUndeclaredDynamicVariables(MethodNode methodNode) {
        methodNode.code?.visit(new CodeVisitorSupport() {
            @Override
            void visitVariableExpression(VariableExpression expression) {
                if (isUndeclaredDynamicVariable(expression)) {
                    reportUndeclaredDynamicVariable(expression)
                }
                super.visitVariableExpression(expression)
            }
        })
    }

    private boolean isUndeclaredDynamicVariable(VariableExpression expression) {
        if (expression.thisExpression || expression.superExpression) {
            return false
        }
        if (currentScope.allowedTagLibs.contains(expression.name)) {
            return false
        }
        expression.getNodeMetaData(StaticTypesMarker.DYNAMIC_RESOLUTION) != null
    }

    private void reportUndeclaredDynamicVariable(VariableExpression expression) {
        if (currentScope.undeclaredDynamicVariables.add(expression.name)) {
            typeCheckingVisitor.addStaticTypeError("The variable [${expression.name}] is undeclared.", expression)
        }
    }

    private boolean isAllowedDynamicTaglibNamespace(Expression objectExpression) {
        if (objectExpression.getNodeMetaData(StaticTypesMarker.DYNAMIC_RESOLUTION) == null) {
            return false
        }

        String namespaceName = null
        if (objectExpression instanceof VariableExpression) {
            namespaceName = ((VariableExpression) objectExpression).name
        } else if (objectExpression instanceof PropertyExpression && isThisTheReceiver(objectExpression)) {
            namespaceName = ((PropertyExpression) objectExpression).propertyAsString
        }

        namespaceName != null && currentScope.allowedTagLibs.contains(namespaceName)
    }

    def isThisTheReceiver(expr) {
        expr.implicitThis || (expr.objectExpression instanceof VariableExpression && expr.objectExpression.thisExpression)
    }

    /**
     * Fails compilation when a namespace has compiled tag libraries but none of them declares the tag.
     *
     * <p>Silent when the namespace is unknown to the index, because a tag library registered at runtime
     * or supplied by a plugin compiled separately is still legitimate and must keep resolving
     * dynamically.
     */
    private void reportUnknownTagIfIndexed(String namespace, String tagName, Expression call) {
        if (namespace == null || !TAG_LIBRARY_INDEX.hasNamespace(namespace)) {
            return
        }
        if (TAG_LIBRARY_INDEX.lookup(namespace, tagName) != null) {
            return
        }
        typeCheckingVisitor.addStaticTypeError(
                "No such tag [${tagName}] in namespace [${namespace}]. Known tags: " +
                        TAG_LIBRARY_INDEX.getTagNames(namespace).join(', '), call)
    }

    private static String namespaceNameOf(Expression objectExpression) {
        if (objectExpression instanceof VariableExpression) {
            return ((VariableExpression) objectExpression).name
        }
        if (objectExpression instanceof PropertyExpression) {
            return ((PropertyExpression) objectExpression).propertyAsString
        }
        null
    }
}

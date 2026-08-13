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
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.transform.stc.GroovyTypeCheckingExtensionSupport
import org.codehaus.groovy.transform.stc.StaticTypesMarker

/**
 * CompileStatic type checking extension for GSPs
 *
 * This makes all unresolved property, variable and method calls dynamic
 *
 */
class GroovyPageTypeCheckingExtension extends GroovyTypeCheckingExtensionSupport.TypeCheckingDSL {

    /**
     * Names the framework binds into every page whose members are answered at runtime rather than
     * declared: {@code grailsApplication.controllerClasses} is matched against a name and answered
     * from the artefact handlers, an open set no interface enumerates, and what pages read from the
     * application context belongs to an implementation rather than to the interface. The flash scope
     * and the web request are here for a different reason: their types live in a module that depends
     * on this one, so they cannot be named from the page. The scopes that can be -- the request, the
     * response, the session, the servlet context -- are typed on the page and are checked normally.
     */
    private static final Set<String> FRAMEWORK_DYNAMIC_NAMES =
            ['grailsApplication', 'applicationContext', 'flash', 'webRequest'] as Set

    @Override
    Object run() {
        ClassNode configAnnotationClassNode = ClassHelper.make(GroovyPageTypeCheckingConfig)

        beforeVisitClass { ClassNode classNode ->
            newScope {
                allowedTagLibs = [] as Set
                pageScopeVariables = [] as Set
                dynamicProperties = [] as Set
                operatorReceivers = [] as Set
                undeclaredDynamicVariables = [] as Set
                strict = false
            }
            AnnotationNode configAnnotation = classNode.getAnnotations(configAnnotationClassNode)?.find { it }
            if (configAnnotation) {
                currentScope.allowedTagLibs = namesFrom(configAnnotation, 'taglibs')
                currentScope.pageScopeVariables = namesFrom(configAnnotation, 'pageScopeVariables')
                currentScope.strict = configAnnotation.getMember('strict')?.text == 'true'
            }
            // What a page reads from these is answered at runtime rather than declared -- an artefact
            // property matched against a name, an implementation member behind an interface -- so they
            // are resolved the way a page that is not compiled statically resolves them.
            currentScope.allowedTagLibs.addAll(FRAMEWORK_DYNAMIC_NAMES)
        }

        unresolvedProperty { PropertyExpression pe ->
            if (isOperatorReceiver(pe)) {
                return null
            }
            if (isThisTheReceiver(pe) && currentScope.allowedTagLibs.contains(pe.propertyAsString)) {
                currentScope.dynamicProperties << pe
                return makeDynamic(pe)
            }
            // A property read from something the page introduced is decided at render time along with
            // the value it is read from, so it follows that value rather than being reported here.
            if (currentScope.dynamicProperties.contains(pe.objectExpression) || isPageScopeVariable(pe.objectExpression)) {
                currentScope.dynamicProperties << pe
                return makeDynamic(pe)
            }
            if (!currentScope.strict && isUnknownReceiver(pe.objectExpression)) {
                currentScope.dynamicProperties << pe
                return makeDynamic(pe)
            }
        }

        unresolvedVariable { VariableExpression ve ->
            if (isOperatorReceiver(ve)) {
                return null
            }
            if (currentScope.allowedTagLibs.contains(ve.name) || currentScope.pageScopeVariables.contains(ve.name)) {
                currentScope.dynamicProperties << ve
                return makeDynamic(ve)
            }
            if (!currentScope.strict) {
                currentScope.dynamicProperties << ve
                return makeDynamic(ve)
            }
        }

        methodNotFound { receiver, name, argList, argTypes, call ->
            if (isOperatorReceiver(call)) {
                return null
            }
            if (isThisTheReceiver(call)) {
                return makeDynamic(call)
            }
            def objectExpression = call.objectExpression
            if (objectExpression == null) {
                return null
            }
            if (currentScope.dynamicProperties.contains(objectExpression)) {
                return makeDynamic(call)
            }
            // GROOVY-12041: Groovy 5 resolves receivers inherited through getProperty(String) as dynamic
            // before unresolvedVariable/unresolvedProperty can record them. Use the marker Groovy places on
            // those expressions, but still require the receiver name to be an allowed taglib namespace.
            if (isAllowedDynamicTaglibNamespace(objectExpression)) {
                return makeDynamic(call)
            }
            // A call on a receiver that resolved to Object is a call on something this page never knew
            // the type of. Reporting it says nothing the page can act on, and the same call in a page
            // that is not compiled statically runs; it is only reported where strictness was asked for.
            if (!currentScope.strict &&
                    (isPageScopeVariable(objectExpression) || isUnknownReceiver(objectExpression))) {
                return makeDynamic(call)
            }
            if (objectExpression instanceof VariableExpression && isUndeclaredDynamicVariable(objectExpression)) {
                reportUndeclaredDynamicVariable(objectExpression)
                return makeDynamic(call)
            }
        }

        beforeVisitMethod { MethodNode methodNode ->
            currentScope.operatorReceivers = collectOperatorReceivers(methodNode)
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
        if (expression.getNodeMetaData(StaticTypesMarker.DYNAMIC_RESOLUTION) == null) {
            return false
        }
        // An operator applied to something of no known type is reported whatever was asked for, and
        // before the exemptions below, which is the whole point of it being here: the receiver
        // resolves through the getProperty this page inherits, and the class writer fails with a
        // GroovyBugError rather than an error when it writes an operator against such a receiver.
        // Reporting it is what keeps the compilation from reaching that.
        if (isOperatorReceiver(expression)) {
            return true
        }
        if (!currentScope.strict) {
            // A page that has not declared its model reads the model it was rendered with, which is
            // how a page that is not compiled statically reads it. Only strictness asks for it back.
            return false
        }
        if (currentScope.allowedTagLibs.contains(expression.name)) {
            return false
        }
        if (currentScope.pageScopeVariables.contains(expression.name)) {
            return false
        }
        true
    }

    /**
     * Whether the type of what a member is read from is not known, which is what {@code Object} means
     * for an expression in a page: nothing said what it holds.
     */
    /**
     * The expressions an operator is applied to, which are what must not be resolved dynamically.
     *
     * <p>Resolving a name dynamically works where it is read from or called, because both go through
     * a call site the compiler can leave to the runtime. An operator does not: a subscript is written
     * as {@code getAt} straight into the class, and the writer that does it fails with a
     * {@code GroovyBugError} rather than an error a page can act on when it is handed a receiver whose
     * type was never established. Those are left alone, so that {@code rows[0]} on something the page
     * never declared is reported as the type error it is.</p>
     */
    private static Set<String> collectOperatorReceivers(MethodNode methodNode) {
        Set<String> receivers = [] as Set
        methodNode.code?.visit(new CodeVisitorSupport() {
            @Override
            void visitBinaryExpression(BinaryExpression expression) {
                receivers.add(positionOf(expression.leftExpression))
                super.visitBinaryExpression(expression)
            }
        })
        receivers
    }

    /**
     * Where an expression is written, which is how one collected before the visit is recognised during
     * it: type checking does not hand back the node that was collected, so identity says nothing.
     */
    private static String positionOf(Expression expression) {
        "${expression.lineNumber}:${expression.columnNumber}:${expression.lastLineNumber}:${expression.lastColumnNumber}"
    }

    private boolean isOperatorReceiver(Expression expression) {
        expression != null && currentScope.operatorReceivers?.contains(positionOf(expression))
    }

    private boolean isUnknownReceiver(Expression expression) {
        // Asked of the visitor rather than read from the expression: a closure parameter carries its
        // type there and not in its own metadata, and those are most of what a page reads from.
        expression != null && ClassHelper.OBJECT_TYPE == getType(expression)
    }

    private boolean isPageScopeVariable(Expression expression) {
        expression instanceof VariableExpression &&
                currentScope.pageScopeVariables.contains(((VariableExpression) expression).name)
    }

    private static Set<String> namesFrom(AnnotationNode annotation, String member) {
        Expression expression = annotation.getMember(member)
        expression instanceof ListExpression ?
                ListExpression.cast(expression).expressions.collect([] as Set) { it.text.trim() } : ([] as Set)
    }

    private void reportUndeclaredDynamicVariable(VariableExpression expression) {
        if (!currentScope.undeclaredDynamicVariables.add(expression.name)) {
            return
        }
        if (isOperatorReceiver(expression)) {
            typeCheckingVisitor.addStaticTypeError(
                    "The type of [${expression.name}] is not known here, and an operator cannot be applied to it. " +
                            'Declare it in the model directive, or give it a type where it is introduced.', expression)
            return
        }
        typeCheckingVisitor.addStaticTypeError("The variable [${expression.name}] is undeclared.", expression)
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

        // Same reasoning as GROOVY-12041 above for a name the page introduced: Groovy has already
        // resolved the receiver dynamically by the time the call is looked at, so the name is what
        // there is to go on.
        namespaceName != null &&
                (currentScope.allowedTagLibs.contains(namespaceName) ||
                        currentScope.pageScopeVariables.contains(namespaceName))
    }

    def isThisTheReceiver(expr) {
        expr.implicitThis || (expr.objectExpression instanceof VariableExpression && expr.objectExpression.thisExpression)
    }
}

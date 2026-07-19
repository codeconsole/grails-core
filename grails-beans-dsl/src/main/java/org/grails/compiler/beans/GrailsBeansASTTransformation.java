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
package org.grails.compiler.beans;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.transform.ASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Rewrites the {@code beans} closure DSL on a {@link grails.compiler.beans.GrailsBeans}-annotated
 * class into real {@code @Bean} factory methods, at compile time.
 *
 * <p>Recognises statements shaped as {@code bean(Type[, "name"]) { ... }}, optionally chained
 * with {@code .conditionalOnMissingBean(Type...)}. For each one it synthesises a public method
 * named after the bean, returning the declared type, annotated {@code @org.springframework.
 * context.annotation.Bean} (and {@code @ConditionalOnMissingBean} when chained), whose body and
 * parameters are lifted directly from the DSL closure. The {@code beans} property itself is
 * removed so no closure survives into the compiled class.
 */
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
public class GrailsBeansASTTransformation implements ASTTransformation {

    private static final String BEANS_PROPERTY = "beans";
    private static final String BEAN_CALL = "bean";
    private static final String CONDITIONAL_ON_MISSING_BEAN_CALL = "conditionalOnMissingBean";

    @Override
    public void visit(ASTNode[] nodes, SourceUnit source) {
        ClassNode classNode = (ClassNode) nodes[1];
        PropertyNode beansProperty = classNode.getProperty(BEANS_PROPERTY);
        if (beansProperty == null) {
            addError(classNode, source, "@GrailsBeans requires a 'beans' property initialised to a closure");
            return;
        }

        Expression initialExpression = beansProperty.getInitialExpression();
        if (!(initialExpression instanceof ClosureExpression)) {
            addError(beansProperty, source, "'beans' must be initialised to a closure, e.g. beans = { ... }");
            return;
        }

        for (Statement statement : beanStatements((ClosureExpression) initialExpression)) {
            processBeanStatement(classNode, statement, source);
        }

        classNode.getProperties().remove(beansProperty);
        classNode.getFields().removeIf(field -> field.getName().equals(BEANS_PROPERTY));
    }

    private List<Statement> beanStatements(ClosureExpression dsl) {
        Statement code = dsl.getCode();
        if (code instanceof BlockStatement) {
            return ((BlockStatement) code).getStatements();
        }
        List<Statement> single = new ArrayList<>();
        single.add(code);
        return single;
    }

    private void processBeanStatement(ClassNode classNode, Statement statement, SourceUnit source) {
        if (!(statement instanceof ExpressionStatement) ||
                !(((ExpressionStatement) statement).getExpression() instanceof MethodCallExpression)) {
            addError(statement, source, "Each 'beans' statement must be a bean(...) call");
            return;
        }

        MethodCallExpression outerCall = (MethodCallExpression) ((ExpressionStatement) statement).getExpression();

        MethodCallExpression qualifierCall = null;
        MethodCallExpression baseCall;
        if (BEAN_CALL.equals(outerCall.getMethodAsString())) {
            baseCall = outerCall;
        }
        else if (CONDITIONAL_ON_MISSING_BEAN_CALL.equals(outerCall.getMethodAsString()) &&
                outerCall.getObjectExpression() instanceof MethodCallExpression &&
                BEAN_CALL.equals(((MethodCallExpression) outerCall.getObjectExpression()).getMethodAsString())) {
            qualifierCall = outerCall;
            baseCall = (MethodCallExpression) outerCall.getObjectExpression();
        }
        else {
            addError(statement, source, "Expected bean(Type[, \"name\"]) { ... } " +
                    "optionally chained with .conditionalOnMissingBean(Type...)");
            return;
        }

        List<Expression> baseArgs = flatten(baseCall.getArguments());
        if (baseArgs.isEmpty() || !(baseArgs.get(0) instanceof ClassExpression)) {
            addError(baseCall, source, "bean(...) requires a bean type as its first argument, e.g. bean(Greeter)");
            return;
        }
        ClassExpression beanType = (ClassExpression) baseArgs.get(0);
        String beanName = baseArgs.size() > 1 && baseArgs.get(1) instanceof ConstantExpression ?
                String.valueOf(((ConstantExpression) baseArgs.get(1)).getValue()) :
                decapitalize(beanType.getType().getNameWithoutPackage());

        MethodCallExpression callCarryingClosure = qualifierCall != null ? qualifierCall : baseCall;
        List<Expression> closureCallArgs = flatten(callCarryingClosure.getArguments());
        if (closureCallArgs.isEmpty() || !(closureCallArgs.get(closureCallArgs.size() - 1) instanceof ClosureExpression)) {
            addError(callCarryingClosure, source, "bean(...) must end with a factory closure: bean(Type) { ... }");
            return;
        }
        ClosureExpression factory = (ClosureExpression) closureCallArgs.get(closureCallArgs.size() - 1);

        MethodNode beanMethod = new MethodNode(
                beanName,
                Modifier.PUBLIC,
                beanType.getType(),
                factory.getParameters() == null ? Parameter.EMPTY_ARRAY : factory.getParameters(),
                ClassNode.EMPTY_ARRAY,
                factory.getCode());
        beanMethod.addAnnotation(beanAnnotation(beanName));

        if (qualifierCall != null) {
            List<Expression> qualifierArgs = flatten(qualifierCall.getArguments());
            // drop the trailing factory closure, keep the conditional's own Type... arguments
            List<Expression> conditionalTypes = qualifierArgs.subList(0, qualifierArgs.size() - 1);
            beanMethod.addAnnotation(conditionalOnMissingBeanAnnotation(conditionalTypes, source, qualifierCall));
        }

        classNode.addMethod(beanMethod);
    }

    private List<Expression> flatten(Expression arguments) {
        List<Expression> result = new ArrayList<>();
        if (arguments instanceof org.codehaus.groovy.ast.expr.TupleExpression) {
            result.addAll(((org.codehaus.groovy.ast.expr.TupleExpression) arguments).getExpressions());
        }
        else {
            result.add(arguments);
        }
        return result;
    }

    private AnnotationNode beanAnnotation(String beanName) {
        AnnotationNode annotation = new AnnotationNode(ClassHelper.make(Bean.class));
        ListExpression names = new ListExpression();
        names.addExpression(new ConstantExpression(beanName));
        annotation.setMember("value", names);
        return annotation;
    }

    private AnnotationNode conditionalOnMissingBeanAnnotation(List<Expression> types, SourceUnit source, ASTNode context) {
        AnnotationNode annotation = new AnnotationNode(ClassHelper.make(ConditionalOnMissingBean.class));
        ListExpression typeList = new ListExpression();
        for (Expression type : types) {
            if (!(type instanceof ClassExpression)) {
                addError(context, source, "conditionalOnMissingBean(...) arguments must be types, e.g. conditionalOnMissingBean(Greeter)");
                continue;
            }
            typeList.addExpression(type);
        }
        annotation.setMember("value", typeList);
        return annotation;
    }

    private String decapitalize(String name) {
        if (name.isEmpty()) {
            return name;
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private void addError(ASTNode node, SourceUnit source, String message) {
        source.getErrorCollector().addErrorAndContinue(
                new org.codehaus.groovy.control.messages.SyntaxErrorMessage(
                        new SyntaxException(message, node.getLineNumber(), node.getColumnNumber()), source));
    }

}

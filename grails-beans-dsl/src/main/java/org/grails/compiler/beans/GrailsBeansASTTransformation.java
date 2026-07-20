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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import groovy.transform.CompilationUnitAware;
import groovy.transform.CompileStatic;
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
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.SyntaxException;
import org.codehaus.groovy.transform.ASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformation;
import org.codehaus.groovy.transform.sc.StaticCompileTransformation;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;

/**
 * Rewrites the {@code beans} closure DSL on a {@link grails.compiler.beans.GrailsBeans}-annotated
 * class into real {@code @Bean} factory methods, at compile time.
 *
 * <p>Recognises statements shaped as {@code bean(Type[, "name"]) { ... }}, optionally chained with
 * any combination of {@code .conditionalOnMissingBean(Type...)}, {@code .primary()},
 * {@code .lazy()}, and {@code .scope("name")}. For each one it synthesises a public method named
 * after the bean, returning the declared type, annotated {@code @org.springframework.context.
 * annotation.Bean} plus whichever of {@code @ConditionalOnMissingBean}, {@code @Primary},
 * {@code @Lazy}, and {@code @Scope} were chained, whose body and parameters are lifted directly
 * from the DSL closure. The {@code beans} property itself is removed so no closure survives into
 * the compiled class.
 *
 * <p>When the annotated class extends {@code grails.plugins.Plugin}, the generated methods land
 * on a new sibling {@code <PluginClassName>AutoConfiguration} class in the same package instead
 * of on the plugin class itself - a {@code Plugin} subclass is instantiated by
 * {@code DefaultGrailsPlugin} via plain reflection, never as a Spring bean, so it cannot carry
 * {@code @Bean} methods or a meaningful {@code @AutoConfiguration} annotation of its own. Any
 * {@code @AutoConfiguration} annotation found on the plugin class is moved onto the generated
 * sibling, since that is the only place it has any effect. This lets a plugin author keep bean
 * definitions in the familiar {@code *GrailsPlugin.groovy} file while everything else about the
 * plugin class - {@code doWithApplicationContext}, {@code onChange}, {@code watchedResources},
 * etc. - continues to work exactly as it does today.
 *
 * <p>{@code @CompileStatic}/{@code @GrailsCompileStatic} on the plugin class is propagated to the
 * generated sibling. Since the sibling is created after Groovy schedules local annotation
 * transforms, this transformation invokes Groovy's static-compilation transform directly after
 * generating the sibling's methods. This is the same approach used by other Grails AST transforms
 * that generate code after local transform discovery.
 */
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
public class GrailsBeansASTTransformation implements ASTTransformation, CompilationUnitAware {

    private static final String BEANS_PROPERTY = "beans";
    private static final String BEAN_CALL = "bean";
    private static final String CONDITIONAL_ON_MISSING_BEAN_CALL = "conditionalOnMissingBean";
    private static final String PRIMARY_CALL = "primary";
    private static final String LAZY_CALL = "lazy";
    private static final String SCOPE_CALL = "scope";
    private static final Set<String> QUALIFIER_CALL_NAMES = Set.of(
            CONDITIONAL_ON_MISSING_BEAN_CALL, PRIMARY_CALL, LAZY_CALL, SCOPE_CALL);
    private static final String PLUGIN_SUPERCLASS_NAME = "grails.plugins.Plugin";
    private static final String AUTO_CONFIGURATION_SUFFIX = "AutoConfiguration";

    private CompilationUnit compilationUnit;

    @Override
    public void setCompilationUnit(CompilationUnit compilationUnit) {
        this.compilationUnit = compilationUnit;
    }

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

        ClassNode beanMethodHost = extendsGrailsPlugin(classNode) ?
                createAutoConfigurationSibling(classNode, source) : classNode;

        for (Statement statement : beanStatements((ClosureExpression) initialExpression)) {
            processBeanStatement(beanMethodHost, statement, source);
        }

        if (beanMethodHost != classNode) {
            applyStaticCompilation(classNode, beanMethodHost, source);
        }

        classNode.getProperties().remove(beansProperty);
        classNode.getFields().removeIf(field -> field.getName().equals(BEANS_PROPERTY));
    }

    private boolean extendsGrailsPlugin(ClassNode classNode) {
        for (ClassNode current = classNode.getSuperClass(); current != null; current = current.getSuperClass()) {
            if (PLUGIN_SUPERCLASS_NAME.equals(current.getName())) {
                return true;
            }
        }
        return false;
    }

    private ClassNode createAutoConfigurationSibling(ClassNode pluginClass, SourceUnit source) {
        List<AnnotationNode> autoConfigurationAnnotations = pluginClass.getAnnotations(ClassHelper.make(AutoConfiguration.class));
        if (autoConfigurationAnnotations.isEmpty()) {
            addError(pluginClass, source, "A Plugin class using @GrailsBeans must also be annotated " +
                    "@AutoConfiguration (even with no before=/after=) - otherwise the generated " +
                    pluginClass.getNameWithoutPackage() + AUTO_CONFIGURATION_SUFFIX +
                    " class would never be processed by Spring Boot");
        }

        ClassNode sibling = new ClassNode(pluginClass.getName() + AUTO_CONFIGURATION_SUFFIX,
                Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
        source.getAST().addClass(sibling);

        // @AutoConfiguration is meaningless on a Plugin subclass (Spring never processes it as
        // a bean), so it moves to the sibling entirely rather than being merely copied.
        sibling.addAnnotations(autoConfigurationAnnotations);
        pluginClass.getAnnotations().removeAll(autoConfigurationAnnotations);

        return sibling;
    }

    private void applyStaticCompilation(ClassNode pluginClass, ClassNode sibling, SourceUnit source) {
        List<AnnotationNode> annotations = pluginClass.getAnnotations(ClassHelper.make(CompileStatic.class));
        if (annotations.isEmpty() || compilationUnit == null) {
            return;
        }

        AnnotationNode sourceAnnotation = annotations.get(0);
        AnnotationNode siblingAnnotation = new AnnotationNode(ClassHelper.make(CompileStatic.class));
        sourceAnnotation.getMembers().forEach(siblingAnnotation::setMember);
        sibling.addAnnotation(siblingAnnotation);

        StaticCompileTransformation transformation = new StaticCompileTransformation();
        transformation.setCompilationUnit(compilationUnit);
        transformation.visit(new ASTNode[] { siblingAnnotation, sibling }, source);
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

        // Walk from the outermost (last-written) call back to the bean(...) call at the root,
        // collecting any chained qualifiers - .conditionalOnMissingBean(...), .primary(), .lazy(),
        // .scope(...) - in source order along the way.
        List<MethodCallExpression> qualifierCalls = new ArrayList<>();
        MethodCallExpression baseCall = outerCall;
        while (!BEAN_CALL.equals(baseCall.getMethodAsString())) {
            if (!QUALIFIER_CALL_NAMES.contains(baseCall.getMethodAsString()) ||
                    !(baseCall.getObjectExpression() instanceof MethodCallExpression)) {
                addError(statement, source, "Expected bean(Type[, \"name\"]) { ... } optionally chained with " +
                        ".conditionalOnMissingBean(Type...), .primary(), .lazy(), and/or .scope(\"name\")");
                return;
            }
            qualifierCalls.add(0, baseCall);
            baseCall = (MethodCallExpression) baseCall.getObjectExpression();
        }

        Set<String> seenQualifiers = new HashSet<>();
        for (MethodCallExpression qualifierCall : qualifierCalls) {
            if (!seenQualifiers.add(qualifierCall.getMethodAsString())) {
                addError(qualifierCall, source, "." + qualifierCall.getMethodAsString() +
                        "(...) may only be chained once per bean(...)");
                return;
            }
        }

        List<Expression> closureCallArgs = flatten(outerCall.getArguments());
        if (closureCallArgs.isEmpty() || !(closureCallArgs.get(closureCallArgs.size() - 1) instanceof ClosureExpression)) {
            addError(outerCall, source, "bean(...) must end with a factory closure: bean(Type) { ... }");
            return;
        }
        ClosureExpression factory = (ClosureExpression) closureCallArgs.get(closureCallArgs.size() - 1);

        // When bean(...) is itself the outermost call (no qualifiers chained), it carries the
        // trailing closure as its own last argument - exclude it before validating the Type[, name]
        // shape, since it was already validated above.
        List<Expression> baseArgs = flatten(baseCall.getArguments());
        if (baseCall == outerCall && !baseArgs.isEmpty()) {
            baseArgs = baseArgs.subList(0, baseArgs.size() - 1);
        }

        if (baseArgs.isEmpty() || baseArgs.size() > 2 || !(baseArgs.get(0) instanceof ClassExpression)) {
            addError(baseCall, source, "bean(...) requires a bean type as its first argument and at most " +
                    "one bean name, e.g. bean(Greeter) or bean(Greeter, \"myGreeter\")");
            return;
        }
        ClassExpression beanType = (ClassExpression) baseArgs.get(0);

        String beanName;
        if (baseArgs.size() == 1) {
            beanName = decapitalize(beanType.getType().getNameWithoutPackage());
        }
        else {
            Expression nameArg = baseArgs.get(1);
            Object nameValue = nameArg instanceof ConstantExpression ? ((ConstantExpression) nameArg).getValue() : null;
            if (!(nameValue instanceof String)) {
                addError(nameArg, source, "bean(Type, name) requires name to be a String literal, " +
                        "e.g. bean(Greeter, \"myGreeter\")");
                return;
            }
            beanName = (String) nameValue;
            if (!isValidJavaIdentifier(beanName)) {
                addError(nameArg, source, "\"" + beanName + "\" is not a valid bean name: it becomes the " +
                        "generated method's name, so it must be a valid Java identifier");
                return;
            }
        }

        MethodNode beanMethod = new MethodNode(
                beanName,
                Modifier.PUBLIC,
                beanType.getType(),
                factory.getParameters() == null ? Parameter.EMPTY_ARRAY : factory.getParameters(),
                ClassNode.EMPTY_ARRAY,
                factory.getCode());
        beanMethod.addAnnotation(beanAnnotation(beanName));

        for (MethodCallExpression qualifierCall : qualifierCalls) {
            List<Expression> qualifierArgs = flatten(qualifierCall.getArguments());
            if (qualifierCall == outerCall) {
                // only the outermost call in the chain can carry the trailing factory closure
                qualifierArgs = qualifierArgs.subList(0, qualifierArgs.size() - 1);
            }
            if (!applyQualifier(beanMethod, qualifierCall, qualifierArgs, source)) {
                return;
            }
        }

        classNode.addMethod(beanMethod);
    }

    private boolean applyQualifier(MethodNode beanMethod, MethodCallExpression qualifierCall,
            List<Expression> args, SourceUnit source) {
        String name = qualifierCall.getMethodAsString();
        if (CONDITIONAL_ON_MISSING_BEAN_CALL.equals(name)) {
            beanMethod.addAnnotation(conditionalOnMissingBeanAnnotation(args, source, qualifierCall));
            return true;
        }
        if (PRIMARY_CALL.equals(name) || LAZY_CALL.equals(name)) {
            if (!args.isEmpty()) {
                addError(qualifierCall, source, "." + name + "() takes no arguments");
                return false;
            }
            Class<?> annotationType = PRIMARY_CALL.equals(name) ? Primary.class : Lazy.class;
            beanMethod.addAnnotation(new AnnotationNode(ClassHelper.make(annotationType)));
            return true;
        }
        return applyScopeQualifier(beanMethod, qualifierCall, args, source);
    }

    private boolean applyScopeQualifier(MethodNode beanMethod, MethodCallExpression qualifierCall,
            List<Expression> args, SourceUnit source) {
        Expression scopeArg = args.size() == 1 ? args.get(0) : null;
        Object scopeValue = scopeArg instanceof ConstantExpression ? ((ConstantExpression) scopeArg).getValue() : null;
        if (!(scopeValue instanceof String) || ((String) scopeValue).isEmpty()) {
            addError(qualifierCall, source, ".scope(...) requires exactly one non-empty String argument, " +
                    "e.g. .scope(\"prototype\")");
            return false;
        }
        AnnotationNode scopeAnnotation = new AnnotationNode(ClassHelper.make(Scope.class));
        scopeAnnotation.setMember("value", scopeArg);
        beanMethod.addAnnotation(scopeAnnotation);
        return true;
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

    private boolean isValidJavaIdentifier(String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private void addError(ASTNode node, SourceUnit source, String message) {
        source.getErrorCollector().addErrorAndContinue(
                new org.codehaus.groovy.control.messages.SyntaxErrorMessage(
                        new SyntaxException(message, node.getLineNumber(), node.getColumnNumber()), source));
    }

}

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
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
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
 * <p>Recognises three kinds of top-level statement inside the {@code beans} closure:
 * <ul>
 * <li>{@code bean(Type[, "name"]) { ... }}, optionally chained with any combination of
 * {@code .conditionalOnMissingBean(Type...)}, {@code .primary()}, {@code .lazy()},
 * {@code .scope("name")}, and (repeatably) {@code .annotate(AnnotationType[, attr: value, ...])}.
 * Synthesises a public method named after the bean, returning the declared type, annotated
 * {@code @org.springframework.context.annotation.Bean} plus whichever qualifiers were chained,
 * whose body and parameters are lifted directly from the DSL closure.</li>
 * <li>{@code field(Type[, "name"])}, optionally chained (repeatably) with
 * {@code .annotate(AnnotationType[, attr: value, ...])} - typically {@code .annotate(Value, value:
 * "${...}")}. Declares a private field on the generated class, for state shared across bean
 * methods (e.g. injected configuration).</li>
 * <li>{@code method(Type[, "name"]) { ... }}, with the same chaining as {@code field(...)}.
 * Declares a private helper method on the generated class, for logic shared across bean methods,
 * lifted from the DSL closure the same way {@code bean(...)} is.</li>
 * </ul>
 *
 * <p>Fields and helper methods declared this way are ordinary private members of the generated
 * class - {@code bean(...)} closures reference them the same way a hand-written {@code @Bean}
 * method would reference a sibling field or method on its {@code @Configuration} class. The
 * {@code beans} property itself is removed so no closure survives into the compiled class.
 *
 * <p>When the annotated class extends {@code grails.plugins.Plugin}, the generated members land
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
 * generating the sibling's members. This is the same approach used by other Grails AST transforms
 * that generate code after local transform discovery.
 */
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
public class GrailsBeansASTTransformation implements ASTTransformation, CompilationUnitAware {

    private static final String BEANS_PROPERTY = "beans";
    private static final String BEAN_CALL = "bean";
    private static final String FIELD_CALL = "field";
    private static final String METHOD_CALL = "method";
    private static final Set<String> ROOT_STATEMENT_CALL_NAMES = Set.of(BEAN_CALL, FIELD_CALL, METHOD_CALL);
    private static final String CONDITIONAL_ON_MISSING_BEAN_CALL = "conditionalOnMissingBean";
    private static final String PRIMARY_CALL = "primary";
    private static final String LAZY_CALL = "lazy";
    private static final String SCOPE_CALL = "scope";
    private static final String ANNOTATE_CALL = "annotate";
    private static final Set<String> QUALIFIER_CALL_NAMES = Set.of(
            CONDITIONAL_ON_MISSING_BEAN_CALL, PRIMARY_CALL, LAZY_CALL, SCOPE_CALL, ANNOTATE_CALL);
    // field(...) and method(...) declare plain class members, not beans - only .annotate(...)
    // (attaching an arbitrary annotation, e.g. @Value) makes sense on them.
    private static final Set<String> MEMBER_QUALIFIER_CALL_NAMES = Set.of(ANNOTATE_CALL);
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
            processStatement(beanMethodHost, statement, source);
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

    private void processStatement(ClassNode classNode, Statement statement, SourceUnit source) {
        if (!(statement instanceof ExpressionStatement) ||
                !(((ExpressionStatement) statement).getExpression() instanceof MethodCallExpression)) {
            addError(statement, source, "Each 'beans' statement must be a bean(...), field(...), or method(...) call");
            return;
        }

        MethodCallExpression outerCall = (MethodCallExpression) ((ExpressionStatement) statement).getExpression();

        // Walk from the outermost (last-written) call back to the bean(...)/field(...)/method(...)
        // call at the root, collecting any chained qualifiers along the way.
        List<MethodCallExpression> qualifierCalls = new ArrayList<>();
        MethodCallExpression baseCall = outerCall;
        while (!ROOT_STATEMENT_CALL_NAMES.contains(baseCall.getMethodAsString())) {
            if (!QUALIFIER_CALL_NAMES.contains(baseCall.getMethodAsString()) ||
                    !(baseCall.getObjectExpression() instanceof MethodCallExpression)) {
                addError(statement, source, "Expected bean(Type[, \"name\"]) { ... }, field(Type[, \"name\"]), " +
                        "or method(Type[, \"name\"]) { ... }, optionally chained with qualifiers");
                return;
            }
            qualifierCalls.add(0, baseCall);
            baseCall = (MethodCallExpression) baseCall.getObjectExpression();
        }

        String rootName = baseCall.getMethodAsString();
        boolean isBean = BEAN_CALL.equals(rootName);
        Set<String> allowedQualifiers = isBean ? QUALIFIER_CALL_NAMES : MEMBER_QUALIFIER_CALL_NAMES;
        for (MethodCallExpression qualifierCall : qualifierCalls) {
            if (!allowedQualifiers.contains(qualifierCall.getMethodAsString())) {
                addError(qualifierCall, source, "." + qualifierCall.getMethodAsString() + "(...) cannot be " +
                        "chained onto " + rootName + "(...) - only .annotate(...) can be");
                return;
            }
        }

        // .annotate(...) is repeatable (once per distinct annotation type, enforced when the
        // annotation is actually attached below); every other qualifier is single-use.
        Set<String> seenQualifiers = new HashSet<>();
        for (MethodCallExpression qualifierCall : qualifierCalls) {
            String qualifierName = qualifierCall.getMethodAsString();
            if (!ANNOTATE_CALL.equals(qualifierName) && !seenQualifiers.add(qualifierName)) {
                addError(qualifierCall, source, "." + qualifierName + "(...) may only be chained once per " +
                        rootName + "(...)");
                return;
            }
        }

        if (isBean) {
            processBeanStatement(classNode, outerCall, baseCall, qualifierCalls, source);
        }
        else if (FIELD_CALL.equals(rootName)) {
            processFieldStatement(classNode, baseCall, qualifierCalls, source);
        }
        else {
            processMethodStatement(classNode, outerCall, baseCall, qualifierCalls, source);
        }
    }

    private void processBeanStatement(ClassNode classNode, MethodCallExpression outerCall, MethodCallExpression baseCall,
            List<MethodCallExpression> qualifierCalls, SourceUnit source) {
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

        TypeAndName typeAndName = parseTypeAndName(baseArgs, baseCall, source, BEAN_CALL);
        if (typeAndName == null) {
            return;
        }

        MethodNode beanMethod = new MethodNode(
                typeAndName.name,
                Modifier.PUBLIC,
                typeAndName.type.getType(),
                factory.getParameters() == null ? Parameter.EMPTY_ARRAY : factory.getParameters(),
                ClassNode.EMPTY_ARRAY,
                factory.getCode());
        beanMethod.addAnnotation(beanAnnotation(typeAndName.name));

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

    private void processFieldStatement(ClassNode classNode, MethodCallExpression baseCall,
            List<MethodCallExpression> qualifierCalls, SourceUnit source) {
        List<Expression> baseArgs = flatten(baseCall.getArguments());
        TypeAndName typeAndName = parseTypeAndName(baseArgs, baseCall, source, FIELD_CALL);
        if (typeAndName == null) {
            return;
        }

        FieldNode field = classNode.addField(typeAndName.name, Modifier.PRIVATE, typeAndName.type.getType(), null);

        for (MethodCallExpression qualifierCall : qualifierCalls) {
            List<Expression> qualifierArgs = flatten(qualifierCall.getArguments());
            if (!applyGenericAnnotation(field, qualifierCall, qualifierArgs, source)) {
                return;
            }
        }
    }

    private void processMethodStatement(ClassNode classNode, MethodCallExpression outerCall, MethodCallExpression baseCall,
            List<MethodCallExpression> qualifierCalls, SourceUnit source) {
        List<Expression> closureCallArgs = flatten(outerCall.getArguments());
        if (closureCallArgs.isEmpty() || !(closureCallArgs.get(closureCallArgs.size() - 1) instanceof ClosureExpression)) {
            addError(outerCall, source, "method(...) must end with a body closure: method(Type, \"name\") { ... }");
            return;
        }
        ClosureExpression body = (ClosureExpression) closureCallArgs.get(closureCallArgs.size() - 1);

        List<Expression> baseArgs = flatten(baseCall.getArguments());
        if (baseCall == outerCall && !baseArgs.isEmpty()) {
            baseArgs = baseArgs.subList(0, baseArgs.size() - 1);
        }

        TypeAndName typeAndName = parseTypeAndName(baseArgs, baseCall, source, METHOD_CALL);
        if (typeAndName == null) {
            return;
        }

        MethodNode helperMethod = new MethodNode(
                typeAndName.name,
                Modifier.PRIVATE,
                typeAndName.type.getType(),
                body.getParameters() == null ? Parameter.EMPTY_ARRAY : body.getParameters(),
                ClassNode.EMPTY_ARRAY,
                body.getCode());

        for (MethodCallExpression qualifierCall : qualifierCalls) {
            List<Expression> qualifierArgs = flatten(qualifierCall.getArguments());
            if (qualifierCall == outerCall) {
                qualifierArgs = qualifierArgs.subList(0, qualifierArgs.size() - 1);
            }
            if (!applyGenericAnnotation(helperMethod, qualifierCall, qualifierArgs, source)) {
                return;
            }
        }

        classNode.addMethod(helperMethod);
    }

    private static final class TypeAndName {
        private final ClassExpression type;
        private final String name;

        TypeAndName(ClassExpression type, String name) {
            this.type = type;
            this.name = name;
        }
    }

    private TypeAndName parseTypeAndName(List<Expression> args, MethodCallExpression call, SourceUnit source, String callName) {
        if (args.isEmpty() || args.size() > 2 || !(args.get(0) instanceof ClassExpression)) {
            addError(call, source, callName + "(...) requires a type as its first argument and at most one " +
                    "name, e.g. " + callName + "(Greeter) or " + callName + "(Greeter, \"myGreeter\")");
            return null;
        }
        ClassExpression type = (ClassExpression) args.get(0);

        String name;
        if (args.size() == 1) {
            name = decapitalize(type.getType().getNameWithoutPackage());
        }
        else {
            Expression nameArg = args.get(1);
            Object nameValue = nameArg instanceof ConstantExpression ? ((ConstantExpression) nameArg).getValue() : null;
            if (!(nameValue instanceof String)) {
                addError(nameArg, source, callName + "(Type, name) requires name to be a String literal, " +
                        "e.g. " + callName + "(Greeter, \"myGreeter\")");
                return null;
            }
            name = (String) nameValue;
            if (!isValidJavaIdentifier(name)) {
                addError(nameArg, source, "\"" + name + "\" is not a valid name: it becomes the generated " +
                        "member's name, so it must be a valid Java identifier");
                return null;
            }
        }
        return new TypeAndName(type, name);
    }

    private boolean applyQualifier(MethodNode beanMethod, MethodCallExpression qualifierCall,
            List<Expression> args, SourceUnit source) {
        String name = qualifierCall.getMethodAsString();
        if (CONDITIONAL_ON_MISSING_BEAN_CALL.equals(name)) {
            return addAnnotationIfAbsent(beanMethod, qualifierCall,
                    conditionalOnMissingBeanAnnotation(args, source, qualifierCall), source);
        }
        if (PRIMARY_CALL.equals(name) || LAZY_CALL.equals(name)) {
            if (!args.isEmpty()) {
                addError(qualifierCall, source, "." + name + "() takes no arguments");
                return false;
            }
            Class<?> annotationType = PRIMARY_CALL.equals(name) ? Primary.class : Lazy.class;
            return addAnnotationIfAbsent(beanMethod, qualifierCall,
                    new AnnotationNode(ClassHelper.make(annotationType)), source);
        }
        if (SCOPE_CALL.equals(name)) {
            return applyScopeQualifier(beanMethod, qualifierCall, args, source);
        }
        return applyGenericAnnotation(beanMethod, qualifierCall, args, source);
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
        return addAnnotationIfAbsent(beanMethod, qualifierCall, scopeAnnotation, source);
    }

    private boolean applyGenericAnnotation(AnnotatedNode target, MethodCallExpression qualifierCall,
            List<Expression> args, SourceUnit source) {
        MapExpression members = !args.isEmpty() && args.get(0) instanceof MapExpression ? (MapExpression) args.get(0) : null;
        List<Expression> remaining = members != null ? args.subList(1, args.size()) : args;
        if (remaining.size() != 1 || !(remaining.get(0) instanceof ClassExpression)) {
            addError(qualifierCall, source, ".annotate(...) requires an annotation type as its only " +
                    "positional argument, e.g. .annotate(Order, value: 1) or .annotate(ConditionalOnWebApplication)");
            return false;
        }

        ClassNode annotationType = ((ClassExpression) remaining.get(0)).getType();
        if (!annotationType.isAnnotationDefinition()) {
            addError(qualifierCall, source, "\"" + annotationType.getName() + "\" is not an annotation type");
            return false;
        }

        AnnotationNode annotation = new AnnotationNode(annotationType);
        if (members != null) {
            for (MapEntryExpression entry : members.getMapEntryExpressions()) {
                Object keyValue = entry.getKeyExpression() instanceof ConstantExpression ?
                        ((ConstantExpression) entry.getKeyExpression()).getValue() : null;
                if (!(keyValue instanceof String)) {
                    addError(qualifierCall, source, ".annotate(...) attribute names must be simple identifiers, " +
                            "e.g. .annotate(Order, value: 1)");
                    return false;
                }
                annotation.setMember((String) keyValue, entry.getValueExpression());
            }
        }
        return addAnnotationIfAbsent(target, qualifierCall, annotation, source);
    }

    private boolean addAnnotationIfAbsent(AnnotatedNode target, MethodCallExpression qualifierCall,
            AnnotationNode annotation, SourceUnit source) {
        ClassNode type = annotation.getClassNode();
        if (!target.getAnnotations(type).isEmpty()) {
            addError(qualifierCall, source, "@" + type.getNameWithoutPackage() + " is already attached " +
                    "here, via an earlier qualifier or .annotate(...)");
            return false;
        }
        target.addAnnotation(annotation);
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

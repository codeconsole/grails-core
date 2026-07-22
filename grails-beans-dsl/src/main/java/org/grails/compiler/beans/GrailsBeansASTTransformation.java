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

import java.beans.Introspector;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.lang.model.SourceVersion;

import groovy.transform.CompilationUnitAware;
import groovy.transform.CompileStatic;
import org.apache.groovy.util.BeanUtils;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.BinaryExpression;
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
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;
import org.codehaus.groovy.transform.ASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformation;
import org.codehaus.groovy.transform.sc.StaticCompileTransformation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScans;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportResource;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.PropertySources;
import org.springframework.context.annotation.Scope;

/**
 * Rewrites the {@code beans} closure DSL on a {@link grails.compiler.beans.GrailsBeans}-annotated
 * class into real {@code @Bean} factory methods, at compile time.
 *
 * <p>Recognises three kinds of top-level statement inside the {@code beans} closure:
 * <ul>
 * <li>{@code bean(Type[, "name"]) { ... }}, optionally chained with any combination of
 * {@code .conditionalOnMissingBean(...)} (positional types, named annotation attributes, or bare),
 * {@code .conditionalOnMissingBeanName(...)} (backs off by this bean's own name, set
 * automatically), {@code .primary()}, {@code .lazy()},
 * {@code .scope("name")}, and (repeatably)
 * {@code .annotate(AnnotationType[, attr: value, ...])}. Synthesises a public method, returning
 * the declared type, annotated {@code @org.springframework.context.annotation.Bean("name")} plus
 * whichever qualifiers were chained, whose body and parameters are lifted directly from the DSL
 * closure. The generated method's name is an implementation detail: it matches the bean name when
 * that is a usable Java identifier not already taken by an existing or generated member, and
 * falls back to a synthesized {@code <type>$N} name otherwise (a non-identifier name like
 * {@code "my-service"}, a reserved keyword, or a collision - a bean named {@code toString} never
 * overrides {@code Object.toString()}) - Spring resolves the bean by its {@code @Bean("name")}
 * value either way, never by the method name.</li>
 * <li>{@code field(Type[, "name"])}, optionally chained with {@code .value(...)} (config
 * injection: key + default, or one verbatim placeholder/SpEL string) and/or (repeatably)
 * {@code .annotate(AnnotationType[, attr: value, ...])}. Declares a private field on the
 * generated class, for state shared across bean methods.</li>
 * <li>{@code method(Type[, "name"]) { ... }}, chainable with {@code .annotate(...)} only
 * ({@code .value(...)} is field-specific).
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
 * on a new sibling class in the same package instead of on the plugin class itself - named by
 * swapping a {@code *GrailsPlugin} suffix for {@code AutoConfiguration}, or appending
 * {@code AutoConfiguration} otherwise. A {@code Plugin} subclass is instantiated by
 * {@code DefaultGrailsPlugin} via plain reflection, never as a Spring bean, so it cannot carry
 * {@code @Bean} methods or a meaningful {@code @AutoConfiguration} annotation of its own.
 * {@code @AutoConfiguration} and every annotation that gates or configures it - the
 * {@code @Conditional*} family, {@code @Import}/{@code @ImportAutoConfiguration}/
 * {@code @ImportResource}, {@code @ComponentScan}, {@code @EnableConfigurationProperties},
 * {@code @PropertySource}/{@code @PropertySources}, and
 * {@code @AutoConfigureOrder}/{@code Before}/{@code After} - found on the plugin class are moved
 * onto the generated sibling, since that is the only place any of them has any effect; annotations
 * outside that set can be named explicitly via {@code @GrailsBeans(moveAnnotations = ...)}. This lets a
 * plugin author keep bean definitions in the familiar {@code *GrailsPlugin.groovy} file while
 * everything else about the plugin class - {@code doWithApplicationContext}, {@code onChange},
 * {@code watchedResources}, etc. - continues to work exactly as it does today.
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
    private static final String CONDITIONAL_ON_MISSING_BEAN_NAME_CALL = "conditionalOnMissingBeanName";
    private static final String PRIMARY_CALL = "primary";
    private static final String LAZY_CALL = "lazy";
    private static final String SCOPE_CALL = "scope";
    private static final String ANNOTATE_CALL = "annotate";
    private static final String VALUE_CALL = "value";
    private static final Set<String> BEAN_QUALIFIER_CALL_NAMES = Set.of(
            CONDITIONAL_ON_MISSING_BEAN_CALL, CONDITIONAL_ON_MISSING_BEAN_NAME_CALL,
            PRIMARY_CALL, LAZY_CALL, SCOPE_CALL, ANNOTATE_CALL);
    // field(...) and method(...) declare plain class members, not beans - bean-specific
    // qualifiers don't apply; .value(...) (@Value config injection) is field-only.
    private static final Set<String> FIELD_QUALIFIER_CALL_NAMES = Set.of(ANNOTATE_CALL, VALUE_CALL);
    private static final Set<String> METHOD_QUALIFIER_CALL_NAMES = Set.of(ANNOTATE_CALL);
    private static final Set<String> ALL_QUALIFIER_CALL_NAMES = Set.of(
            CONDITIONAL_ON_MISSING_BEAN_CALL, CONDITIONAL_ON_MISSING_BEAN_NAME_CALL,
            PRIMARY_CALL, LAZY_CALL, SCOPE_CALL, ANNOTATE_CALL, VALUE_CALL);
    private static final String PLUGIN_SUPERCLASS_NAME = "grails.plugins.Plugin";
    private static final String GRAILS_PLUGIN_SUFFIX = "GrailsPlugin";
    private static final String AUTO_CONFIGURATION_SUFFIX = "AutoConfiguration";
    private static final String AUTO_CONFIGURATION_NAME_MEMBER = "autoConfigurationName";
    private static final String MOVE_ANNOTATIONS_MEMBER = "moveAnnotations";

    private CompilationUnit compilationUnit;

    @Override
    public void setCompilationUnit(CompilationUnit compilationUnit) {
        this.compilationUnit = compilationUnit;
    }

    @Override
    public void visit(ASTNode[] nodes, SourceUnit source) {
        AnnotationNode grailsBeansAnnotation = (AnnotationNode) nodes[0];
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

        boolean isPlugin = extendsGrailsPlugin(classNode);
        if (!isPlugin) {
            for (String pluginOnlyMember : new String[] { AUTO_CONFIGURATION_NAME_MEMBER, MOVE_ANNOTATIONS_MEMBER }) {
                if (grailsBeansAnnotation.getMember(pluginOnlyMember) != null) {
                    addError(grailsBeansAnnotation, source, pluginOnlyMember + " has no effect here: it only applies " +
                            "when @GrailsBeans is applied to a grails.plugins.Plugin subclass, where the compiled beans " +
                            "land on a generated sibling class rather than on " + classNode.getNameWithoutPackage() + " itself");
                }
            }
        }

        ClassNode beanMethodHost = isPlugin ?
                createAutoConfigurationSibling(classNode, grailsBeansAnnotation, source) : classNode;

        Set<String> usedNames = existingMemberNames(beanMethodHost);
        Set<String> usedBeanNames = new HashSet<>();
        List<Statement> statements = beanStatements((ClosureExpression) initialExpression);
        // Two passes: field(...)/method(...) declare explicit member names, so they are processed
        // first (along with anything malformed, so every statement is still processed exactly
        // once) and bean(...) statements second. A bean's derived method name then adapts to every
        // explicitly-named member wherever it appears in the block - reordering equivalent DSL
        // statements must never change validity.
        for (Statement statement : statements) {
            if (!isBeanRootedStatement(statement)) {
                processStatement(beanMethodHost, statement, source, usedNames, usedBeanNames);
            }
        }
        for (Statement statement : statements) {
            if (isBeanRootedStatement(statement)) {
                processStatement(beanMethodHost, statement, source, usedNames, usedBeanNames);
            }
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

    // Annotations that only make sense on whatever class Spring Boot actually evaluates as an
    // auto-configuration - meaningless on a Plugin subclass, which is instantiated by
    // DefaultGrailsPlugin via plain reflection and never processed by Spring as a bean. Matching
    // is transitive through meta-annotations (see belongsOnSibling), so this list only needs the
    // "root" annotations - a composed annotation built on top of any of these (e.g. a custom
    // @ConditionalOnFeature meta-annotated with Spring Boot's own @ConditionalOnProperty, or a
    // custom @EnableSomething meta-annotated with @Import) is found automatically.
    private static final Set<String> SIBLING_ONLY_ANNOTATION_NAMES = Set.of(
            AutoConfiguration.class.getName(), AutoConfigureOrder.class.getName(),
            AutoConfigureBefore.class.getName(), AutoConfigureAfter.class.getName(),
            Import.class.getName(), ImportAutoConfiguration.class.getName(), ImportResource.class.getName(),
            ComponentScan.class.getName(), ComponentScans.class.getName(),
            EnableConfigurationProperties.class.getName(),
            PropertySource.class.getName(), PropertySources.class.getName(),
            Conditional.class.getName());

    private ClassNode createAutoConfigurationSibling(ClassNode pluginClass, AnnotationNode grailsBeansAnnotation, SourceUnit source) {
        List<AnnotationNode> autoConfigurationAnnotations = pluginClass.getAnnotations(ClassHelper.make(AutoConfiguration.class));
        if (autoConfigurationAnnotations.isEmpty()) {
            addError(pluginClass, source, "A Plugin class using @GrailsBeans must also be annotated " +
                    "@AutoConfiguration (even with no before=/after=) - otherwise the generated " +
                    defaultSiblingSimpleName(pluginClass) +
                    " class would never be processed by Spring Boot");
        }

        String siblingSimpleName = siblingSimpleName(pluginClass, grailsBeansAnnotation, source);
        String packageName = pluginClass.getPackageName();
        String siblingName = (packageName == null || packageName.isEmpty()) ?
                siblingSimpleName : packageName + "." + siblingSimpleName;
        ClassNode sibling = new ClassNode(siblingName, Modifier.PUBLIC, ClassHelper.OBJECT_TYPE);
        source.getAST().addClass(sibling);

        // Matching annotations move entirely rather than being merely copied - they have no effect
        // where the author wrote them (see SIBLING_ONLY_ANNOTATION_NAMES).
        Set<String> moveAnnotationNames = parseMoveAnnotations(grailsBeansAnnotation, source);
        List<AnnotationNode> siblingAnnotations = new ArrayList<>();
        for (AnnotationNode annotation : pluginClass.getAnnotations()) {
            if (belongsOnSibling(annotation.getClassNode(), moveAnnotationNames)) {
                siblingAnnotations.add(annotation);
            }
        }
        sibling.addAnnotations(siblingAnnotations);
        pluginClass.getAnnotations().removeAll(siblingAnnotations);

        return sibling;
    }

    private Set<String> parseMoveAnnotations(AnnotationNode grailsBeansAnnotation, SourceUnit source) {
        Expression member = grailsBeansAnnotation.getMember(MOVE_ANNOTATIONS_MEMBER);
        if (member == null) {
            return Set.of();
        }
        List<Expression> entries = member instanceof ListExpression ?
                ((ListExpression) member).getExpressions() : List.of(member);
        Set<String> names = new HashSet<>();
        for (Expression entry : entries) {
            if (!(entry instanceof ClassExpression)) {
                addError(entry, source, "moveAnnotations entries must be annotation class literals, " +
                        "e.g. @GrailsBeans(moveAnnotations = [ComponentScan])");
                continue;
            }
            ClassNode annotationType = ((ClassExpression) entry).getType();
            if (!annotationType.isAnnotationDefinition()) {
                addError(entry, source, "\"" + annotationType.getName() + "\" is not an annotation type");
                continue;
            }
            names.add(annotationType.getName());
        }
        return names;
    }

    // A *GrailsPlugin name swaps that suffix for AutoConfiguration (I18nGrailsPlugin ->
    // I18nAutoConfiguration - the name the hand-written class it replaces would have had);
    // anything else appends AutoConfiguration.
    private String defaultSiblingSimpleName(ClassNode pluginClass) {
        String simpleName = pluginClass.getNameWithoutPackage();
        if (simpleName.endsWith(GRAILS_PLUGIN_SUFFIX) && simpleName.length() > GRAILS_PLUGIN_SUFFIX.length()) {
            return simpleName.substring(0, simpleName.length() - GRAILS_PLUGIN_SUFFIX.length()) + AUTO_CONFIGURATION_SUFFIX;
        }
        return simpleName + AUTO_CONFIGURATION_SUFFIX;
    }

    private String siblingSimpleName(ClassNode pluginClass, AnnotationNode grailsBeansAnnotation, SourceUnit source) {
        String defaultName = defaultSiblingSimpleName(pluginClass);
        Expression nameArg = grailsBeansAnnotation.getMember(AUTO_CONFIGURATION_NAME_MEMBER);
        if (nameArg == null) {
            return defaultName;
        }
        Object nameValue = nameArg instanceof ConstantExpression ? ((ConstantExpression) nameArg).getValue() : null;
        if (!(nameValue instanceof String)) {
            addError(nameArg, source, "@GrailsBeans(autoConfigurationName = ...) requires a String literal");
            return defaultName;
        }
        String name = (String) nameValue;
        if (name.isBlank()) {
            addError(nameArg, source, "@GrailsBeans(autoConfigurationName = \"" + name + "\") must not be " +
                    "blank - omit the attribute entirely to use the default " + defaultName + " instead");
            return defaultName;
        }
        if (!isValidJavaIdentifier(name)) {
            addError(nameArg, source, "@GrailsBeans(autoConfigurationName = \"" + name + "\") is not a valid " +
                    "name: it becomes the generated sibling's simple class name, so it must be a valid Java identifier");
            return defaultName;
        }
        return name;
    }

    private boolean belongsOnSibling(ClassNode annotationType, Set<String> moveAnnotationNames) {
        return belongsOnSibling(annotationType, moveAnnotationNames, new HashSet<>());
    }

    // Recurses through meta-annotations rather than checking only one level, since Spring's own
    // composed-annotation convention is arbitrarily deep - e.g. a project-specific
    // @ConditionalOnFeature is typically meta-annotated with an existing @ConditionalOnXxx (itself
    // meta-annotated @Conditional), not with @Conditional directly. `visited` guards against cycles
    // and, since every annotation type transitively reaches common JDK meta-annotations
    // (@Retention, @Target, @Documented) from multiple paths, avoids redundant re-exploration.
    private boolean belongsOnSibling(ClassNode annotationType, Set<String> moveAnnotationNames, Set<String> visited) {
        if (!visited.add(annotationType.getName())) {
            return false;
        }
        if (SIBLING_ONLY_ANNOTATION_NAMES.contains(annotationType.getName()) ||
                moveAnnotationNames.contains(annotationType.getName())) {
            return true;
        }
        for (AnnotationNode metaAnnotation : annotationType.getAnnotations()) {
            if (belongsOnSibling(metaAnnotation.getClassNode(), moveAnnotationNames, visited)) {
                return true;
            }
        }
        return false;
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

    // Generated names must not collide with anything the host class already has: its own fields
    // and methods (in the standalone form the host is a real user-written class), every method
    // inherited through its full type graph - superclasses and interfaces alike, since a bean
    // named 'toString' or after an interface's default method must synthesize, not override -
    // and the GroovyObject methods Groovy itself adds at class generation.
    private Set<String> existingMemberNames(ClassNode host) {
        Set<String> names = new HashSet<>();
        for (FieldNode field : host.getFields()) {
            names.add(field.getName());
        }
        Set<String> visited = new HashSet<>();
        collectMethodNames(host, names, visited);
        collectMethodNames(ClassHelper.GROOVY_OBJECT_TYPE, names, visited);
        return names;
    }

    private void collectMethodNames(ClassNode type, Set<String> names, Set<String> visited) {
        if (type == null || !visited.add(type.getName())) {
            return;
        }
        for (MethodNode method : type.getMethods()) {
            names.add(method.getName());
        }
        // A Groovy property's accessors are synthesized by the Verifier at class generation, AFTER
        // this transform runs, so they are not in getMethods() yet - reserve the names they will
        // occupy, or a same-named bean method would displace the real accessor.
        for (PropertyNode property : type.getProperties()) {
            String capitalized = BeanUtils.capitalize(property.getName());
            names.add("get" + capitalized);
            names.add("set" + capitalized);
            if (ClassHelper.boolean_TYPE.equals(property.getType()) ||
                    ClassHelper.Boolean_TYPE.equals(property.getType())) {
                names.add("is" + capitalized);
            }
        }
        collectMethodNames(type.getSuperClass(), names, visited);
        for (ClassNode implemented : type.getInterfaces()) {
            collectMethodNames(implemented, names, visited);
        }
    }

    // Silent classification counterpart of processStatement's qualifier-chain walk: descends to
    // the root call without reporting anything, so malformed statements are classified (not
    // validated) here and still produce their usual errors when actually processed.
    private boolean isBeanRootedStatement(Statement statement) {
        if (!(statement instanceof ExpressionStatement) ||
                !(((ExpressionStatement) statement).getExpression() instanceof MethodCallExpression)) {
            return false;
        }
        MethodCallExpression call = (MethodCallExpression) ((ExpressionStatement) statement).getExpression();
        while (!ROOT_STATEMENT_CALL_NAMES.contains(call.getMethodAsString()) &&
                call.getObjectExpression() instanceof MethodCallExpression) {
            call = (MethodCallExpression) call.getObjectExpression();
        }
        return BEAN_CALL.equals(call.getMethodAsString());
    }

    private void processStatement(ClassNode classNode, Statement statement, SourceUnit source, Set<String> usedNames,
            Set<String> usedBeanNames) {
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
            if (!ALL_QUALIFIER_CALL_NAMES.contains(baseCall.getMethodAsString()) ||
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
        Set<String> allowedQualifiers = isBean ? BEAN_QUALIFIER_CALL_NAMES :
                FIELD_CALL.equals(rootName) ? FIELD_QUALIFIER_CALL_NAMES : METHOD_QUALIFIER_CALL_NAMES;
        for (MethodCallExpression qualifierCall : qualifierCalls) {
            if (!allowedQualifiers.contains(qualifierCall.getMethodAsString())) {
                addError(qualifierCall, source, "." + qualifierCall.getMethodAsString() + "(...) cannot be " +
                        "chained onto " + rootName + "(...)");
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
            processBeanStatement(classNode, outerCall, baseCall, qualifierCalls, source, usedNames, usedBeanNames);
        }
        else if (FIELD_CALL.equals(rootName)) {
            processFieldStatement(classNode, baseCall, qualifierCalls, source, usedNames);
        }
        else {
            processMethodStatement(classNode, outerCall, baseCall, qualifierCalls, source, usedNames);
        }
    }

    private boolean registerName(String name, ASTNode location, SourceUnit source, Set<String> usedNames, String errorSuffix) {
        if (!usedNames.add(name)) {
            addError(location, source, "\"" + name + "\" " + errorSuffix);
            return false;
        }
        return true;
    }

    private void processBeanStatement(ClassNode classNode, MethodCallExpression outerCall, MethodCallExpression baseCall,
            List<MethodCallExpression> qualifierCalls, SourceUnit source, Set<String> usedNames, Set<String> usedBeanNames) {
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

        TypeAndName typeAndName = parseTypeAndName(baseArgs, baseCall, source, BEAN_CALL, false);
        if (typeAndName == null) {
            return;
        }
        if (!registerName(typeAndName.name, baseCall, source, usedBeanNames,
                "is already used as the Spring bean name of another bean(...) statement")) {
            return;
        }

        // The method name is an implementation detail - Spring resolves the bean by its
        // @Bean("name") value, never by the factory method's name - so a bean name that isn't a
        // usable Java identifier, or is already taken by an existing member, synthesizes instead
        // of erroring.
        String javaMethodName = isValidJavaIdentifier(typeAndName.name) && !usedNames.contains(typeAndName.name) ?
                typeAndName.name :
                syntheticBeanMethodName(typeAndName.type.getType(), usedNames);
        usedNames.add(javaMethodName);

        MethodNode beanMethod = new MethodNode(
                javaMethodName,
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
            if (!applyQualifier(beanMethod, typeAndName.name, qualifierCall, qualifierArgs, source)) {
                return;
            }
        }

        classNode.addMethod(beanMethod);
    }

    private String syntheticBeanMethodName(ClassNode beanType, Set<String> usedNames) {
        String base = decapitalize(beanType.getNameWithoutPackage());
        String candidate;
        int index = 0;
        do {
            candidate = base + "$" + index;
            index++;
        }
        while (usedNames.contains(candidate));
        return candidate;
    }

    private void processFieldStatement(ClassNode classNode, MethodCallExpression baseCall,
            List<MethodCallExpression> qualifierCalls, SourceUnit source, Set<String> usedNames) {
        List<Expression> baseArgs = flatten(baseCall.getArguments());
        TypeAndName typeAndName = parseTypeAndName(baseArgs, baseCall, source, FIELD_CALL, true);
        if (typeAndName == null) {
            return;
        }
        if (!registerName(typeAndName.name, baseCall, source, usedNames,
                "is already used by another member of the class (declared, inherited, or another field(...)/method(...) statement) - " +
                        "generated member names must be unique")) {
            return;
        }

        FieldNode field = classNode.addField(typeAndName.name, Modifier.PRIVATE, typeAndName.type.getType(), null);

        for (MethodCallExpression qualifierCall : qualifierCalls) {
            List<Expression> qualifierArgs = flatten(qualifierCall.getArguments());
            if (VALUE_CALL.equals(qualifierCall.getMethodAsString())) {
                AnnotationNode valueAnnotation = valueAnnotation(qualifierArgs, qualifierCall, source);
                if (valueAnnotation == null || !addAnnotationIfAbsent(field, qualifierCall, valueAnnotation, source)) {
                    return;
                }
            }
            else if (!applyGenericAnnotation(field, qualifierCall, qualifierArgs, source)) {
                return;
            }
        }
    }

    // .value(key, default) builds the '${key:default}' placeholder itself, as a concatenation the
    // compiler folds into a constant - which is what lets the key be a bare static-final constant
    // reference, the one shape a directly-written annotation value rejects. .value(placeholder)
    // passes its single argument through verbatim, for SpEL or an already-complete placeholder.
    private AnnotationNode valueAnnotation(List<Expression> args, MethodCallExpression qualifierCall, SourceUnit source) {
        if (args.isEmpty() || args.size() > 2) {
            addError(qualifierCall, source, ".value(...) requires a config key and default - e.g. " +
                    ".value(Settings.GSP_VIEW_ENCODING, \"UTF-8\") - or a single complete placeholder/SpEL string");
            return null;
        }
        Expression member;
        if (args.size() == 1) {
            member = args.get(0);
        }
        else {
            member = concat(new ConstantExpression("${"), args.get(0));
            member = concat(member, new ConstantExpression(":"));
            member = concat(member, args.get(1));
            member = concat(member, new ConstantExpression("}"));
        }
        AnnotationNode annotation = new AnnotationNode(ClassHelper.make(Value.class));
        annotation.setMember("value", member);
        return annotation;
    }

    private Expression concat(Expression left, Expression right) {
        return new BinaryExpression(left, Token.newSymbol(Types.PLUS, -1, -1), right);
    }

    private void processMethodStatement(ClassNode classNode, MethodCallExpression outerCall, MethodCallExpression baseCall,
            List<MethodCallExpression> qualifierCalls, SourceUnit source, Set<String> usedNames) {
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

        TypeAndName typeAndName = parseTypeAndName(baseArgs, baseCall, source, METHOD_CALL, true);
        if (typeAndName == null) {
            return;
        }
        if (!registerName(typeAndName.name, baseCall, source, usedNames,
                "is already used by another member of the class (declared, inherited, or another field(...)/method(...) statement) - " +
                        "generated member names must be unique")) {
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

    private TypeAndName parseTypeAndName(List<Expression> args, MethodCallExpression call, SourceUnit source,
            String callName, boolean requireValidIdentifier) {
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
            if (requireValidIdentifier && !isValidJavaIdentifier(name)) {
                addError(nameArg, source, "\"" + name + "\" is not a valid name: it becomes the generated " +
                        "member's name, so it must be a valid Java identifier");
                return null;
            }
            // Even bean(...), which otherwise allows any Spring name, must reject a blank one:
            // Spring treats a blank @Bean name as absent and falls back to the method name, so
            // the name actually written would be silently discarded.
            if (!requireValidIdentifier && name.isBlank()) {
                addError(nameArg, source, callName + "(Type, name) requires a non-blank name");
                return null;
            }
        }
        return new TypeAndName(type, name);
    }

    private boolean applyQualifier(MethodNode beanMethod, String beanName, MethodCallExpression qualifierCall,
            List<Expression> args, SourceUnit source) {
        String name = qualifierCall.getMethodAsString();
        if (CONDITIONAL_ON_MISSING_BEAN_CALL.equals(name)) {
            AnnotationNode annotation = conditionalOnMissingBeanAnnotation(args, qualifierCall, source);
            return annotation != null && addAnnotationIfAbsent(beanMethod, qualifierCall, annotation, source);
        }
        if (CONDITIONAL_ON_MISSING_BEAN_NAME_CALL.equals(name)) {
            AnnotationNode annotation = conditionalOnMissingBeanNameAnnotation(args, beanName, qualifierCall, source);
            return annotation != null && addAnnotationIfAbsent(beanMethod, qualifierCall, annotation, source);
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
        if (members != null && !addMembersFromMap(annotation, members, qualifierCall, source)) {
            return false;
        }
        return addAnnotationIfAbsent(target, qualifierCall, annotation, source);
    }

    private boolean addMembersFromMap(AnnotationNode annotation, MapExpression members,
            MethodCallExpression qualifierCall, SourceUnit source) {
        for (MapEntryExpression entry : members.getMapEntryExpressions()) {
            Object keyValue = entry.getKeyExpression() instanceof ConstantExpression ?
                    ((ConstantExpression) entry.getKeyExpression()).getValue() : null;
            if (!(keyValue instanceof String)) {
                addError(qualifierCall, source, "." + qualifierCall.getMethodAsString() +
                        "(...) attribute names must be simple identifiers, e.g. .annotate(Order, value: 1)");
                return false;
            }
            annotation.setMember((String) keyValue, entry.getValueExpression());
        }
        return true;
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

    // Zero args -> a bare annotation, letting Spring infer the back-off type from the method's
    // return type. Positional types -> the value member. Named args -> the annotation's own
    // attributes (name:, search:, ...), so the common name-based back-off doesn't need the
    // generic .annotate(...) escape hatch.
    private AnnotationNode conditionalOnMissingBeanAnnotation(List<Expression> args,
            MethodCallExpression qualifierCall, SourceUnit source) {
        AnnotationNode annotation = new AnnotationNode(ClassHelper.make(ConditionalOnMissingBean.class));
        MapExpression members = !args.isEmpty() && args.get(0) instanceof MapExpression ? (MapExpression) args.get(0) : null;
        List<Expression> types = members != null ? args.subList(1, args.size()) : args;
        if (members != null && !types.isEmpty()) {
            for (MapEntryExpression entry : members.getMapEntryExpressions()) {
                Object keyValue = entry.getKeyExpression() instanceof ConstantExpression ?
                        ((ConstantExpression) entry.getKeyExpression()).getValue() : null;
                if ("value".equals(keyValue)) {
                    addError(qualifierCall, source, "conditionalOnMissingBean(...) was given types both " +
                            "positionally and via value: - use one or the other");
                    return null;
                }
            }
        }
        if (members != null && !addMembersFromMap(annotation, members, qualifierCall, source)) {
            return null;
        }
        if (!types.isEmpty()) {
            ListExpression typeList = new ListExpression();
            for (Expression type : types) {
                if (!(type instanceof ClassExpression)) {
                    addError(qualifierCall, source, "conditionalOnMissingBean(...) arguments must be types " +
                            "and/or named attributes, e.g. conditionalOnMissingBean(Greeter) or " +
                            "conditionalOnMissingBean(name: \"greeter\", search: SearchStrategy.CURRENT)");
                    continue;
                }
                typeList.addExpression(type);
            }
            annotation.setMember("value", typeList);
        }
        return annotation;
    }

    // "Register this bean unless a bean with THIS bean's name already exists" - the name member is
    // set from the bean's own (explicit or convention-derived) name, so the two strings the plain
    // form would have to keep in sync cannot diverge. name:/value:/positional types are rejected:
    // supplying them contradicts the qualifier's purpose, and the fully-explicit forms remain
    // available on .conditionalOnMissingBean(...).
    private AnnotationNode conditionalOnMissingBeanNameAnnotation(List<Expression> args, String beanName,
            MethodCallExpression qualifierCall, SourceUnit source) {
        MapExpression members = !args.isEmpty() && args.get(0) instanceof MapExpression ? (MapExpression) args.get(0) : null;
        if (args.size() > (members != null ? 1 : 0)) {
            addError(qualifierCall, source, "conditionalOnMissingBeanName(...) takes only named attributes " +
                    "(e.g. search: SearchStrategy.CURRENT) - it always backs off by this bean's own name; " +
                    "use conditionalOnMissingBean(...) for type-based or fully explicit conditions");
            return null;
        }
        if (members != null) {
            for (MapEntryExpression entry : members.getMapEntryExpressions()) {
                Object keyValue = entry.getKeyExpression() instanceof ConstantExpression ?
                        ((ConstantExpression) entry.getKeyExpression()).getValue() : null;
                if ("name".equals(keyValue) || "value".equals(keyValue)) {
                    addError(qualifierCall, source, "conditionalOnMissingBeanName(...) sets name automatically " +
                            "from this bean's own name - use conditionalOnMissingBean(...) to spell out name: or types");
                    return null;
                }
            }
        }
        AnnotationNode annotation = new AnnotationNode(ClassHelper.make(ConditionalOnMissingBean.class));
        if (members != null && !addMembersFromMap(annotation, members, qualifierCall, source)) {
            return null;
        }
        annotation.setMember("name", new ConstantExpression(beanName));
        return annotation;
    }

    private String decapitalize(String name) {
        return Introspector.decapitalize(name);
    }

    private boolean isValidJavaIdentifier(String name) {
        return SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name);
    }

    private void addError(ASTNode node, SourceUnit source, String message) {
        source.getErrorCollector().addErrorAndContinue(
                new org.codehaus.groovy.control.messages.SyntaxErrorMessage(
                        new SyntaxException(message, node.getLineNumber(), node.getColumnNumber()), source));
    }

}

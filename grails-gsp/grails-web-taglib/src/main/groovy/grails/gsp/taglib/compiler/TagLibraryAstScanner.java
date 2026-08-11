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
package grails.gsp.taglib.compiler;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import groovy.lang.Closure;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;

import grails.gsp.NotATag;
import grails.gsp.Tag;
import org.grails.taglib.TagMethodInvoker;

/**
 * Reads a tag library's namespace and tag names from its AST.
 *
 * <p>This mirrors the runtime rules in {@code TagMethodInvoker.isTagMethodCandidate} and
 * {@code DefaultGrailsTagLibClass}, applied to {@link MethodNode} instead of {@code java.lang.reflect
 * .Method} so that the answer is available while the tag library is being compiled. The two must agree:
 * a tag recorded here but rejected at runtime would resolve statically and then fail to dispatch.
 *
 * @since 8.0.0
 */
final class TagLibraryAstScanner {

    static final String DEFAULT_NAMESPACE = "g";

    private static final String NAMESPACE_FIELD = "namespace";

    private static final ClassNode CLOSURE_TYPE = ClassHelper.make(Closure.class);
    private static final ClassNode MAP_TYPE = ClassHelper.MAP_TYPE;
    private static final ClassNode TAG_ANNOTATION = ClassHelper.make(Tag.class);
    private static final ClassNode NOT_A_TAG_ANNOTATION = ClassHelper.make(NotATag.class);

    /**
     * Names excluded because they are Groovy or Object plumbing rather than tags. The
     * framework-trait names come from {@link TagMethodInvoker#FRAMEWORK_METHOD_NAMES} so that the
     * compile-time and runtime views of "what is a tag" cannot drift apart.
     */
    private static final Set<String> NON_TAG_METHOD_NAMES = Set.of(
            "invokeMethod", "methodMissing", "propertyMissing", "getProperty", "setProperty",
            "getMetaClass", "setMetaClass", "equals", "hashCode", "toString");

    private TagLibraryAstScanner() {
    }

    /**
     * Resolves the namespace the way {@code DefaultGrailsTagLibClass} does at runtime, which reads the
     * static {@code namespace} property through the class hierarchy.
     *
     * @param classNode the tag library
     * @return the namespace, or {@code null} when it cannot be determined without running the code, in
     *         which case no descriptor should be written and the tag library resolves dynamically
     */
    static String resolveNamespace(ClassNode classNode) {
        for (ClassNode current = classNode; current != null && !ClassHelper.isObjectType(current);
                current = current.getSuperClass()) {
            FieldNode namespaceField = current.getDeclaredField(NAMESPACE_FIELD);
            if (namespaceField == null || !namespaceField.isStatic()) {
                continue;
            }
            Expression initial = namespaceField.getInitialExpression();
            if (initial instanceof ConstantExpression constant && constant.getValue() != null) {
                String value = constant.getValue().toString().trim();
                return value.isEmpty() ? DEFAULT_NAMESPACE : value;
            }
            // Declared, but its value is only known once the initialiser runs - a reference to a shared
            // constant, a concatenation, and so on. Guessing "g" here would file the tags under the
            // wrong namespace, so the tag library is left out of the index entirely.
            return null;
        }
        return DEFAULT_NAMESPACE;
    }

    /**
     * @param classNode the tag library
     * @return every tag the library declares, whether as a tag method or a legacy closure field
     */
    static Collection<String> findTagNames(ClassNode classNode, boolean parameterNamesRetained) {
        Set<String> tagNames = new LinkedHashSet<>();
        for (MethodNode method : classNode.getMethods()) {
            // TagMethodInvoker scans getDeclaredMethods(), so a method inherited from a superclass is
            // not dispatchable and must not be recorded. Trait methods are woven as declarations on the
            // implementing class and so are still seen here.
            if (method.getDeclaringClass() != null && !classNode.equals(method.getDeclaringClass())) {
                continue;
            }
            if (isTagMethodCandidate(method, parameterNamesRetained)) {
                tagNames.add(method.getName());
            }
        }
        // Closure-typed fields remain tags for as long as the deprecated form is supported.
        for (FieldNode field : classNode.getFields()) {
            if (!field.isStatic() && field.getType() != null && CLOSURE_TYPE.equals(field.getType())) {
                tagNames.add(field.getName());
            }
        }
        return tagNames;
    }

    private static boolean isTagMethodCandidate(MethodNode method, boolean parameterNamesRetained) {
        if (!method.isPublic() || method.isStatic() || method.isAbstract() || method.isSynthetic()) {
            return false;
        }
        // @NotATag and @Tag override the signature rule at runtime, so they must override it here too.
        if (!method.getAnnotations(NOT_A_TAG_ANNOTATION).isEmpty()) {
            return false;
        }
        if (!method.getAnnotations(TAG_ANNOTATION).isEmpty()) {
            return true;
        }
        String name = method.getName();
        if (name.startsWith("<") || NON_TAG_METHOD_NAMES.contains(name) ||
                TagMethodInvoker.FRAMEWORK_METHOD_NAMES.contains(name)) {
            return false;
        }
        // Trait application generates super-accessor bridges such as
        // "grails_artefact_TagLibrarytrait$super$raw". These are synthetic at runtime but are not
        // flagged as such on the AST at canonicalization, so they are excluded by name.
        if (name.indexOf('$') >= 0) {
            return false;
        }
        Parameter[] parameters = method.getParameters();
        if (name.startsWith("get") && parameters.length == 0) {
            return false;
        }
        if (name.startsWith("is") && parameters.length == 0) {
            return false;
        }
        if (name.startsWith("set") && parameters.length == 1) {
            return false;
        }
        return hasConventionalTagSignature(parameters, parameterNamesRetained);
    }

    /**
     * A tag is invoked as {@code (Map)} or {@code (Map, Closure)}; anything else is a helper method
     * that happens to live on the tag library.
     *
     * <p>Parameters with default values are taken into account. Groovy expands
     * {@code formatValue(value, String path = null, Boolean tagSyntaxCall = false)} into overloads of
     * one, two and three arguments, and the runtime sees those overloads through reflection. At
     * canonicalization only the declaration exists, so the arities the defaults will produce are
     * derived here instead; otherwise such a tag is recorded as absent and silently falls back to
     * dynamic dispatch.
     */
    private static boolean hasConventionalTagSignature(Parameter[] parameters, boolean parameterNamesRetained) {
        int required = 0;
        for (Parameter parameter : parameters) {
            if (!parameter.hasInitialExpression()) {
                required++;
            }
        }
        // Every arity from the required count up to the full parameter list is callable.
        for (int arity = Math.max(required, 1); arity <= parameters.length; arity++) {
            if (matchesTagShape(parameters, arity, parameterNamesRetained)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesTagShape(Parameter[] parameters, int arity, boolean parameterNamesRetained) {
        if (arity == 1) {
            return isAttrs(parameters[0], parameterNamesRetained) || isBody(parameters[0], parameterNamesRetained);
        }
        if (arity == 2) {
            return isAttrs(parameters[0], parameterNamesRetained) && isBody(parameters[1], parameterNamesRetained);
        }
        return false;
    }

    /**
     * Mirrors {@code TagMethodInvoker.isAttrsParameter}, which requires the declared type to be
     * assignable to {@link java.util.Map}. An untyped parameter is {@code Object} and is therefore not
     * an attributes parameter, so a tag declared as {@code def foo(attrs)} is not dispatchable and must
     * not be recorded here either.
     */
    private static boolean isAttrs(Parameter parameter, boolean parameterNamesRetained) {
        return isMap(parameter) && namedOrUnnamed(parameter, "attrs", parameterNamesRetained);
    }

    private static boolean isBody(Parameter parameter, boolean parameterNamesRetained) {
        return isClosure(parameter) && namedOrUnnamed(parameter, "body", parameterNamesRetained);
    }

    /**
     * Runtime accepts a parameter as attrs or body when it carries the expected name, or when the class
     * was compiled without retaining parameter names and no name is available to check. Mirroring that
     * requires knowing which of those the compilation will produce.
     */
    private static boolean namedOrUnnamed(Parameter parameter, String expectedName, boolean parameterNamesRetained) {
        return !parameterNamesRetained || expectedName.equals(parameter.getName());
    }

    private static boolean isMap(Parameter parameter) {
        ClassNode type = parameter.getType();
        return type != null && (MAP_TYPE.equals(type) || type.isDerivedFrom(MAP_TYPE) ||
                type.implementsInterface(MAP_TYPE));
    }

    /**
     * Mirrors {@code TagMethodInvoker.isBodyParameter}, which requires assignability to
     * {@link groovy.lang.Closure}.
     */
    private static boolean isClosure(Parameter parameter) {
        ClassNode type = parameter.getType();
        return type != null && (CLOSURE_TYPE.equals(type) || type.isDerivedFrom(CLOSURE_TYPE));
    }

    /**
     * @param expression a {@code static returnObjectForTags} initialiser
     * @return the listed tag names
     */
    static List<String> constantList(Expression expression) {
        if (expression instanceof ListExpression list) {
            return list.getExpressions().stream()
                    .filter(ConstantExpression.class::isInstance)
                    .map(e -> String.valueOf(((ConstantExpression) e).getValue()))
                    .toList();
        }
        return List.of();
    }
}

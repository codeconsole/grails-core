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
package org.grails.datastore.gorm.query.transform;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Types;

import org.grails.datastore.mapping.reflect.AstUtils;

/**
 * {@link ClassCodeVisitorSupport} that detects GORM HQL/Cypher query text built from a
 * {@link GStringExpression} that Groovy coerced to a plain {@code String} <em>before</em> it
 * reaches a GORM query method, e.g.:
 *
 * <pre>{@code
 * String query = "from Book where name = ${userInput}"   // coerced to String right here
 * Book.executeQuery(query)                                // -> raw, unescaped text, no binding
 * }</pre>
 *
 * <p>When a {@link groovy.lang.GString} is passed directly to a GORM query method, GORM binds
 * each interpolated value as a query parameter — safe. Once the {@code GString} has been coerced
 * to a {@code String} (an explicit {@code String}-typed local, a {@code .toString()} call, or an
 * {@code as String}/cast coercion), that information is gone: a {@code String} carries no trace
 * of ever having been a {@code GString}, so this can only be caught here, before the coercion
 * erases it — a runtime check at the query boundary is structurally blind to this case.
 *
 * <p>A {@code GString} does not have to be flattened directly at the call site to be unsafe —
 * aliasing it through one or more intermediate variables still loses the binding the moment it is
 * assigned to a {@code String}-typed variable, however many hops away that happens:
 *
 * <pre>{@code
 * def g = "from Book where name = ${userInput}"   // g: still a live GString
 * String q = g                                     // flattened HERE, not at the executeQuery call
 * Book.executeQuery(q)
 * }</pre>
 *
 * <p><strong>Known limitations (deliberate v1 scope):</strong>
 * <ul>
 *     <li>Intraprocedural only — a flattened {@code String} built inside a helper method and
 *     returned to the caller is invisible to this check.</li>
 *     <li>Reassignment tracking is last-write-wins, not full branch-sensitive dataflow.</li>
 *     <li>Only local variables are tracked, not fields.</li>
 *     <li>Does not detect plain string concatenation with no {@code GString} involved at all, raw
 *     JDBC via {@code groovy.sql.Sql}, or any datastore whose query methods use names outside
 *     {@link #CANDIDATE_METHODS}.</li>
 * </ul>
 *
 * @since 8.1
 */
public class GormQuerySafetyTransformer extends ClassCodeVisitorSupport {

    /**
     * What a tracked local variable currently holds, from this check's point of view.
     */
    private enum Origin {
        /** Not derived from an interpolated GString at all - nothing to track. */
        NONE,
        /** Still a real {@link groovy.lang.GString} - safe if passed directly to a query method. */
        LIVE_GSTRING,
        /** Already coerced to a plain {@code String} - unsafe if it reaches a query method. */
        FLATTENED
    }

    /**
     * The {@code @SuppressWarnings} value that silences this check on the enclosing method (or,
     * for calls outside any method, the enclosing class).
     */
    public static final String SUPPRESS_WARNINGS_VALUE = "GormUnsafeQueryString";

    private static final Set<String> CANDIDATE_METHODS = new HashSet<>(Arrays.asList(
            "find", "findAll", "executeQuery", "executeUpdate",
            "findAllWithSql", "cypherStatic", "findPath", "findPathTo"));

    /**
     * The positional index of the query argument for each candidate method. Every candidate
     * method takes the query as its first argument except Neo4j's
     * {@code findPathTo(Class type, CharSequence query, Map params)}.
     */
    private static final Map<String, Integer> QUERY_ARGUMENT_INDEX = buildQueryArgumentIndex();

    private static Map<String, Integer> buildQueryArgumentIndex() {
        Map<String, Integer> indexes = new HashMap<>();
        for (String method : CANDIDATE_METHODS) {
            indexes.put(method, 0);
        }
        indexes.put("findPathTo", 1);
        return Collections.unmodifiableMap(indexes);
    }

    private final SourceUnit sourceUnit;
    private final Map<String, ASTNode> flattenedStringVars = new HashMap<>();
    private final Map<String, ASTNode> liveGStringVars = new HashMap<>();
    private ClassNode currentClassNode;
    private MethodNode currentMethodNode;

    public GormQuerySafetyTransformer(SourceUnit sourceUnit) {
        this.sourceUnit = sourceUnit;
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return this.sourceUnit;
    }

    @Override
    public void visitClass(ClassNode node) {
        try {
            this.currentClassNode = node;
            super.visitClass(node);
        } finally {
            this.currentClassNode = null;
            clearTracking();
        }
    }

    @Override
    public void visitMethod(MethodNode node) {
        this.currentMethodNode = node;
        try {
            super.visitMethod(node);
        } finally {
            this.currentMethodNode = null;
            clearTracking();
        }
    }

    private void clearTracking() {
        flattenedStringVars.clear();
        liveGStringVars.clear();
    }

    @Override
    public void visitDeclarationExpression(DeclarationExpression expression) {
        // getVariableExpression() is null for multiple-assignment declarations, e.g. def (a, b) = [...]
        VariableExpression variableExpression = expression.isMultipleAssignmentDeclaration() ?
                null : expression.getVariableExpression();
        if (variableExpression != null) {
            track(variableExpression.getName(), expression.getRightExpression(), variableExpression.getType(), expression);
        }
        super.visitDeclarationExpression(expression);
    }

    @Override
    public void visitBinaryExpression(BinaryExpression expression) {
        if (expression.getOperation().getType() == Types.ASSIGN &&
                expression.getLeftExpression() instanceof VariableExpression) {
            VariableExpression leftVariable = (VariableExpression) expression.getLeftExpression();
            track(leftVariable.getName(), expression.getRightExpression(), leftVariable.getType(), expression);
        }
        super.visitBinaryExpression(expression);
    }

    /**
     * Records what {@code variableName} now holds after being assigned {@code rightExpression},
     * resolving through any variable aliasing so a {@code GString} tracked several assignments
     * earlier is still recognised as unsafe once it (or an alias of it) reaches a
     * {@code String}-typed variable.
     */
    private void track(String variableName, Expression rightExpression, ClassNode declaredType, ASTNode locationNode) {
        switch (classify(rightExpression, declaredType)) {
            case FLATTENED:
                flattenedStringVars.put(variableName, locationNode);
                liveGStringVars.remove(variableName);
                break;
            case LIVE_GSTRING:
                liveGStringVars.put(variableName, locationNode);
                flattenedStringVars.remove(variableName);
                break;
            case NONE:
            default:
                // Any other (safe) reassignment clears prior tracking - last write wins,
                // not full branch-sensitive dataflow. See class Javadoc.
                flattenedStringVars.remove(variableName);
                liveGStringVars.remove(variableName);
                break;
        }
    }

    /**
     * Determines what {@code expression} evaluates to, from this check's point of view, resolving
     * one level of variable reference against the current tracking state so aliasing chains
     * (however many hops long) are followed correctly - each hop was itself already classified
     * when its own assignment was visited.
     */
    private Origin classify(Expression expression, ClassNode declaredType) {
        if (expression instanceof VariableExpression) {
            String name = ((VariableExpression) expression).getName();
            if (flattenedStringVars.containsKey(name)) {
                return Origin.FLATTENED; // already a plain String - stays unsafe regardless of declaredType
            }
            if (liveGStringVars.containsKey(name)) {
                return ClassHelper.STRING_TYPE.equals(declaredType) ? Origin.FLATTENED : Origin.LIVE_GSTRING;
            }
            return Origin.NONE;
        }
        if (isInterpolatedGString(expression)) {
            return ClassHelper.STRING_TYPE.equals(declaredType) ? Origin.FLATTENED : Origin.LIVE_GSTRING;
        }
        if (expression instanceof CastExpression) {
            CastExpression cast = (CastExpression) expression;
            if (ClassHelper.STRING_TYPE.equals(cast.getType()) && isUnsafeSource(cast.getExpression())) {
                return Origin.FLATTENED;
            }
            return Origin.NONE;
        }
        if (expression instanceof MethodCallExpression) {
            MethodCallExpression call = (MethodCallExpression) expression;
            if ("toString".equals(call.getMethodAsString()) && isUnsafeSource(call.getObjectExpression())) {
                return Origin.FLATTENED; // .toString() always yields a String, regardless of declaredType
            }
        }
        return Origin.NONE;
    }

    /**
     * True when {@code expression} is itself a live GString, or a variable reference already
     * tracked as a live GString or an already-flattened String - i.e. anything a cast or
     * {@code .toString()} applied on top of would still be unsafe to hand to a query method.
     */
    private boolean isUnsafeSource(Expression expression) {
        if (isInterpolatedGString(expression)) {
            return true;
        }
        if (expression instanceof VariableExpression) {
            String name = ((VariableExpression) expression).getName();
            return flattenedStringVars.containsKey(name) || liveGStringVars.containsKey(name);
        }
        return false;
    }

    private boolean isInterpolatedGString(Expression expression) {
        return expression instanceof GStringExpression && !((GStringExpression) expression).getValues().isEmpty();
    }

    @Override
    public void visitMethodCallExpression(MethodCallExpression call) {
        String methodName = call.getMethodAsString();
        if (methodName != null && CANDIDATE_METHODS.contains(methodName) &&
                isFlattenedQueryArgument(methodName, call.getArguments()) &&
                isGormReceiver(call.getObjectExpression()) &&
                !isSuppressed()) {
            reportUnsafeQuery(call, methodName);
        }
        super.visitMethodCallExpression(call);
    }

    @Override
    public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
        String methodName = call.getMethod();
        if (CANDIDATE_METHODS.contains(methodName) &&
                isFlattenedQueryArgument(methodName, call.getArguments()) &&
                AstUtils.isDomainClass(call.getOwnerType()) &&
                !isSuppressed()) {
            reportUnsafeQuery(call, methodName);
        }
        super.visitStaticMethodCallExpression(call);
    }

    private boolean isGormReceiver(Expression objectExpression) {
        if (objectExpression instanceof ClassExpression) {
            return AstUtils.isDomainClass(((ClassExpression) objectExpression).getType());
        }
        if (objectExpression instanceof VariableExpression && ((VariableExpression) objectExpression).isThisExpression()) {
            return currentClassNode != null && AstUtils.isDomainClass(currentClassNode);
        }
        return false;
    }

    private boolean isFlattenedQueryArgument(String methodName, Expression arguments) {
        if (!(arguments instanceof ArgumentListExpression)) {
            return false;
        }
        List<Expression> args = ((ArgumentListExpression) arguments).getExpressions();
        int index = QUERY_ARGUMENT_INDEX.get(methodName);
        if (args.size() <= index || !(args.get(index) instanceof VariableExpression)) {
            return false;
        }
        String variableName = ((VariableExpression) args.get(index)).getName();
        return flattenedStringVars.containsKey(variableName);
    }

    private boolean isSuppressed() {
        if (currentMethodNode != null && isSuppressedNode(currentMethodNode)) {
            return true;
        }
        return currentClassNode != null && isSuppressedNode(currentClassNode);
    }

    private boolean isSuppressedNode(AnnotatedNode node) {
        for (AnnotationNode annotation : node.getAnnotations(ClassHelper.make(SuppressWarnings.class))) {
            Expression value = annotation.getMember("value");
            if (containsSuppressionValue(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSuppressionValue(Expression value) {
        if (value instanceof ConstantExpression) {
            return SUPPRESS_WARNINGS_VALUE.equals(((ConstantExpression) value).getValue());
        }
        if (value instanceof ListExpression) {
            for (Expression element : ((ListExpression) value).getExpressions()) {
                if (containsSuppressionValue(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void reportUnsafeQuery(ASTNode node, String methodName) {
        String message = "[GORM] The query string passed to '" + methodName + "' was built from a " +
                "GString that Groovy already coerced to a plain String, so any interpolated " +
                "values are now embedded as raw, unescaped text - this is a query injection " +
                "risk. Keep the value as a GString when calling '" + methodName + "' (GORM turns " +
                "GString interpolations into bound query parameters automatically), or pass " +
                "named/positional parameters explicitly. To suppress this check for a reviewed, " +
                "safe call site, add @SuppressWarnings(\"" + SUPPRESS_WARNINGS_VALUE + "\") to the " +
                "enclosing method.";
        sourceUnit.getErrorCollector().addErrorAndContinue(message, node, sourceUnit);
    }
}

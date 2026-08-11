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

import java.util.List;

import org.codehaus.groovy.ast.ClassCodeExpressionTransformer;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.DynamicVariable;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.ArgumentListExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.SourceUnit;

import org.grails.taglib.CompiledTagInvocation;
import org.grails.taglib.index.TagLibraryIndex;
import org.grails.taglib.index.TagLibraryIndexEntry;

/**
 * Rewrites a call to a known tag into a direct invocation.
 *
 * <p>Writing {@code g.message(code: 'x')} reaches the tag library through {@code propertyMissing} to
 * find the namespace and {@code invokeMethod} to find the tag, which is a dynamic call site even in a
 * statically compiled class. Both the namespace and the tag name are fixed in the source, and the tag
 * library index says whether that tag exists, so the call is replaced with
 * {@link CompiledTagInvocation#invoke}, an ordinary static method call.
 *
 * <p>Only calls whose shape is evident from the source are rewritten: a tag takes attributes, a body,
 * both or neither, and where the arguments cannot be recognised as that the call is left alone and
 * resolves as it did before. A namespace the index does not know, or a tag it does not hold, is also
 * left alone, which is what keeps a tag library registered at runtime working.
 *
 * @since 8.0.0
 */
public class CompiledTagCallRewriter extends ClassCodeExpressionTransformer {

    private static final ClassNode INVOCATION_TYPE = ClassHelper.make(CompiledTagInvocation.class);
    private static final String LOOKUP_ACCESSOR = "getTagLibraryLookup";
    private static final String INVOKE = "invoke";

    private final SourceUnit sourceUnit;
    private final TagLibraryIndex index;
    private final ClassNode classNode;
    private int rewritten;

    public CompiledTagCallRewriter(SourceUnit sourceUnit, TagLibraryIndex index, ClassNode classNode) {
        this.sourceUnit = sourceUnit;
        this.index = index;
        this.classNode = classNode;
    }

    /**
     * @return how many calls were rewritten, for tests to assert against
     */
    public int getRewrittenCount() {
        return rewritten;
    }

    public void rewrite() {
        for (MethodNode method : classNode.getMethods()) {
            // getMethods() reaches inherited methods, whose bodies belong to the class that declared
            // them. Rewriting one here would change a superclass through a subclass that happens to be
            // able to call tags. Trait methods are woven as declarations on this class and so remain.
            if (method.getDeclaringClass() != null && !classNode.equals(method.getDeclaringClass())) {
                continue;
            }
            if (method.getCode() != null && !method.isAbstract()) {
                visitClassCodeContainer(method.getCode());
            }
        }
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    @Override
    public Expression transform(Expression expression) {
        if (expression instanceof MethodCallExpression call) {
            Expression rewrite = rewriteTagCall(call);
            if (rewrite != null) {
                rewritten++;
                return rewrite;
            }
        }
        return super.transform(expression);
    }

    private Expression rewriteTagCall(MethodCallExpression call) {
        String namespace = namespaceOf(call.getObjectExpression());
        if (namespace == null || !index.hasNamespace(namespace)) {
            return null;
        }
        if (!(call.getMethod() instanceof ConstantExpression methodName) ||
                methodName.getValue() == null) {
            return null;
        }
        String tagName = methodName.getValue().toString();
        TagLibraryIndexEntry entry = index.lookup(namespace, tagName);
        if (entry == null) {
            // Unknown here, or declared by more than one tag library and so deliberately unresolved.
            return null;
        }
        if (!entry.isBindable()) {
            // A closure-based tag carries no signature to bind to, so it keeps being dispatched.
            return null;
        }
        if (isShadowed(call.getObjectExpression(), namespace)) {
            return null;
        }

        Expression[] attrsAndBody = attributesAndBody(call.getArguments());
        if (attrsAndBody == null) {
            return null;
        }
        ArgumentListExpression invocationArgs = new ArgumentListExpression();
        invocationArgs.addExpression(new MethodCallExpression(VariableExpression.THIS_EXPRESSION,
                LOOKUP_ACCESSOR, MethodCallExpression.NO_ARGUMENTS));
        invocationArgs.addExpression(new ConstantExpression(namespace));
        invocationArgs.addExpression(new ConstantExpression(tagName));
        invocationArgs.addExpression(attrsAndBody[0]);
        invocationArgs.addExpression(attrsAndBody[1]);
        return new StaticMethodCallExpression(INVOCATION_TYPE, INVOKE, invocationArgs);
    }

    /**
     * @return the attributes and body to pass, or {@code null} when the arguments are not recognisably
     *         a tag call and it should be left to resolve as before
     */
    private Expression[] attributesAndBody(Expression arguments) {
        if (!(arguments instanceof TupleExpression tuple)) {
            return null;
        }
        List<Expression> args = tuple.getExpressions();
        Expression noAttributes = new MapExpression();
        Expression noBody = ConstantExpression.NULL;
        switch (args.size()) {
            case 0:
                return new Expression[] { noAttributes, noBody };
            case 1:
                if (args.get(0) instanceof MapExpression) {
                    return new Expression[] { transform(args.get(0)), noBody };
                }
                if (args.get(0) instanceof ClosureExpression) {
                    return new Expression[] { noAttributes, transform(args.get(0)) };
                }
                return null;
            case 2:
                if (args.get(0) instanceof MapExpression && args.get(1) instanceof ClosureExpression) {
                    return new Expression[] { transform(args.get(0)), transform(args.get(1)) };
                }
                return null;
            default:
                return null;
        }
    }

    /**
     * @return the namespace a call is made through, or {@code null} when the receiver is not a plain
     *         name that could be one
     */
    private static String namespaceOf(Expression objectExpression) {
        if (objectExpression instanceof VariableExpression variable) {
            return variable.isThisExpression() || variable.isSuperExpression() ? null : variable.getName();
        }
        if (objectExpression instanceof PropertyExpression property &&
                property.getObjectExpression() instanceof VariableExpression receiver &&
                receiver.isThisExpression()) {
            return property.getPropertyAsString();
        }
        return null;
    }

    /**
     * Whether something in scope has already claimed the name, in which case it is that thing rather
     * than a tag library namespace.
     *
     * <p>A namespace is not declared anywhere: it is reached because nothing else answers to the name.
     * A local variable, a parameter or a field called {@code g} does answer to it, and rewriting such a
     * call would silently send it to a tag library instead of the object the author wrote.
     */
    private boolean isShadowed(Expression objectExpression, String namespace) {
        if (objectExpression instanceof VariableExpression variable) {
            Variable accessed = variable.getAccessedVariable();
            // A name that resolves to something - a local, a parameter, a field, a property - is that
            // thing. Only a name nothing has claimed is left to mean a namespace.
            if (accessed != null && !(accessed instanceof DynamicVariable)) {
                return true;
            }
        }
        // Reached as this.g, or as a bare name resolved dynamically: a field or property of that name
        // anywhere in the hierarchy is the member, not a namespace.
        return classNode.getField(namespace) != null ||
                classNode.getProperty(namespace) != null ||
                hasGetter(namespace);
    }

    private boolean hasGetter(String namespace) {
        String getterName = "get" + Character.toUpperCase(namespace.charAt(0)) + namespace.substring(1);
        return !classNode.getMethods(getterName).isEmpty();
    }
}

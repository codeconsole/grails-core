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
package org.grails.taglib.discovery;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import groovy.lang.Closure;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;

import org.grails.taglib.index.TagLibraryIndexEntry;

/**
 * Reads a tag library's namespace and tag names from its syntax tree.
 *
 * <p>Classification is delegated to {@link TagDiscoveryRules}, the same rules an application applies
 * when it registers tag libraries, so the two cannot disagree about what a tag is. What remains here
 * is reading the namespace and gathering the candidate members from the tree.
 *
 * @since 8.0.0
 */
public final class TagLibraryAstDiscovery {

    public static final String DEFAULT_NAMESPACE = "g";

    private static final String NAMESPACE_FIELD = "namespace";

    private static final ClassNode CLOSURE_TYPE = ClassHelper.make(Closure.class);

    private TagLibraryAstDiscovery() {
    }

    /**
     * Resolves the namespace the way {@code DefaultGrailsTagLibClass} does at runtime, which reads the
     * static {@code namespace} property through the class hierarchy.
     *
     * @param classNode the tag library
     * @return the namespace, or {@code null} when it cannot be determined without running the code, in
     *         which case no descriptor should be written and the tag library resolves dynamically
     */
    public static String resolveNamespace(ClassNode classNode) {
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
     * @param parameterNamesRetained whether this compilation writes parameter names into the class file
     * @return every tag the library declares, whether as a tag method or a legacy closure field
     */
    /**
     * @param classNode the tag library
     * @param parameterNamesRetained whether this compilation writes parameter names into the class file
     * @return each tag mapped to how it is implemented, so that a caller can tell a tag it can bind to
     *         from one it must dispatch dynamically
     */
    public static Map<String, TagLibraryIndexEntry.Kind> findTags(ClassNode classNode,
            boolean parameterNamesRetained) {
        Map<String, TagLibraryIndexEntry.Kind> tags = new LinkedHashMap<>();
        for (MethodNode method : classNode.getMethods()) {
            if (method.getDeclaringClass() != null && !classNode.equals(method.getDeclaringClass())) {
                continue;
            }
            if (TagDiscoveryRules.isTagMethod(new AstTagMethodView(method, parameterNamesRetained))) {
                tags.put(method.getName(), TagLibraryIndexEntry.Kind.METHOD);
            }
        }
        for (FieldNode field : classNode.getFields()) {
            if (!field.isStatic() && field.getType() != null && CLOSURE_TYPE.equals(field.getType())) {
                // A closure carries no signature, so a call to it cannot be bound when compiled.
                tags.put(field.getName(), TagLibraryIndexEntry.Kind.LEGACY_CLOSURE);
            }
        }
        return tags;
    }

    public static Collection<String> findTagNames(ClassNode classNode, boolean parameterNamesRetained) {
        Set<String> tagNames = new LinkedHashSet<>();
        for (MethodNode method : classNode.getMethods()) {
            // TagMethodInvoker scans getDeclaredMethods(), so a method inherited from a superclass is
            // not dispatchable and must not be recorded. Trait methods are woven as declarations on the
            // implementing class and so are still seen here.
            if (method.getDeclaringClass() != null && !classNode.equals(method.getDeclaringClass())) {
                continue;
            }
            if (TagDiscoveryRules.isTagMethod(new AstTagMethodView(method, parameterNamesRetained))) {
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
}

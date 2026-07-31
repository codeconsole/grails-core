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

package org.grails.datastore.gorm.transform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;

import org.grails.datastore.mapping.model.config.GormProperties;
import org.grails.datastore.mapping.reflect.AstUtils;
import org.grails.datastore.mapping.reflect.ClassPropertyFetcher;
import org.grails.datastore.mapping.reflect.NameUtils;

/**
 * Utilities for resolving property names from domain classes etc.
 *
 * @author Graeme Rocher
 * @since 6.1
 */
public class AstPropertyResolveUtils {

    /**
     * Key under which the resolved property map is stashed via {@link ClassNode#getNodeMetaData(Object, java.util.function.Function)}.
     * <p>
     * Earlier versions of this class cached resolved properties in a single static, process-wide
     * {@code Map} keyed by class name (later by {@code ClassNode} identity). Both designs share a
     * problem: a static map is never emptied, so every {@code ClassNode} ever looked up - and, for
     * a primary node, the {@code GroovyClassLoader}/{@code CompileUnit} it pins via
     * {@link ClassNode#getModule()} - is retained for the lifetime of the JVM. In a long-lived
     * process that repeatedly compiles Groovy (a Gradle daemon reusing its Groovy compiler across
     * builds, a dev-mode recompile loop), that is an unbounded classloader leak.
     * <p>
     * Storing the resolved properties as metadata on the {@code ClassNode} itself instead avoids
     * both hazards this class has previously had to fix:
     * <ul>
     *     <li>No collision is possible between distinct {@code ClassNode} instances that happen to
     *     share a name (e.g. classes compiled without a package, or the same source compiled twice
     *     in separate {@code GroovyClassLoader}s) - each instance owns its own metadata storage, so
     *     there is no shared key space to collide on in the first place.</li>
     *     <li>No leak is possible - the cached data is only reachable through the {@code ClassNode}
     *     it describes, so it becomes eligible for garbage collection at the same time as the node
     *     (and the compilation/classloader it belongs to) rather than being pinned forever by a
     *     static field.</li>
     * </ul>
     * Per {@link ClassNode#getModule()}'s own convention, the cache is stored on
     * {@link ClassNode#redirect()} - the node a placeholder/generics-parameterized reference
     * ultimately stands in for - so that looking a class up through different reference nodes still
     * shares one cache entry.
     * <p>
     * No explicit synchronization is used. A given {@code ClassNode} instance is built by, and only
     * ever mutated by, the single compiler thread compiling its source unit; nothing in this
     * codebase compiles the same {@code ClassNode} from two threads at once (Gradle's test workers
     * are separate JVMs, not threads sharing this cache, and this module does not enable JUnit's
     * in-JVM parallel execution). The realistic concurrency exposure - if it ever arises - is
     * distinct {@code ClassNode}s being resolved concurrently by independent {@code compileGroovy}
     * tasks, each populating its own node's metadata; that is naturally race-free since the nodes
     * involved are different objects.
     */
    private static final String PROPERTIES_CACHE_KEY = AstPropertyResolveUtils.class.getName() + ".properties";

    /**
     * Resolves the type of of the given property
     *
     * @param classNode The class node
     * @param propertyName The property
     * @return The type
     */
    public static ClassNode getPropertyType(ClassNode classNode, String propertyName) {
        if (propertyName == null || propertyName.length() == 0) {
            return null;
        }
        Map<String, ClassNode> cachedProperties = getPropertiesFromCache(classNode);
        if (cachedProperties.containsKey(propertyName)) {
            return cachedProperties.get(propertyName);
        }
        ClassNode type = null;
        PropertyNode property = classNode.getProperty(propertyName);
        if (property != null) {
            type = property.getType();
        } else {
            MethodNode methodNode = classNode.getMethod(NameUtils.getGetterName(propertyName), new Parameter[0]);
            if (methodNode != null) {
                type = methodNode.getReturnType();
            } else {
                FieldNode fieldNode = classNode.getDeclaredField(propertyName);
                if (fieldNode != null) {
                    type = fieldNode.getType();
                }
            }
        }
        return type;
    }

    /**
     * Resolves the property names for the given class node
     *
     * @param classNode The class node
     * @return The property names
     */
    public static List<String> getPropertyNames(ClassNode classNode) {
        Map<String, ClassNode> cachedProperties = getPropertiesFromCache(classNode);
        return new ArrayList<>(cachedProperties.keySet());
    }

    private static Map<String, ClassNode> getPropertiesFromCache(ClassNode classNode) {
        ClassNode cacheHolder = classNode.redirect();
        return cacheHolder.getNodeMetaData(PROPERTIES_CACHE_KEY, key -> computeProperties(cacheHolder));
    }

    private static Map<String, ClassNode> computeProperties(ClassNode classNode) {
        Map<String, ClassNode> newProperties = new HashMap<>();
        boolean isDomainClass = AstUtils.isDomainClass(classNode);
        if (isDomainClass) {
            newProperties.put(GormProperties.IDENTITY, new ClassNode(Long.class));
            newProperties.put(GormProperties.VERSION, new ClassNode(Long.class));
        }
        ClassNode currentNode = classNode;
        while (currentNode != null && !currentNode.equals(ClassHelper.OBJECT_TYPE)) {
            populatePropertiesForClassNode(currentNode, newProperties, isDomainClass, !isDomainClass);
            currentNode = currentNode.getSuperClass();
        }
        return newProperties;
    }

    private static void populatePropertiesForClassNode(ClassNode classNode, Map<String, ClassNode> cachedProperties, boolean isDomainClass, boolean allowAbstract) {
        List<MethodNode> methods = classNode.getMethods();
        for (MethodNode method : methods) {
            String methodName = method.getName();
            if (AstUtils.isGetter(method)) {
                if (!allowAbstract && method.isAbstract()) continue;
                String propertyName = NameUtils.getPropertyNameForGetterOrSetter(methodName);
                if (GormProperties.META_CLASS.equals(propertyName)) continue;
                if (isDomainClass && (GormProperties.HAS_MANY.equals(propertyName) || GormProperties.BELONGS_TO.equals(propertyName) || GormProperties.HAS_ONE.equals(propertyName))) {
                    FieldNode field = classNode.getField(propertyName);
                    if (field != null) {
                        populatePropertiesForInitialExpression(cachedProperties, field.getInitialExpression());
                    }
                } else if (!method.isStatic()) {
                    cachedProperties.put(propertyName, method.getReturnType());
                }
            }
        }
        List<PropertyNode> properties = classNode.getProperties();
        for (PropertyNode property : properties) {

            String propertyName = property.getName();
            if (propertyName.equals(GormProperties.META_CLASS)) continue;
            if (isDomainClass && (GormProperties.HAS_MANY.equals(propertyName) || GormProperties.BELONGS_TO.equals(propertyName) || GormProperties.HAS_ONE.equals(propertyName))) {
                Expression initialExpression = property.getInitialExpression();
                populatePropertiesForInitialExpression(cachedProperties, initialExpression);
            } else {
                cachedProperties.put(propertyName, property.getType());
            }
        }

        if (isDomainClass && classNode.isResolved()) {
            ClassPropertyFetcher propertyFetcher = ClassPropertyFetcher.forClass(classNode.getTypeClass());
            cachePropertiesForAssociationMetadata(cachedProperties, propertyFetcher, GormProperties.HAS_MANY);
            cachePropertiesForAssociationMetadata(cachedProperties, propertyFetcher, GormProperties.BELONGS_TO);
            cachePropertiesForAssociationMetadata(cachedProperties, propertyFetcher, GormProperties.HAS_ONE);
        }

    }

    private static void cachePropertiesForAssociationMetadata(Map<String, ClassNode> cachedProperties, ClassPropertyFetcher propertyFetcher, String associationMetadataName) {
        if (propertyFetcher.isReadableProperty(associationMetadataName)) {
            Object propertyValue = propertyFetcher.getPropertyValue(associationMetadataName);
            if (propertyValue instanceof Map) {
                Map hasManyMap = (Map) propertyValue;
                for (Object propertyName : hasManyMap.keySet()) {
                    Object val = hasManyMap.get(propertyName);
                    if (val instanceof Class) {
                        cachedProperties.put(propertyName.toString(), ClassHelper.make((Class) val).getPlainNodeReference());
                    }
                }
            }
        }
    }

    private static void populatePropertiesForInitialExpression(Map<String, ClassNode> cachedProperties, Expression initialExpression) {
        if (initialExpression instanceof MapExpression) {
            MapExpression me = (MapExpression) initialExpression;
            List<MapEntryExpression> mapEntryExpressions = me.getMapEntryExpressions();
            for (MapEntryExpression mapEntryExpression : mapEntryExpressions) {
                Expression keyExpression = mapEntryExpression.getKeyExpression();
                Expression valueExpression = mapEntryExpression.getValueExpression();
                if (valueExpression instanceof ClassExpression) {
                    cachedProperties.put(keyExpression.getText(), valueExpression.getType());
                }
            }
        }
    }

}

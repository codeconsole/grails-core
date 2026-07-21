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
package org.grails.datastore.gorm.transform

import java.lang.reflect.Modifier

import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import spock.lang.Specification

/**
 * {@link AstPropertyResolveUtils} caches resolved property metadata in a static, process-wide
 * map keyed by {@link ClassNode}. Two distinct compilations (e.g. the same source parsed in two
 * different {@code GroovyClassLoader}s, as happens for dynamically-generated sources and in
 * tests) produce distinct {@code ClassNode} instances that can legitimately share the exact same
 * name - {@code ClassNode#equals(Object)} compares by name, so a naive name- or equals()-based
 * cache key would conflate them, corrupting the resolved properties of one class with those of
 * an unrelated class that happens to share its name. This spec proves the cache keys strictly by
 * {@code ClassNode} identity, so same-named-but-distinct class nodes never contaminate each
 * other's cached property data.
 */
class AstPropertyResolveUtilsSpec extends Specification {

    void "property lookups for two same-named ClassNodes in different packages do not corrupt each other"() {
        given: 'two distinct ClassNodes with the same simple name declared in different packages'
        ClassNode first = new ClassNode('org.example.one.Widget', Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        first.addProperty('color', Modifier.PUBLIC, ClassHelper.STRING_TYPE, null, null, null)

        ClassNode second = new ClassNode('org.example.two.Widget', Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        second.addProperty('weight', Modifier.PUBLIC, ClassHelper.Integer_TYPE, null, null, null)

        when: 'the first class node is resolved, populating its cache entry'
        List<String> firstProperties = AstPropertyResolveUtils.getPropertyNames(first)

        then: 'only its own property is resolved'
        firstProperties.contains('color')
        !firstProperties.contains('weight')

        when: 'the second, differently-packaged, same-simple-name class node is resolved'
        List<String> secondProperties = AstPropertyResolveUtils.getPropertyNames(second)

        then: 'its own property is resolved, not leaked from the first class node'
        secondProperties.contains('weight')
        !secondProperties.contains('color')

        and: 'the first class node cache entry remains unaffected by resolving the second'
        List<String> firstPropertiesAfter = AstPropertyResolveUtils.getPropertyNames(first)
        firstPropertiesAfter.contains('color')
        !firstPropertiesAfter.contains('weight')
    }

    void "property lookups for two distinct ClassNode instances with the exact same unqualified name do not corrupt each other"() {
        given: 'two distinct ClassNode instances - as produced by two separate compilations - sharing an identical unqualified name'
        ClassNode first = new ClassNode('Widget', Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        first.addProperty('color', Modifier.PUBLIC, ClassHelper.STRING_TYPE, null, null, null)

        ClassNode second = new ClassNode('Widget', Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        second.addProperty('weight', Modifier.PUBLIC, ClassHelper.Integer_TYPE, null, null, null)

        expect: 'the two ClassNode instances compare equal by name - the exact condition that would collide in a name-keyed or equals()-keyed cache'
        first == second
        first.hashCode() == second.hashCode()
        !first.is(second)

        when: 'both class nodes are resolved'
        List<String> firstProperties = AstPropertyResolveUtils.getPropertyNames(first)
        List<String> secondProperties = AstPropertyResolveUtils.getPropertyNames(second)

        then: 'each keeps its own, independently-resolved properties despite comparing equal'
        firstProperties.contains('color')
        !firstProperties.contains('weight')
        secondProperties.contains('weight')
        !secondProperties.contains('color')
    }

    void "getPropertyType resolves and caches the type of a declared property"() {
        given: 'a class node with a declared property'
        ClassNode classNode = new ClassNode('org.example.PropertyTypeWidget', Modifier.PUBLIC, ClassHelper.OBJECT_TYPE)
        classNode.addProperty('label', Modifier.PUBLIC, ClassHelper.STRING_TYPE, null, null, null)

        expect: 'the resolved property type matches the declared type, both on first (cache-populating) and second (cache-hit) lookup'
        AstPropertyResolveUtils.getPropertyType(classNode, 'label') == ClassHelper.STRING_TYPE
        AstPropertyResolveUtils.getPropertyType(classNode, 'label') == ClassHelper.STRING_TYPE
    }
}

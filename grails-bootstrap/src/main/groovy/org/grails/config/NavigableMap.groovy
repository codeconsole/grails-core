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
package org.grails.config

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import org.codehaus.groovy.runtime.DefaultGroovyMethods

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * @deprecated This class is deprecated to reduce complexity, improve performance, and increase maintainability. Use {@code config.getProperty(String key, Class<T> targetType)} instead.
 */
@Deprecated
@EqualsAndHashCode
@CompileStatic
class NavigableMap implements Map<String, Object>, Cloneable {

    private static final Logger LOG = LoggerFactory.getLogger(NavigableMap)
    private static final int MAX_DOTTED_BRACKETS = 8

    final NavigableMap rootConfig
    final List<String> path
    final Map<String, Object> delegateMap
    final String dottedPath

    NavigableMap() {
        rootConfig = this
        path = []
        dottedPath = ''
        delegateMap = new LinkedHashMap<>()
    }

    NavigableMap(NavigableMap rootConfig, List<String> path) {
        super()
        this.rootConfig = rootConfig
        this.path = path
        dottedPath = path.join('.')
        delegateMap = new LinkedHashMap<>()
    }

    private NavigableMap(NavigableMap rootConfig, List<String> path, Map<String, Object> delegateMap) {
        this.rootConfig = rootConfig
        this.path = path
        dottedPath = path.join('.')
        this.delegateMap = delegateMap
    }

    @Override
    String toString() {
        delegateMap.toString()
    }

    @Override
    NavigableMap clone() {
        new NavigableMap(getRootConfig(), getPath(), new LinkedHashMap<>(getDelegateMap()))
    }

    @Override
    int size() {
        delegateMap.size()
    }

    @Override
    boolean isEmpty() {
        delegateMap.isEmpty()
    }

    @Override
    boolean containsKey(Object key) {
        delegateMap.containsKey(key)
    }

    @Override
    boolean containsValue(Object value) {
        delegateMap.containsValue(value)
    }

    @CompileDynamic
    @Override
    Object get(Object key) {
        Object result = delegateMap.get(key)
        if (result != null) {
            return result
        }
        null
    }

    @Override
    Object put(String key, Object value) {
        delegateMap.put(key, value)
    }

    @Override
    Object remove(Object key) {
        delegateMap.remove(key)
    }

    @Override
    void putAll(Map<? extends String, ? extends Object> m) {
        delegateMap.putAll(m)
    }

    @Override
    void clear() {
        delegateMap.clear()
    }

    @Override
    Set<String> keySet() {
        delegateMap.keySet()
    }

    @Override
    Collection<Object> values() {
        delegateMap.values()
    }

    @Override
    Set<Map.Entry<String, Object>> entrySet() {
        delegateMap.entrySet()
    }

    void merge(Map sourceMap, boolean parseFlatKeys = false) {
        mergeMaps(this, '', this, sourceMap, parseFlatKeys)
    }

    private void mergeMaps(NavigableMap rootMap,
                           String path,
                           NavigableMap targetMap,
                           Map sourceMap,
                           boolean parseFlatKeys) {

        for (Entry entry in sourceMap) {
            Object sourceKeyObject = entry.key
            Object sourceValue = entry.value
            String sourceKey = String.valueOf(sourceKeyObject)
            if (parseFlatKeys) {
                String[] keyParts = sourceKey.split(/\./)
                if (keyParts.length > 1) {
                    mergeMapEntry(rootMap, path, targetMap, sourceKey, sourceValue, parseFlatKeys)
                    def pathParts = keyParts[0..-2]
                    Map actualTarget = targetMap.navigateSubMap(pathParts as List, true)
                    sourceKey = keyParts[-1]
                    // the flat key of the entry is its full path: that of the map holding the dotted key, then the key
                    String nestedPath = path ? "${path}.${pathParts.join('.')}" : pathParts.join('.')
                    mergeMapEntry(rootMap, nestedPath, actualTarget, sourceKey, sourceValue, parseFlatKeys)
                } else {
                    mergeMapEntry(rootMap, path, targetMap, sourceKey, sourceValue, parseFlatKeys)
                }
            } else {
                mergeMapEntry(rootMap, path, targetMap, sourceKey, sourceValue, parseFlatKeys)
            }
        }
    }

    protected void mergeMapEntry(NavigableMap rootMap, String path, NavigableMap targetMap, String sourceKey, Object sourceValue, boolean parseFlatKeys, boolean isNestedSet = false) {
        int subscriptStart = sourceKey.indexOf('[')
        int subscriptEnd = sourceKey.indexOf(']')
        if (subscriptEnd > subscriptStart) {
            if (subscriptStart > -1) {
                String k = sourceKey[0..<subscriptStart]
                String index = sourceKey[subscriptStart + 1..<subscriptEnd]
                String remainder = subscriptEnd != sourceKey.length() - 1 ? sourceKey[subscriptEnd + 2..-1] : null
                if (remainder) {

                    boolean isNumber = index.isNumber()
                    if (isNumber) {
                        int i = index.toInteger()
                        def currentValue = targetMap.get(k)
                        List list = currentValue instanceof List ? currentValue : []
                        if (list.size() > i) {
                            def v = list.get(i)
                            if (v instanceof Map) {
                                ((Map) v).put(remainder, sourceValue)
                            } else {
                                Map newMap = [:]
                                newMap.put(remainder, sourceValue)
                                fill(list, i, null)
                                list.set(i, newMap)
                            }
                        } else {
                            Map newMap = [:]
                            newMap.put(remainder, sourceValue)
                            fill(list, i, null)
                            list.set(i, newMap)
                        }
                        targetMap.put(k, list)
                    } else {
                        def currentValue = targetMap.get(k)
                        Map nestedMap = currentValue instanceof Map ? currentValue : [:]
                        targetMap.put(k, nestedMap)

                        def v = nestedMap.get(index)
                        if (v instanceof Map) {
                            ((Map) v).put(remainder, sourceValue)
                        } else {
                            Map newMap = [:]
                            newMap.put(remainder, sourceValue)
                            nestedMap.put(index, newMap)
                        }
                    }
                } else {
                    def currentValue = targetMap.get(k)
                    if (index.isNumber()) {
                        List list = currentValue instanceof List ? currentValue : []
                        int i = index.toInteger()
                        fill(list, i, null)
                        list.set(i, sourceValue)
                        targetMap.put(k, list)
                    } else {
                        Map nestedMap = currentValue instanceof Map ? currentValue : [:]
                        targetMap.put(k, nestedMap)
                        nestedMap.put(index, sourceValue)
                    }
                    targetMap.put(sourceKey, sourceValue)
                }

            }
        } else {
            Object currentValue = targetMap.containsKey(sourceKey) ? targetMap.get(sourceKey) : null
            Object newValue
            if (sourceValue instanceof Map) {
                List<String> newPathList = []
                newPathList.addAll(targetMap.getPath())
                newPathList.add(sourceKey)
                NavigableMap subMap
                if (currentValue instanceof NavigableMap) {
                    subMap = (NavigableMap) currentValue
                }
                else {
                    subMap = new NavigableMap(targetMap.getRootConfig(), newPathList.asImmutable())
                    if (currentValue instanceof Map) {
                        subMap.putAll((Map) currentValue)
                    }
                }
                String newPath = path ? "${path}.${sourceKey}" : sourceKey
                mergeMaps(rootMap, newPath , subMap, (Map) sourceValue, parseFlatKeys)
                newValue = subMap
            } else {
                newValue = sourceValue
            }
            if (isNestedSet && newValue == null) {
                if (path) {

                    def subMap = rootMap.get(path)
                    if (subMap instanceof Map) {
                        subMap.remove(sourceKey)
                    }
                    def keysToRemove = rootMap.keySet().findAll() { String key ->
                        key.startsWith("${path}.")
                    }
                    for (key in keysToRemove) {
                        rootMap.remove(key)
                    }
                }
                targetMap.remove(sourceKey)
            } else {
                mergeMapEntry(targetMap, sourceKey, newValue)
                if (path) {
                    // the flat key holds the value the nested entry holds, as the hook stored it, so a placeholder in it
                    // is resolved once, for both
                    rootMap.put("${path}.${sourceKey}".toString(), targetMap.get(sourceKey))
                }
            }
        }
    }

    protected Object mergeMapEntry(NavigableMap targetMap, String sourceKey, newValue) {
        targetMap.put(sourceKey, newValue)
    }

    Object getAt(Object key) {
        getProperty(String.valueOf(key))
    }

    void setAt(Object key, Object value) {
        setProperty(String.valueOf(key), value)
    }

    Object getProperty(String name) {
        if (!containsKey(name)) {
            return new NullSafeNavigator(this, [name].asImmutable())
        }
        Object result = get(name)
        return result
    }

    void setProperty(String name, Object value) {
        mergeMapEntry(rootConfig, dottedPath, this, name, value, false, true)
    }

    Object navigate(String... path) {
        return navigateMap(this, path)
    }

    private Object navigateMap(Map<String, Object> map, String... path) {
        if (map == null || path == null) return null
        if (path.length == 0) {
            return map
        } else if (path.length == 1) {
            return map.get(path[0])
        } else {
            def submap = map.get(path[0])
            if (submap instanceof Map) {
                return navigateMap((Map<String, Object>) submap, path.tail())
            }
            return submap
        }
    }

    private void fill(List list, Integer toIndex, Object value) {
        if (toIndex >= list.size()) {
            for (int i = list.size(); i <= toIndex; i++) {
                list.add(i, value)
            }
        }
    }

    NavigableMap navigateSubMap(List<String> path, boolean createMissing) {
        NavigableMap rootMap = this
        NavigableMap currentMap = this
        StringBuilder accumulatedPath = new StringBuilder()
        boolean isFirst = true
        for (String pathElement : path) {
            if (!isFirst) {
                accumulatedPath.append('.').append(pathElement)
            }
            else {
                isFirst = false
                accumulatedPath.append(pathElement)
            }

            Object currentItem = currentMap.get(pathElement)
            if (currentItem instanceof NavigableMap) {
                currentMap = (NavigableMap) currentItem
            } else if (createMissing) {
                List<String> newPathList = []
                newPathList.addAll(currentMap.getPath())
                newPathList.add(pathElement)

                Map<String, Object> newMap = new NavigableMap(currentMap.getRootConfig(), newPathList.asImmutable())
                currentMap.put(pathElement, newMap)

                def fullPath = accumulatedPath.toString()
                if (!rootMap.containsKey(fullPath)) {
                    rootMap.put(fullPath, newMap)
                }
                currentMap = newMap
            } else {
                return null
            }
        }
        currentMap
    }

    Map<String, Object> toFlatConfig() {
        Map<String, Object> flatConfig = [:]
        flattenKeys(flatConfig, this, [], false)
        removeDottedDuplicates(flatConfig)
        flatConfig
    }

    Properties toProperties() {
        Properties properties = new Properties()
        flattenKeys((Map<Object, Object>) properties, this, [], true)
        removeDottedDuplicates((Map<Object, Object>) properties)
        properties
    }

    /**
     * Removes each name that only writes a bracketed name with dots, such as {@code app.routes./api/**} beside
     * {@code app.routes[/api/**]}, which Spring Boot would bind as the key {@code api}. A map written by map subscripts
     * holds its entries under their bracketed names and is walked under their dotted ones too, so each entry is
     * flattened under both. A dotted name is removed only when every bracketed name that it writes with dots holds the
     * same value; where they differ, as when a later write changed one of them, every name stays, as it was before. A
     * bracketed key with a dot in it, {@code a[b.c]}, names a key of {@code a}, so the dotted name {@code a.b.c} is
     * a property of its own, and it stays when the map holds it as a key, as a put of {@code a.b.c} does, or the nested
     * maps hold a value under that path.
     */
    private void removeDottedDuplicates(Map<? extends Object, Object> flatConfig) {
        Map<String, List<Object>> bracketedValues = [:]
        Set<String> pathsToCheck = [] as Set<String>
        for (Map.Entry<? extends Object, Object> entry in flatConfig.entrySet()) {
            String name = String.valueOf(entry.key)
            if (name.indexOf('[') > -1) {
                for (Map.Entry<String, Boolean> dottedName in dottedNamesOf(name).entrySet()) {
                    List<Object> values = bracketedValues.get(dottedName.key)
                    if (values == null) {
                        values = []
                        bracketedValues.put(dottedName.key, values)
                    }
                    values.add(entry.value)
                    if (dottedName.value) {
                        pathsToCheck.add(dottedName.key)
                    }
                }
            }
        }
        for (Map.Entry<String, List<Object>> candidate in bracketedValues.entrySet()) {
            if (!flatConfig.containsKey(candidate.key)) {
                continue
            }
            Object value = flatConfig.get(candidate.key)
            if (!candidate.value.every { Object bracketedValue -> bracketedValue == value }) {
                continue
            }
            if (candidate.key in pathsToCheck && (containsKey(candidate.key) || navigate(candidate.key.tokenize('.') as String[]) != null)) {
                continue
            }
            flatConfig.remove(candidate.key)
        }
    }

    /**
     * The names that write some or all of the bracketed map keys of a name with dots, {@code app.routes./api/**} for
     * {@code app.routes[/api/**]}, each with whether one of the keys it writes so has a dot in it. A list index,
     * {@code rows[0]}, is not a map key and stays as it is.
     */
    private static Map<String, Boolean> dottedNamesOf(String name) {
        List<int[]> brackets = []
        int start = name.indexOf('[')
        while (start > -1 && brackets.size() < MAX_DOTTED_BRACKETS) {
            int end = name.indexOf(']', start)
            if (end < 0) {
                break
            }
            if (!name.substring(start + 1, end).isNumber()) {
                brackets.add([start, end] as int[])
            }
            start = name.indexOf('[', end)
        }
        Map<String, Boolean> dottedNames = [:]
        int combinations = 1 << brackets.size()
        for (int mask = 1; mask < combinations; mask++) {
            StringBuilder dottedName = new StringBuilder()
            boolean keyWithDot = false
            int from = 0
            for (int i = 0; i < brackets.size(); i++) {
                if ((mask & (1 << i)) != 0) {
                    int[] bracket = brackets.get(i)
                    dottedName.append(name, from, bracket[0])
                    if (bracket[0] > 0) {
                        dottedName.append('.')
                    }
                    String key = name.substring(bracket[0] + 1, bracket[1])
                    keyWithDot = keyWithDot || key.indexOf('.') > -1
                    dottedName.append(key)
                    from = bracket[1] + 1
                }
            }
            dottedName.append(name, from, name.length())
            dottedNames.put(dottedName.toString(), keyWithDot)
        }
        dottedNames
    }

    private void flattenKeys(Map<? extends Object, Object> flatConfig, Map currentMap, List<String> path, boolean forceStrings) {
        currentMap.each { key, value ->
            String stringKey = String.valueOf(key)
            if (value != null) {
                if (value instanceof Map) {
                    List<String> newPathList = []
                    newPathList.addAll(path)
                    newPathList.add(stringKey)

                    flattenKeys(flatConfig, (Map) value, newPathList.asImmutable(), forceStrings)
                } else {
                    String fullKey
                    if (path) {
                        fullKey = path.join('.') + '.' + stringKey
                    } else {
                        fullKey = stringKey
                    }
                    if (value instanceof Collection) {
                        if (forceStrings) {
                            flatConfig.put(fullKey, ((Collection) value).join(','))
                        } else {
                            flatConfig.put(fullKey, value)
                        }
                        int index = 0
                        for (Object item: (Collection) value) {
                            String collectionKey = "${fullKey}[${index}]".toString()
                            flatConfig.put(collectionKey, forceStrings ? String.valueOf(item) : item)
                            index++
                        }
                    } else {
                        flatConfig.put(fullKey, forceStrings ? String.valueOf(value) : value)
                    }
                }
            }
        }
    }

    @Override
    int hashCode() {
        return delegateMap.hashCode()
    }

    @Override
    boolean equals(Object obj) {
        return delegateMap.equals(obj)
    }

    /**
     * @deprecated This class should be avoided due to known performance reasons. Use {@code config.getProperty(String key, Class<T> targetType)} instead of dot based navigation.
     */
    @Deprecated
    @CompileStatic
    static class NullSafeNavigator implements Map<String, Object> {
        final NavigableMap parent
        final List<String> path

        NullSafeNavigator(NavigableMap parent, List<String> path) {
            this.parent = parent
            this.path = path
            if (LOG.isWarnEnabled()) {
                LOG.warn("Accessing config key '{}' through dot notation has known performance issues, consider using 'config.getProperty(key, targetClass)' instead.", path)
            }
        }

        Object getAt(Object key) {
            getProperty(String.valueOf(key))
        }

        void setAt(Object key, Object value) {
            setProperty(String.valueOf(key), value)
        }

        @Override
        int size() {
            NavigableMap parentMap = parent.navigateSubMap(path, false)
            if (parentMap != null) {
                return parentMap.size()
            }
            return 0
        }

        @Override
        boolean isEmpty() {
            NavigableMap parentMap = parent.navigateSubMap(path, false)
            if (parentMap != null) {
                return parentMap.isEmpty()
            }
            return true
        }

        boolean containsKey(Object key) {
            NavigableMap parentMap = parent.navigateSubMap(path, false)
            if (parentMap == null) return false
            else {
                return parentMap.containsKey(key)
            }
        }

        @Override
        boolean containsValue(Object value) {
            NavigableMap parentMap = parent.navigateSubMap(path, false)
            if (parentMap != null) {
                return parentMap.containsValue(value)
            }
            return false
        }

        @Override
        Object get(Object key) {
            return getAt(key)
        }

        @Override
        Object put(String key, Object value) {
            throw new UnsupportedOperationException('Configuration cannot be modified')
        }

        @Override
        Object remove(Object key) {
            throw new UnsupportedOperationException('Configuration cannot be modified')
        }

        @Override
        void putAll(Map<? extends String, ?> m) {
            throw new UnsupportedOperationException('Configuration cannot be modified')
        }

        @Override
        void clear() {
            throw new UnsupportedOperationException('Configuration cannot be modified')
        }

        @Override
        Set<String> keySet() {
            NavigableMap parentMap = parent.navigateSubMap(path, false)
            if (parentMap != null) {
                return parentMap.keySet()
            }
            return Collections.emptySet()
        }

        @Override
        Collection<Object> values() {
            NavigableMap parentMap = parent.navigateSubMap(path, false)
            if (parentMap != null) {
                return parentMap.values()
            }
            return Collections.emptySet()
        }

        @Override
        Set<Entry<String, Object>> entrySet() {
            NavigableMap parentMap = parent.navigateSubMap(path, false)
            if (parentMap != null) {
                return parentMap.entrySet()
            }
            return Collections.emptySet()
        }

        Object getProperty(String name) {
            NavigableMap parentMap = parent.navigateSubMap(path, false)
            if (parentMap == null) {
                return new NullSafeNavigator(parent, ((path + [name]) as List<String>).asImmutable())
            } else {
                return parentMap.get(name)
            }
        }

        void setProperty(String name, Object value) {
            NavigableMap parentMap = parent.navigateSubMap(path, true)
            parentMap.setProperty(name, value)
        }

        boolean asBoolean() {
            false
        }

        Object invokeMethod(String name, Object args) {
            throw new NullPointerException('Cannot invoke method ' + name + '() on NullSafeNavigator')
        }

        boolean equals(Object to) {
            return to == null || DefaultGroovyMethods.is(this, to)
        }

        Iterator iterator() {
            return Collections.EMPTY_LIST.iterator()
        }

        Object plus(String s) {
            return toString() + s
        }

        Object plus(Object o) {
            throw new NullPointerException('Cannot invoke method plus on NullSafeNavigator')
        }

        boolean is(Object other) {
            return other == null || DefaultGroovyMethods.is(this, other)
        }

        Object asType(Class c) {
            if (c == Boolean || c == boolean) return false
            return null
        }

        String toString() {
            return null
        }

//        public int hashCode() {
//            throw new NullPointerException('Cannot invoke method hashCode() on NullSafeNavigator')
//        }
    }
}

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
package org.grails.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

import groovy.util.ConfigObject;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.convert.ConversionException;
import org.springframework.core.convert.support.ConfigurableConversionService;
import org.springframework.core.convert.support.DefaultConversionService;

import grails.config.Config;
import grails.util.GrailsStringUtils;
import org.grails.core.exceptions.GrailsConfigurationException;

/**
 * A {@link Config} implementation that operates against a {@link org.grails.config.NavigableMap}
 *
 * @deprecated This class behavior is related to {@link org.grails.config.NavigableMap} which will be removed in future. Use {@link grails.config.Config} instead.
 * @author Graeme Rocher
 * @since 3.0
 */
@Deprecated
public abstract class NavigableMapConfig implements Config {
    protected static final Logger LOG = LoggerFactory.getLogger(NavigableMapConfig.class);
    protected ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    protected ConfigurableConversionService conversionService = new DefaultConversionService();
    protected NavigableMap configMap = new NavigableMap() {

        /**
         * A value resolved for a dotted key, which the merge writes twice in a row: under the whole key and then,
         * in the nested map, under its last part. The second write takes the value the first one resolved.
         */
        private Object pendingSource;
        private Object pendingResolved;

        /**
         * Resolves the placeholders in a list, which is stored as it is, and in a value written under an indexed key,
         * {@code rows[0]} or {@code rows[0].url}, which is written into its list or map without the hook below.
         */
        @Override
        protected void mergeMapEntry(NavigableMap rootMap, String path, NavigableMap targetMap, String sourceKey,
                Object sourceValue, boolean parseFlatKeys, boolean isNestedSet) {
            Object value = pendingSource != null && pendingSource == sourceValue ? pendingResolved : resolvedBeforeMerge(sourceKey, sourceValue);
            pendingSource = null;
            pendingResolved = null;
            if (value != sourceValue && parseFlatKeys && sourceKey.split("\\.").length > 1) {
                pendingSource = sourceValue;
                pendingResolved = value;
            }
            super.mergeMapEntry(rootMap, path, targetMap, sourceKey, value, parseFlatKeys, isNestedSet);
        }

        @Override
        protected Object mergeMapEntry(NavigableMap targetMap, String sourceKey, Object newValue) {
            if (newValue instanceof CharSequence) {
                newValue = resolvePlaceholders(newValue.toString());
            }
            return super.mergeMapEntry(targetMap, sourceKey, newValue);
        }

        private Object resolvedBeforeMerge(String sourceKey, Object value) {
            if (value instanceof List) {
                return resolvedElements((List<?>) value);
            }
            int subscriptStart = sourceKey.indexOf('[');
            if ((value instanceof CharSequence || value instanceof Map) && subscriptStart > -1 && sourceKey.indexOf(']') > subscriptStart) {
                return resolvedElement(value);
            }
            return value;
        }

        /**
         * The value with the placeholders in its strings resolved, through the lists and maps it holds. A list or map
         * holding no placeholder is the one given, and any other is a copy of the same kind, so the property source
         * keeps what it holds.
         */
        @SuppressWarnings("unchecked")
        private Object resolvedElement(Object value) {
            if (value instanceof CharSequence) {
                return resolvePlaceholders(value.toString());
            }
            if (value instanceof List) {
                return resolvedElements((List<?>) value);
            }
            if (value instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) value;
                Map<Object, Object> resolved = null;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object element = resolvedElement(entry.getValue());
                    if (resolved == null && !Objects.equals(element, entry.getValue())) {
                        resolved = map instanceof NavigableMap ? (Map<Object, Object>) (Map<?, ?>) ((NavigableMap) map).clone() : new LinkedHashMap<>(map);
                    }
                    if (resolved != null) {
                        resolved.put(entry.getKey(), element);
                    }
                }
                return resolved != null ? resolved : map;
            }
            return value;
        }

        private List<?> resolvedElements(List<?> list) {
            List<Object> resolved = null;
            for (int i = 0; i < list.size(); i++) {
                Object element = resolvedElement(list.get(i));
                if (resolved == null && !Objects.equals(element, list.get(i))) {
                    resolved = new ArrayList<>(list);
                }
                if (resolved != null) {
                    resolved.set(i, element);
                }
            }
            return resolved != null ? resolved : list;
        }
    };

    @Override
    public int hashCode() {
        return configMap.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof NavigableMapConfig) {
            return this.configMap.equals(((NavigableMapConfig) obj).configMap);
        }
        return false;
    }

    @Override
    public String toString() {
        return configMap.toString();
    }

    @Override
    public Object getAt(Object key) {
        return get(key);
    }

    @Override
    public void setAt(Object key, Object value) {
        configMap.put(key.toString(), value);
    }

    @Override
    public int size() {
        return configMap.size();
    }

    @Override
    public boolean isEmpty() {
        return configMap.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return containsProperty(key.toString());
    }

    @Override
    public boolean containsValue(Object value) {
        return configMap.containsValue(value);
    }

    @Override
    public Object get(Object key) {
        return configMap.getProperty(key.toString());
    }

    @Override
    public Object put(String key, Object value) {
        return configMap.put(key, value);
    }

    @Override
    public Object remove(Object key) {
        return configMap.remove(key);
    }

    @Override
    public void putAll(Map<? extends String, ?> m) {
        configMap.putAll(m);
    }

    @Override
    public void clear() {
        configMap.clear();
    }

    @Override
    public Set<String> keySet() {
        return configMap.keySet();
    }

    @Override
    public Collection<Object> values() {
        return configMap.values();
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return configMap.entrySet();
    }

    @Override
    @Deprecated
    public Map<String, Object> flatten() {
        if (LOG.isWarnEnabled()) {
            LOG.warn("A plugin or your application called the flatten() method which can degrade startup performance");
        }
        return configMap;
    }

    @Override
    public Properties toProperties() {
        return configMap.toProperties();
    }

    @Override
    public Object navigate(String... path) {
        return configMap.navigate(path);
    }

    @Override
    public Config merge(Map<String, Object> toMerge) {
        configMap.merge(toMerge, true);
        return this;
    }

    public Object asType(Class c) {
        if (c == Boolean.class || c == boolean.class) return false;
        return null;
    }

    @Override
    public Iterator<Entry<String, Object>> iterator() {
        return DefaultGroovyMethods.iterator(configMap);
    }

    @Override
    public boolean containsProperty(String key) {
        return getProperty(key, Object.class) != null;
    }

    @Override
    public String getProperty(String key) {
        return getProperty(key, String.class);
    }

    @Override
    public String getProperty(String key, String defaultValue) {
        return getProperty(key, String.class, defaultValue);
    }

    @Override
    public <T> T getProperty(String key, Class<T> targetType) {
        return getProperty(key, targetType, null);
    }

    @Override
    public <T> T getProperty(String key, Class<T> targetType, T defaultValue, List<T> allowedValues) {
        T value = getProperty(key, targetType, defaultValue);
        if (!allowedValues.contains(value)) {
            throw new GrailsConfigurationException("Invalid configuration value [$value] for key [${key}]. Possible values $allowedValues");
        }
        return value;
    }

    @Override
    public <T> T getProperty(String key, Class<T> targetType, T defaultValue) {
        Object value = findInSystemEnvironment(key);
        if (value == null) {
            value = getValueWithDotNotatedKeySupport(configMap, key);
        }
        if (value == null) {
            value = configMap.get(key);
        }

        return convertValueIfNecessary(value, targetType, defaultValue);
    }

    private Object findInSystemEnvironment(String key) {
        String propertyName = resolvePropertyName(key);
        return propertyName != null ? System.getenv(propertyName) : null;
    }

    private String resolvePropertyName(String name) {
        String resolvedName = checkPropertyName(name);
        if (resolvedName != null) {
            return resolvedName;
        }
        String uppercasedName = name.toUpperCase();
        if (!name.equals(uppercasedName)) {
            resolvedName = checkPropertyName(uppercasedName);
            if (resolvedName != null) {
                return resolvedName;
            }
        }
        return name;
    }

    private String checkPropertyName(String name) {
        // Check name as-is
        if (containsKey(name)) {
            return name;
        }
        // Check name with just dots replaced
        String noDotName = name.replace('.', '_');
        if (!name.equals(noDotName) && containsKey(noDotName)) {
            return noDotName;
        }
        // Check name with just hyphens replaced
        String noHyphenName = name.replace('-', '_');
        if (!name.equals(noHyphenName) && containsKey(noHyphenName)) {
            return noHyphenName;
        }
        // Check name with dots and hyphens replaced
        String noDotNoHyphenName = noDotName.replace('-', '_');
        if (!noDotName.equals(noDotNoHyphenName) && containsKey(noDotNoHyphenName)) {
            return noDotNoHyphenName;
        }
        // Give up
        return null;
    }

    private boolean containsKey(String name) {
        return System.getenv(name) != null;
    }

    private Map<Object, Object> convertToMap(NavigableMap from, IdentityHashMap<NavigableMap, Map<Object, Object>> cache) {
        if (cache.containsKey(from)) {
            return cache.get(from);
        }
        final Map<Object, Object> to = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry: from.entrySet()) {
            if (entry.getValue() instanceof NavigableMap) {
                to.put(entry.getKey(), convertToMap((NavigableMap) entry.getValue(), cache));
            } else if (entry.getValue() instanceof List) {
                List<Object> newList = new ArrayList<>();
                for (Object o: (List<?>) entry.getValue()) {
                    if (o instanceof NavigableMap) {
                        newList.add(convertToMap((NavigableMap) o, cache));
                    } else {
                        newList.add(o);
                    }
                }
                to.put(entry.getKey(), newList);
            } else {
                to.put(entry.getKey(), entry.getValue());
            }
        }
        cache.put(from, to);
        return to;
    }

    private ConfigObject convertPropsToMap(ConfigObject config) {
        for (Map.Entry<String, Object> entry: (Set<Map.Entry<String, Object>>) config.entrySet()) {
            final IdentityHashMap<NavigableMap, Map<Object, Object>> cache = new IdentityHashMap<>();
            if (entry.getValue() instanceof NavigableMap) {
                config.setProperty(entry.getKey(), convertToMap((NavigableMap) entry.getValue(), cache));
            } else if (entry.getValue() instanceof List) {
                final List<Object> newList = new ArrayList<>();
                for (Object o: (List<?>) entry.getValue()) {
                    if (o instanceof NavigableMap) {
                        newList.add(convertToMap((NavigableMap) o, cache));
                    } else {
                        newList.add(o);
                    }
                }
                config.setProperty(entry.getKey(), newList);
            }
        }
        return config;
    }

    private <T> T convertValueIfNecessary(Object originalValue, Class<T> targetType, T defaultValue) {
        if (originalValue != null) {
            if (targetType.isInstance(originalValue)) {
                if (originalValue instanceof NavigableMap && targetType.equals(Map.class)) {
                    final IdentityHashMap<NavigableMap, Map<Object, Object>> cache = new IdentityHashMap<>();
                    return (T) convertToMap((NavigableMap) originalValue, cache);
                } else {
                    return (T) originalValue;
                }
            } else {
                if (!(originalValue instanceof NavigableMap) || Map.class.isAssignableFrom(targetType)) {
                    try {
                        T value = conversionService.convert(originalValue, targetType);
                        if (value instanceof ConfigObject) {
                            convertPropsToMap((ConfigObject) value);
                        }
                        return DefaultGroovyMethods.asBoolean(value) ? value : defaultValue;
                    } catch (ConversionException e) {
                        if (targetType.isEnum()) {
                            String stringValue = originalValue.toString();
                            try {
                                T value = (T) toEnumValue(targetType, stringValue);
                                return value;
                            } catch (Throwable e2) {
                                // ignore e2 and throw original
                            }
                        }
                    }
                }
            }
        }
        return defaultValue;
    }

    private Object toEnumValue(Class targetType, String stringValue) {
        return Enum.valueOf(targetType, stringValue.toUpperCase());
    }

    @Override
    public String getRequiredProperty(String key) throws IllegalStateException {
        String value = getProperty(key);
        if (GrailsStringUtils.isBlank(value)) {
            throw new IllegalStateException("Value for key [" + key + "] cannot be resolved");
        }
        return value;
    }

    @Override
    public <T> T getRequiredProperty(String key, Class<T> targetType) throws IllegalStateException {
        T value = getProperty(key, targetType);
        if (value == null) {
            throw new IllegalStateException("Value for key [" + key + "] cannot be resolved");
        }
        return value;
    }

    public static class ClassConversionException extends ConversionException {

        public ClassConversionException(Class<?> actual, Class<?> expected) {
            super(String.format("Actual type %s is not assignable to expected type %s", actual.getName(), expected.getName()));
        }

        public ClassConversionException(String actual, Class<?> expected, Exception ex) {
            super(String.format("Could not find/load class %s during attempt to convert to %s", actual, expected.getName()), ex);
        }
    }

    /**
     * Resolves dot notated getProperty calls on a config object so that injected environmental variables
     * are properly resolved the same as Groovy's dot notation.
     *
     * @param configMap NavigableMap
     * @param key       identifies NavigableMap value to retrieve
     * @return the property value associated with the key
     */
    private Object getValueWithDotNotatedKeySupport(NavigableMap configMap, String key) {
        if (key == null || configMap == null) {
            return null;
        }

        List<String> keys = convertTokensIntoArrayList(new StringTokenizer(key, "."));
        if (keys.size() == 0) {
            return null;
        }

        Object value = null;
        for (int i = 0; i < keys.size(); i++) {
            if (i == 0) {
                value = configMap.get(keys.get(i));
            } else if (value instanceof Map) {
                value = ((Map) value).get(keys.get(i));
            }
        }
        return value;
    }

    private List<String> convertTokensIntoArrayList(StringTokenizer st) {
        List<String> elements = new ArrayList<>();
        while (st.hasMoreTokens()) {
            elements.add(st.nextToken());
        }
        return elements;
    }
}

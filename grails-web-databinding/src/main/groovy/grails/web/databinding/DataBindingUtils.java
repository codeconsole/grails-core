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
package grails.web.databinding;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import groovy.lang.GroovySystem;
import groovy.lang.MetaClass;
import groovy.lang.MetaProperty;

import jakarta.servlet.ServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

import grails.config.Settings;
import grails.core.GrailsApplication;
import grails.databinding.CollectionDataBindingSource;
import grails.databinding.DataBinder;
import grails.databinding.DataBindingSource;
import grails.databinding.SimpleDataBinder;
import grails.util.Environment;
import grails.util.Holders;
import grails.validation.ValidationErrors;
import grails.web.mime.MimeType;
import grails.web.mime.MimeTypeResolver;
import grails.web.mime.MimeTypeUtils;
import org.grails.config.NavigableMap;
import org.grails.core.exceptions.GrailsConfigurationException;
import org.grails.datastore.mapping.model.PersistentEntity;
import org.grails.datastore.mapping.model.PersistentProperty;
import org.grails.datastore.mapping.model.types.OneToOne;
import org.grails.web.databinding.DefaultASTDatabindingHelper;
import org.grails.web.databinding.bindingsource.DataBindingSourceRegistry;
import org.grails.web.databinding.bindingsource.DefaultDataBindingSourceRegistry;
import org.grails.web.databinding.bindingsource.InvalidRequestBodyException;

/**
 * Utility methods to perform data binding from Grails objects.
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@SuppressWarnings("rawtypes")
public class DataBindingUtils {

    private static final Logger LOG = LoggerFactory.getLogger(DataBindingUtils.class);
    public static final String DATA_BINDER_BEAN_NAME = "grailsWebDataBinder";
    private static final String BLANK = "";
    private static final List NO_BINDING_INCLUDE_LIST = new NoBindingIncludeList();
    private static final Map<Class, List> CLASS_TO_BINDING_INCLUDE_LIST = new ConcurrentHashMap<>();
    private static final Map<Class, List> CLASS_TO_LEGACY_BINDING_INCLUDE_LIST = new ConcurrentHashMap<>();
    private static final Set<String> FRAMEWORK_MANAGED_PROPERTIES = Set.of(
            "class", "classLoader", "protectionDomain", "metaClass", "metaPropertyValues", "properties",
            "errors", "id", "version", "dateCreated", "lastUpdated");
    private static final Map<Class, List> CLASS_TO_UNBINDABLE_PROPERTY_NAMES = new ConcurrentHashMap<>();

    private static final class NoBindingIncludeList extends ArrayList {
    }

    /**
     * Associations both sides of any bidirectional relationships found in the object and source map to bind
     *
     * @param object The object
     * @param source The source map
     * @param persistentEntity The PersistentEntity for the object
     */
    public static void assignBidirectionalAssociations(Object object, Map source, PersistentEntity persistentEntity) {
        if (source == null) {
            return;
        }

        for (Object key : source.keySet()) {
            String propertyName = key.toString();
            if (propertyName.indexOf('.') > -1) {
                propertyName = propertyName.substring(0, propertyName.indexOf('.'));
            }
            PersistentProperty prop = persistentEntity.getPropertyByName(propertyName);

            if (prop != null && prop instanceof OneToOne && ((OneToOne) prop).isBidirectional()) {
                Object val = source.get(key);
                PersistentProperty otherSide = ((OneToOne) prop).getInverseSide();
                if (val != null && otherSide != null) {
                    MetaClass mc = GroovySystem.getMetaClassRegistry().getMetaClass(val.getClass());
                    try {
                        mc.setProperty(val, otherSide.getName(), object);
                    }
                    catch (Exception e) {
                        // ignore
                    }
                }
            }

        }
    }

    /**
     * Binds the given source object to the given target object performing type conversion if necessary
     *
     * @param object The object to bind to
     * @param source The source object
     * @return A BindingResult if there were errors or null if it was successful
     */
    public static BindingResult bindObjectToInstance(Object object, Object source) {
        return bindObjectToInstance(object, source, getBindingIncludeList(object), Collections.emptyList(), null);
    }

    protected static List getBindingIncludeList(final Object object) {
        final boolean legacyBindableDefaultEnabled = isLegacyBindableDefaultEnabled();
        final Map<Class, List> includeListCache = legacyBindableDefaultEnabled ?
                CLASS_TO_LEGACY_BINDING_INCLUDE_LIST : CLASS_TO_BINDING_INCLUDE_LIST;
        List includeList = null;
        try {
            final Class<? extends Object> objectClass = object.getClass();
            if (includeListCache.containsKey(objectClass)) {
                includeList = includeListCache.get(objectClass);
                if (includeList == NO_BINDING_INCLUDE_LIST) {
                    includeList = null;
                }
            } else {
                // Resolve the runtime-derived bindable names only on a cache miss - this walks the
                // target's constraints/metaclass and would otherwise run on every bind of a cached class.
                final List runtimeBindableNames = legacyBindableDefaultEnabled ? null : getBindablePropertyNames(object);
                includeList = runtimeBindableNames;
                final Field legacyWhiteListField = getField(objectClass, DefaultASTDatabindingHelper.LEGACY_DATABINDING_WHITELIST);
                final Field defaultWhiteListField = legacyBindableDefaultEnabled ?
                        getField(objectClass, DefaultASTDatabindingHelper.DEFAULT_DATABINDING_WHITELIST) :
                        getPairedField(objectClass, DefaultASTDatabindingHelper.DEFAULT_DATABINDING_WHITELIST,
                                DefaultASTDatabindingHelper.LEGACY_DATABINDING_WHITELIST);
                if (legacyBindableDefaultEnabled) {
                    includeList = getStaticListFieldValue(legacyWhiteListField);
                    if (includeList == null) {
                        includeList = getStaticListFieldValue(defaultWhiteListField);
                    }
                } else if (defaultWhiteListField != null) {
                    final List generatedIncludeList = getStaticListFieldValue(defaultWhiteListField);
                    final Collection combinedIncludeList = new LinkedHashSet();
                    if (generatedIncludeList != null) {
                        combinedIncludeList.addAll(generatedIncludeList);
                    }
                    if (runtimeBindableNames != null) {
                        combinedIncludeList.addAll(runtimeBindableNames);
                    }
                    includeList = new ArrayList(combinedIncludeList);
                }
                if (!legacyBindableDefaultEnabled) {
                    includeList = asGeneratedBindingIncludeList(includeList);
                }
                if (!Environment.getCurrent().isReloadEnabled()) {
                    includeListCache.put(objectClass, includeList == null ? NO_BINDING_INCLUDE_LIST : includeList);
                }
            }
        } catch (Exception e) {
        }
        if (!legacyBindableDefaultEnabled) {
            includeList = asGeneratedBindingIncludeList(includeList);
        }
        return includeList;
    }

    static List asGeneratedBindingIncludeList(final List includeList) {
        if (includeList instanceof GeneratedBindingIncludeList) {
            return includeList;
        }
        final Collection values = includeList == null || includeList.isEmpty() ?
                Collections.singletonList(DefaultASTDatabindingHelper.NO_BINDABLE_PROPERTIES) : includeList;
        return new GeneratedBindingIncludeList(values);
    }

    static boolean isGeneratedBindingIncludeList(final List includeList) {
        return includeList instanceof GeneratedBindingIncludeList;
    }

    private static final class GeneratedBindingIncludeList extends ArrayList {
        private GeneratedBindingIncludeList(final Collection values) {
            super(values);
        }
    }

    private static Field getField(final Class objectClass, final String fieldName) {
        Class currentClass = objectClass;
        while (currentClass != null) {
            final Field field = getPublicDeclaredField(currentClass, fieldName);
            if (field != null) {
                return field;
            }
            currentClass = currentClass.getSuperclass();
        }
        return null;
    }

    private static Field getPairedField(final Class objectClass, final String fieldName, final String pairedFieldName) {
        Class currentClass = objectClass;
        while (currentClass != null) {
            final Field field = getPublicDeclaredField(currentClass, fieldName);
            final Field pairedField = getPublicDeclaredField(currentClass, pairedFieldName);
            if (field != null && pairedField != null) {
                return field;
            }
            currentClass = currentClass.getSuperclass();
        }
        return null;
    }

    private static Field getPublicDeclaredField(final Class objectClass, final String fieldName) {
        try {
            final Field field = objectClass.getDeclaredField(fieldName);
            return Modifier.isPublic(field.getModifiers()) ? field : null;
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }

    private static List getStaticListFieldValue(final Field field) throws IllegalAccessException {
        if (field != null && (field.getModifiers() & Modifier.STATIC) != 0) {
            final Object value = field.get(null);
            if (value instanceof List) {
                return (List) value;
            }
        }
        return null;
    }

    static List getBindablePropertyNames(final Object object) {
        return getPropertyNamesWithBindableValue(object, Boolean.TRUE);
    }

    static List getUnbindablePropertyNames(final Object object) {
        // Instance-derived constraints (constraintsMap / getConstraintsMap / constraints)
        // may differ between instances of the same class, so do not cache by Class here.
        return getPropertyNamesWithBindableValue(object, Boolean.FALSE);
    }

    static List getUnbindablePropertyNames(final Class objectClass) {
        if (!Environment.getCurrent().isReloadEnabled()) {
            return CLASS_TO_UNBINDABLE_PROPERTY_NAMES.computeIfAbsent(
                    objectClass, ignored -> getPropertyNamesWithBindableValue(evaluateConstrainedProperties(objectClass), Boolean.FALSE));
        }
        return getPropertyNamesWithBindableValue(evaluateConstrainedProperties(objectClass), Boolean.FALSE);
    }

    static List getPropertyNamesWithBindableValue(final Object object, final Boolean bindableValue) {
        return getPropertyNamesWithBindableValue(getConstrainedProperties(object), bindableValue);
    }

    private static List getPropertyNamesWithBindableValue(final Map constrainedProperties, final Boolean bindableValue) {
        if (constrainedProperties == null || constrainedProperties.isEmpty()) {
            return Collections.emptyList();
        }
        final List propertyNames = new ArrayList();
        for (Object entryObject : constrainedProperties.entrySet()) {
            Map.Entry entry = (Map.Entry) entryObject;
            if (bindableValue.equals(getBindableConstraintValue(entry.getValue()))) {
                String propertyName = String.valueOf(entry.getKey());
                propertyNames.add(propertyName);
                if (Boolean.TRUE.equals(bindableValue) && !isSimpleType(getConstrainedPropertyType(entry.getValue()))) {
                    propertyNames.add(propertyName + "_*");
                    propertyNames.add(propertyName + ".*");
                }
            }
        }
        return propertyNames;
    }

    private static Class getConstrainedPropertyType(final Object constrainedProperty) {
        MetaClass metaClass = GroovySystem.getMetaClassRegistry().getMetaClass(constrainedProperty.getClass());
        try {
            Object propertyType = metaClass.invokeMethod(constrainedProperty, "getPropertyType", new Object[0]);
            if (propertyType instanceof Class) {
                return (Class) propertyType;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isSimpleType(final Class propertyType) {
        return propertyType != null && (propertyType.isPrimitive() || String.class.equals(propertyType) ||
                Boolean.class.equals(propertyType) || Character.class.equals(propertyType) || Number.class.isAssignableFrom(propertyType) ||
                BigInteger.class.equals(propertyType) || BigDecimal.class.equals(propertyType) || URL.class.equals(propertyType));
    }

    static Map getConstrainedProperties(final Object object) {
        MetaClass metaClass = GroovySystem.getMetaClassRegistry().getMetaClass(object.getClass());
        try {
            Object constrainedProperties = metaClass.getProperty(object, "constraintsMap");
            if (constrainedProperties instanceof Map) {
                return (Map) constrainedProperties;
            }
        } catch (Exception ignored) {
        }
        try {
            Object constrainedProperties = metaClass.invokeMethod(object, "getConstraintsMap", new Object[0]);
            if (constrainedProperties instanceof Map) {
                return (Map) constrainedProperties;
            }
        } catch (Exception ignored) {
        }
        try {
            Object constrainedProperties = metaClass.getProperty(object, "constraints");
            if (constrainedProperties instanceof Map) {
                return (Map) constrainedProperties;
            }
        } catch (Exception ignored) {
        }
        try {
            Map constrainedProperties = evaluateConstrainedProperties(object.getClass());
            if (constrainedProperties != null) {
                return constrainedProperties;
            }
        } catch (Exception ignored) {
        }
        return Collections.emptyMap();
    }

    private static Map evaluateConstrainedProperties(final Class objectClass) {
        try {
            Class<?> validationSupport = Class.forName("org.grails.web.plugins.support.ValidationSupport");
            Object constrainedProperties = validationSupport.getMethod("getConstrainedPropertiesForClass", Class.class, boolean.class).invoke(null, objectClass, false);
            if (constrainedProperties instanceof Map) {
                return (Map) constrainedProperties;
            }
        } catch (Exception ignored) {
        }
        return Collections.emptyMap();
    }

    static Object getBindableConstraintValue(final Object constrainedProperty) {
        MetaClass metaClass = GroovySystem.getMetaClassRegistry().getMetaClass(constrainedProperty.getClass());
        try {
            Object value = metaClass.invokeMethod(constrainedProperty, "getMetaConstraintValue", new Object[] { DefaultASTDatabindingHelper.BINDABLE_CONSTRAINT_NAME });
            if (value != null) {
                return value;
            }
        } catch (Exception ignored) {
        }
        try {
            Object metaConstraints = metaClass.getProperty(constrainedProperty, "metaConstraints");
            if (metaConstraints instanceof Map) {
                return ((Map) metaConstraints).get(DefaultASTDatabindingHelper.BINDABLE_CONSTRAINT_NAME);
            }
        } catch (Exception ignored) {
        }
        try {
            Object delegate = metaClass.getProperty(constrainedProperty, "property");
            if (delegate != null && delegate != constrainedProperty) {
                return getBindableConstraintValue(delegate);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static List addUnbindablePropertyNames(final Object object, final List exclude) {
        final List unbindablePropertyNames = getUnbindablePropertyNames(object);
        if (unbindablePropertyNames.isEmpty()) {
            return exclude;
        }
        if (exclude == null || exclude.isEmpty()) {
            return unbindablePropertyNames;
        }
        final List combinedExcludes = new ArrayList(exclude);
        combinedExcludes.addAll(unbindablePropertyNames);
        return combinedExcludes;
    }

    static boolean isLegacyBindableDefaultEnabled() {
        GrailsApplication application = Holders.findApplication();
        if (application != null) {
            return resolveLegacyBindableDefault(
                    application.getConfig().getProperty(Settings.LEGACY_BINDABLE_DEFAULT, Object.class, null));
        }
        return resolveLegacyBindableDefault(Holders.getFlatConfig().get(Settings.LEGACY_BINDABLE_DEFAULT));
    }

    /**
     * Resolves the configured value of {@code grails.databinding.legacyBindableDefault} against the
     * permissive default.
     * <p>
     * The raw value must be resolved here rather than through a typed {@code Boolean} config lookup:
     * a config value that converts to {@code Boolean.FALSE} is discarded in favour of the supplied
     * default, which would silently ignore an explicit opt-in to the secure deny-by-default mode from
     * any string-valued source such as a properties file, a system property or an environment variable.
     * <p>
     * A navigable config answers an absent key with a placeholder object rather than {@code null}, so
     * only a genuinely absent key may fall back to the permissive default. Any other unrecognised value
     * fails closed, because this switch governs mass-assignment protection.
     *
     * @param value the raw configured value, which may be {@code null} or an absent-key placeholder
     * @return true when the legacy (permissive) binding default applies
     */
    static boolean resolveLegacyBindableDefault(final Object value) {
        if (value == null || value instanceof NavigableMap.NullSafeNavigator) {
            return true;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof CharSequence) {
            final String configuredValue = value.toString().trim();
            if ("true".equalsIgnoreCase(configuredValue)) {
                return true;
            }
            if ("false".equalsIgnoreCase(configuredValue)) {
                return false;
            }
        }
        LOG.warn("Unrecognised value [{}] for configuration property [{}]; secure data binding will be enabled.",
                value, Settings.LEGACY_BINDABLE_DEFAULT);
        return false;
    }

    static void clearBindingCaches() {
        CLASS_TO_BINDING_INCLUDE_LIST.clear();
        CLASS_TO_LEGACY_BINDING_INCLUDE_LIST.clear();
        CLASS_TO_UNBINDABLE_PROPERTY_NAMES.clear();
    }

    /**
     * Binds the given source object to the given target object performing type conversion if necessary
     *
     * @param entity The PersistentEntity instance
     * @param object The object to bind to
     * @param source The source object
     *
     * @see org.grails.datastore.mapping.model.PersistentEntity
     *
     * @return A BindingResult if there were errors or null if it was successful
     */
    public static BindingResult bindObjectToDomainInstance(PersistentEntity entity, Object object, Object source) {
        return bindObjectToDomainInstance(entity, object, source, getBindingIncludeList(object), Collections.emptyList(), null);
    }

    /**
     * For each DataBindingSource provided by collectionBindingSource a new instance of targetType is created,
     * data binding is imposed on that instance with the DataBindingSource and the instance is added to the end of
     * collectionToPopulate
     *
     * @param targetType The type of objects to create, must be a concrete class
     * @param collectionToPopulate A collection to populate with new instances of targetType
     * @param collectionBindingSource A CollectionDataBindingSource
     * @since 2.3
     */
    public static <T> void bindToCollection(final Class<T> targetType, final Collection<T> collectionToPopulate, final CollectionDataBindingSource collectionBindingSource) throws InstantiationException, IllegalAccessException {
        final GrailsApplication application = Holders.findApplication();
        PersistentEntity entity = null;
        if (application != null) {
            try {
                entity = application.getMappingContext().getPersistentEntity(targetType.getName());
            } catch (GrailsConfigurationException e) {
                //no-op
            }
        }
        final List<DataBindingSource> dataBindingSources = collectionBindingSource.getDataBindingSources();
        for (final DataBindingSource dataBindingSource : dataBindingSources) {
            final T newObject;
            try {
                newObject = targetType.getDeclaredConstructor().newInstance();
            } catch (NoSuchMethodException | InvocationTargetException ex) {
                throw new InstantiationException(
                    "Could not instantiate class [" + targetType.getName() + "]: " + ex.getMessage()
                );
            }

            bindObjectToDomainInstance(entity, newObject, dataBindingSource, getBindingIncludeList(newObject), Collections.emptyList(), null);
            collectionToPopulate.add(newObject);
        }
    }

    public static <T> void bindToCollection(final Class<T> targetType, final Collection<T> collectionToPopulate, final ServletRequest request) throws InstantiationException, IllegalAccessException {
        final GrailsApplication grailsApplication = Holders.findApplication();
        final CollectionDataBindingSource collectionDataBindingSource = createCollectionDataBindingSource(grailsApplication, targetType, request);
        bindToCollection(targetType, collectionToPopulate, collectionDataBindingSource);
    }

    /**
     * Binds the given source object to the given target object performing type conversion if necessary
     *
     * @param object The object to bind to
     * @param source The source object
     * @param include The list of properties to include
     * @param exclude The list of properties to exclude
     * @param filter The prefix to filter by
     *
     * @return A BindingResult if there were errors or null if it was successful
     */
    public static BindingResult bindObjectToInstance(Object object, Object source, List include, List exclude, String filter) {
        return bindObjectToInstance(object, source, include, exclude, filter, false);
    }

    public static BindingResult bindObjectToInstance(Object object, Object source, List include, List exclude, String filter, boolean nullMissing) {
        boolean explicitInclude = include != null;
        if (include == null) {
            if (exclude == null || !isLegacyBindableDefaultEnabled()) {
                include = getBindingIncludeList(object);
            } else {
                // Exclude-only in compatibility mode must not intersect the class allowlist.
                include = SimpleDataBinder.getBindAllBindingIncludeList();
            }
        }
        else if (include.isEmpty() && !SimpleDataBinder.isBindAllBindingIncludeList(include)) {
            include = Collections.singletonList(DefaultASTDatabindingHelper.NO_BINDABLE_PROPERTIES);
        }
        GrailsApplication application = Holders.findApplication();
        PersistentEntity entity = null;
        if (application != null) {
            try {
                entity = application.getMappingContext().getPersistentEntity(object.getClass().getName());
            } catch (GrailsConfigurationException e) {
                //no-op
            }
        }
        return bindObjectToDomainInstance(entity, object, source, include, exclude, filter, nullMissing && explicitInclude);
    }

    /**
     * Binds the given source object to the given target object performing type conversion if necessary
     *
     * @param entity The PersistentEntity instance
     * @param object The object to bind to
     * @param source The source object
     * @param include The list of properties to include
     * @param exclude The list of properties to exclude
     * @param filter The prefix to filter by
     *
     * @see org.grails.datastore.mapping.model.PersistentEntity
     *
     * @return A BindingResult if there were errors or null if it was successful
     */
    @SuppressWarnings("unchecked")
    public static BindingResult bindObjectToDomainInstance(PersistentEntity entity, Object object,
                                                           Object source, List include, List exclude, String filter) {
        if (include == null) {
            if (exclude == null || !isLegacyBindableDefaultEnabled()) {
                include = getBindingIncludeList(object);
            } else {
                include = SimpleDataBinder.getBindAllBindingIncludeList();
            }
        }
        else if (include.isEmpty() && !SimpleDataBinder.isBindAllBindingIncludeList(include)) {
            include = Collections.singletonList(DefaultASTDatabindingHelper.NO_BINDABLE_PROPERTIES);
        }
        return bindObjectToDomainInstance(entity, object, source, include, exclude, filter, false);
    }

    @SuppressWarnings("unchecked")
    public static BindingResult bindObjectToDomainInstance(PersistentEntity entity, Object object,
                                                           Object source, List include, List exclude, String filter, boolean nullMissing) {
        boolean explicitInclude = include != null;
        if (include == null) {
            include = getBindingIncludeList(object);
        }
        else if (include.isEmpty()) {
            include = Collections.singletonList(DefaultASTDatabindingHelper.NO_BINDABLE_PROPERTIES);
        }
        BindingResult bindingResult = null;
        GrailsApplication grailsApplication = Holders.findApplication();

        try {
            final DataBindingSource bindingSource = createDataBindingSource(grailsApplication, object.getClass(), source);
            final DataBinder grailsWebDataBinder = getGrailsWebDataBinder(grailsApplication);
            grailsWebDataBinder.bind(object, bindingSource, filter, include, exclude);
            if (nullMissing && explicitInclude && !include.isEmpty()) {
                assignNullToMissingIncludedProperties(object, bindingSource, include, exclude, filter);
            }
        } catch (InvalidRequestBodyException e) {
            String messageCode = "invalidRequestBody";
            Class objectType = object.getClass();
            String defaultMessage = "An error occurred parsing the body of the request";
            String[] codes = getMessageCodes(messageCode, objectType);
            bindingResult = new BeanPropertyBindingResult(object, objectType.getName());
            bindingResult.addError(new ObjectError(bindingResult.getObjectName(), codes, null, defaultMessage));
        } catch (Exception e) {
            bindingResult = new BeanPropertyBindingResult(object, object.getClass().getName());
            bindingResult.addError(new ObjectError(bindingResult.getObjectName(), e.getMessage()));
        }

        if (entity != null && bindingResult != null) {
            BindingResult newResult = new ValidationErrors(object);
            for (Object error : bindingResult.getAllErrors()) {
                if (error instanceof FieldError) {
                    FieldError fieldError = (FieldError) error;
                    final boolean isBlank = BLANK.equals(fieldError.getRejectedValue());
                    if (!isBlank) {
                        newResult.addError(fieldError);
                    }
                    else {
                        PersistentProperty property = entity.getPropertyByName(fieldError.getField());
                        if (property != null) {
                            final boolean isOptional = property.isNullable();
                            if (!isOptional) {
                                newResult.addError(fieldError);
                            }
                        }
                        else {
                            newResult.addError(fieldError);
                        }
                    }
                }
                else {
                    newResult.addError((ObjectError) error);
                }
            }
            bindingResult = newResult;
        }
        MetaClass mc = GroovySystem.getMetaClassRegistry().getMetaClass(object.getClass());
        if (mc.hasProperty(object, "errors") != null && bindingResult != null) {
            ValidationErrors errors = new ValidationErrors(object);
            errors.addAllErrors(bindingResult);
            mc.setProperty(object, "errors", errors);
        }
        return bindingResult;
    }

    private static void assignNullToMissingIncludedProperties(Object object, DataBindingSource bindingSource, List include, List exclude, String filter) {
        for (Object includedProperty : include) {
            if (includedProperty instanceof CharSequence) {
                String propertyName = includedProperty.toString();
                if (propertyName.indexOf('*') == -1 && isNullMissingPropertyBindable(object, propertyName, include, exclude)) {
                    if (assignNullToMissingIndexedProperties(object, bindingSource, propertyName, filter)) {
                        continue;
                    }
                    if (!bindingSourceContainsProperty(bindingSource, propertyName, filter)) {
                        setPropertyToNull(object, propertyName);
                    }
                }
            }
        }
    }

    private static boolean isNullMissingPropertyBindable(Object object, String propertyName, List include, List exclude) {
        if (object == null) {
            return false;
        }
        String allowlistPropertyName = removePropertyIndexes(propertyName);
        List bindingIncludeList = getBindingIncludeList(object);
        List bindingExcludeList = normalizePropertyIndexes(addUnbindablePropertyNames(object, exclude));
        if (!isNullMissingPropertyPathAllowed(allowlistPropertyName, bindingIncludeList, include, bindingExcludeList)) {
            return false;
        }
        int separator = propertyPathSeparator(propertyName);
        if (separator == -1) {
            return true;
        }

        Object nestedObject = getPropertyValue(object, propertyName.substring(0, separator));
        String nestedPropertyName = propertyName.substring(separator + 1);
        if (nestedObject instanceof Collection) {
            for (Object item : (Collection) nestedObject) {
                if (item != null && !isNullMissingPropertyBindable(item, nestedPropertyName, getNestedIncludeList(include, propertyName), null)) {
                    return false;
                }
            }
            return true;
        }
        if (nestedObject instanceof Map) {
            for (Object value : ((Map) nestedObject).values()) {
                if (value != null && !isNullMissingPropertyBindable(value, nestedPropertyName, getNestedIncludeList(include, propertyName), null)) {
                    return false;
                }
            }
            return true;
        }
        return nestedObject == null || isNullMissingPropertyBindable(nestedObject, nestedPropertyName, getNestedIncludeList(include, propertyName), null);
    }

    private static String removePropertyIndexes(String propertyName) {
        return propertyName.replaceAll("\\[[^]]*]", "");
    }

    private static List normalizePropertyIndexes(List propertyNames) {
        if (propertyNames == null) {
            return Collections.emptyList();
        }
        List normalizedPropertyNames = new ArrayList(propertyNames.size());
        for (Object propertyName : propertyNames) {
            normalizedPropertyNames.add(propertyName instanceof CharSequence ? removePropertyIndexes(propertyName.toString()) : propertyName);
        }
        return normalizedPropertyNames;
    }

    private static List getNestedIncludeList(List include, String propertyName) {
        if (include == null || include.isEmpty()) {
            return Collections.emptyList();
        }
        String normalizedPropertyName = removePropertyIndexes(propertyName);
        int separator = propertyPathSeparator(normalizedPropertyName);
        if (separator == -1) {
            return Collections.emptyList();
        }
        String rootPropertyName = normalizedPropertyName.substring(0, separator);
        List nestedIncludeList = new ArrayList();
        for (Object includedProperty : include) {
            if (includedProperty instanceof CharSequence) {
                String includedPropertyName = removePropertyIndexes(includedProperty.toString());
                int includedPropertySeparator = propertyPathSeparator(includedPropertyName);
                if (includedPropertySeparator != -1 && rootPropertyName.equals(includedPropertyName.substring(0, includedPropertySeparator))) {
                    nestedIncludeList.add(includedPropertyName.substring(includedPropertySeparator + 1));
                }
            }
        }
        return nestedIncludeList;
    }

    private static boolean isNullMissingPropertyPathAllowed(String propertyName, List generatedIncludeList, List explicitIncludeList, List excludeList) {
        if (isFrameworkManagedProperty(propertyName) || SimpleDataBinder.isPropertyExcluded(propertyName, excludeList)) {
            return false;
        }
        return isNullMissingPropertyIncluded(propertyName, generatedIncludeList) ||
                isNullMissingPropertyIncluded(propertyName, explicitIncludeList);
    }

    private static boolean isFrameworkManagedProperty(String propertyName) {
        int separator = propertyPathSeparator(propertyName);
        String rootPropertyName = separator == -1 ? propertyName : propertyName.substring(0, separator);
        return FRAMEWORK_MANAGED_PROPERTIES.contains(rootPropertyName);
    }

    private static boolean isNullMissingPropertyIncluded(String propertyName, List includeList) {
        if (includeList == null) {
            return false;
        }
        for (Object includedProperty : includeList) {
            if (includedProperty instanceof CharSequence) {
                String includedPropertyName = removePropertyIndexes(includedProperty.toString());
                if (includedPropertyName.equals(propertyName)) {
                    return true;
                }
                if (includedPropertyName.endsWith(".*")) {
                    String prefix = includedPropertyName.substring(0, includedPropertyName.length() - 2);
                    if (propertyName.startsWith(prefix + ".")) {
                        return true;
                    }
                }
                if (includedPropertyName.endsWith("_*")) {
                    String prefix = includedPropertyName.substring(0, includedPropertyName.length() - 2);
                    if (propertyName.startsWith(prefix + ".") || propertyName.startsWith(prefix + "_")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean assignNullToMissingIndexedProperties(Object object, DataBindingSource bindingSource, String propertyName, String filter) {
        String sourcePropertyName = filter == null ? propertyName : filter + "." + propertyName;
        return assignNullToMissingIndexedProperties(object, bindingSource, BLANK, sourcePropertyName, propertyName);
    }

    private static boolean assignNullToMissingIndexedProperties(Object object, Object source, String targetPathPrefix, String sourcePropertyName, String targetPropertyName) {
        int sourceSeparator = propertyPathSeparator(sourcePropertyName);
        int targetSeparator = propertyPathSeparator(targetPropertyName);
        if (sourceSeparator == -1 || targetSeparator == -1) {
            return false;
        }

        String sourceRootPropertyName = sourcePropertyName.substring(0, sourceSeparator);
        String targetRootPropertyName = targetPropertyName.substring(0, targetSeparator);
        String nestedSourcePropertyName = sourcePropertyName.substring(sourceSeparator + 1);
        String nestedTargetPropertyName = targetPropertyName.substring(targetSeparator + 1);
        String[] sourceSegments = splitPropertyPath(sourcePropertyName);
        String[] targetSegments = splitPropertyPath(targetPropertyName);
        if (sourceSegments.length > targetSegments.length) {
            if (containsSourceProperty(source, sourceRootPropertyName)) {
                return assignNullToMissingIndexedProperties(object, getSourcePropertyValue(source, sourceRootPropertyName), targetPathPrefix, nestedSourcePropertyName, targetPropertyName);
            }
            int sourceRootSegmentCount = sourceSegments.length - targetSegments.length + 1;
            sourceRootPropertyName = joinPropertyPath(sourceSegments, 0, sourceRootSegmentCount);
            nestedSourcePropertyName = joinPropertyPath(sourceSegments, sourceRootSegmentCount, sourceSegments.length);
            targetRootPropertyName = targetSegments[0];
            nestedTargetPropertyName = joinPropertyPath(targetSegments, 1, targetSegments.length);
        }

        if (containsSourceProperty(source, sourceRootPropertyName)) {
            Object nestedSource = getSourcePropertyValue(source, sourceRootPropertyName);
            if (nestedSource instanceof Collection) {
                return assignNullToMissingCollectionProperties(object, (Collection) nestedSource, targetPathPrefix, targetRootPropertyName, nestedSourcePropertyName, nestedTargetPropertyName);
            }
            Object targetObject = getTargetObject(object, targetPathPrefix);
            if (nestedSource instanceof Map && hasNestedSourceEntries((Map) nestedSource) && shouldExpandMapEntries(targetObject, targetObject == null ? null : targetObject.getClass(), targetRootPropertyName)) {
                return assignNullToMissingMapProperties(object, (Map) nestedSource, targetPathPrefix, targetRootPropertyName, nestedSourcePropertyName, nestedTargetPropertyName);
            }
        }

        boolean indexed = false;
        String indexedSourcePropertyPrefix = sourceRootPropertyName + "[";
        for (String indexedSourcePropertyName : getIndexedSourcePropertyNames(source, indexedSourcePropertyPrefix)) {
            indexed = true;
            String targetIndexedPropertyName = appendPropertyPath(targetPathPrefix, targetRootPropertyName + indexedSourcePropertyName.substring(sourceRootPropertyName.length()));
            if (containsSourceProperty(source, indexedSourcePropertyName)) {
                Object nestedSource = getSourcePropertyValue(source, indexedSourcePropertyName);
                if (!assignNullToMissingIndexedProperties(object, nestedSource, targetIndexedPropertyName, nestedSourcePropertyName, nestedTargetPropertyName) && !containsPropertyPath(nestedSource, nestedSourcePropertyName)) {
                    setPropertyToNull(object, targetIndexedPropertyName + "." + nestedTargetPropertyName);
                }
            }
            else if (!containsPropertyPath(source, indexedSourcePropertyName + "." + nestedSourcePropertyName)) {
                setPropertyToNull(object, targetIndexedPropertyName + "." + nestedTargetPropertyName);
            }
        }
        return indexed;
    }

    private static boolean assignNullToMissingCollectionProperties(Object object, Collection collection, String targetPathPrefix, String targetRootPropertyName, String nestedSourcePropertyName, String nestedTargetPropertyName) {
        int index = 0;
        for (Object item : collection) {
            String targetIndexedPropertyName = appendPropertyPath(targetPathPrefix, targetRootPropertyName + "[" + index + "]");
            assignNullToMissingNestedProperty(object, item, targetIndexedPropertyName, nestedSourcePropertyName, nestedTargetPropertyName);
            index++;
        }
        return true;
    }

    private static boolean assignNullToMissingMapProperties(Object object, Map map, String targetPathPrefix, String targetRootPropertyName, String nestedSourcePropertyName, String nestedTargetPropertyName) {
        for (Object entryObject : map.entrySet()) {
            Map.Entry entry = (Map.Entry) entryObject;
            String targetIndexedPropertyName = appendPropertyPath(targetPathPrefix, targetRootPropertyName + "[" + entry.getKey() + "]");
            assignNullToMissingNestedProperty(object, entry.getValue(), targetIndexedPropertyName, nestedSourcePropertyName, nestedTargetPropertyName);
        }
        return true;
    }

    private static void assignNullToMissingNestedProperty(Object object, Object nestedSource, String targetIndexedPropertyName, String nestedSourcePropertyName, String nestedTargetPropertyName) {
        if (!assignNullToMissingIndexedProperties(object, nestedSource, targetIndexedPropertyName, nestedSourcePropertyName, nestedTargetPropertyName) && !containsPropertyPath(nestedSource, nestedSourcePropertyName)) {
            setPropertyToNull(object, targetIndexedPropertyName + "." + nestedTargetPropertyName);
        }
    }

    private static String joinPropertyPath(String[] segments, int start, int end) {
        StringBuilder propertyPath = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (propertyPath.length() > 0) {
                propertyPath.append('.');
            }
            propertyPath.append(segments[i]);
        }
        return propertyPath.toString();
    }

    private static boolean bindingSourceContainsProperty(DataBindingSource bindingSource, String propertyName, String filter) {
        String sourcePropertyName = filter == null ? propertyName : filter + "." + propertyName;
        int exactPrefixSegments = filter == null ? 0 : splitPropertyPath(filter).length;
        return containsPropertyPath(bindingSource, sourcePropertyName, exactPrefixSegments) || containsPropertyPath(bindingSource, checkboxMarkerPropertyName(sourcePropertyName), exactPrefixSegments);
    }

    private static boolean containsPropertyPath(Object source, String propertyName) {
        return containsPropertyPath(source, propertyName, 0);
    }

    private static boolean containsPropertyPath(Object source, String propertyName, int exactPrefixSegments) {
        if (containsSourceProperty(source, propertyName)) {
            return true;
        }
        if (containsIndexedPropertyPath(source, propertyName, exactPrefixSegments)) {
            return true;
        }
        int separator = propertyPathSeparator(propertyName);
        if (separator == -1) {
            return false;
        }
        String rootPropertyName = propertyName.substring(0, separator);
        if (!containsSourceProperty(source, rootPropertyName)) {
            return containsIndexedNestedPropertyPath(source, rootPropertyName, propertyName.substring(separator + 1));
        }
        Object nestedSource = getSourcePropertyValue(source, rootPropertyName);
        String nestedPropertyName = propertyName.substring(separator + 1);
        if (nestedSource instanceof Collection) {
            for (Object item : (Collection) nestedSource) {
                if (containsPropertyPath(item, nestedPropertyName)) {
                    return true;
                }
            }
            return false;
        }
        return containsPropertyPath(nestedSource, nestedPropertyName);
    }

    private static boolean containsIndexedNestedPropertyPath(Object source, String rootPropertyName, String nestedPropertyName) {
        String indexedSourcePropertyPrefix = rootPropertyName + "[";
        for (String indexedSourcePropertyName : getIndexedSourcePropertyNames(source, indexedSourcePropertyPrefix)) {
            if (containsSourceProperty(source, indexedSourcePropertyName) && containsPropertyPath(getSourcePropertyValue(source, indexedSourcePropertyName), nestedPropertyName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIndexedPropertyPath(Object source, String propertyName, int exactPrefixSegments) {
        for (String indexedPropertyName : getSourcePropertyNames(source)) {
            if (indexedPropertyPathMatches(indexedPropertyName, propertyName, exactPrefixSegments)) {
                return true;
            }
        }
        return false;
    }

    private static int propertyPathSeparator(String propertyName) {
        return propertyPathSeparator(propertyName, false);
    }

    private static int propertyPathSeparator(String propertyName, boolean last) {
        int separator = -1;
        int bracketDepth = 0;
        for (int i = 0; i < propertyName.length(); i++) {
            char character = propertyName.charAt(i);
            if (character == '[') {
                bracketDepth++;
            }
            else if (character == ']' && bracketDepth > 0) {
                bracketDepth--;
            }
            else if (character == '.' && bracketDepth == 0) {
                if (!last) {
                    return i;
                }
                separator = i;
            }
        }
        return separator;
    }

    private static String[] splitPropertyPath(String propertyName) {
        List<String> segments = new ArrayList<>();
        StringBuilder segment = new StringBuilder();
        int bracketDepth = 0;
        for (int i = 0; i < propertyName.length(); i++) {
            char character = propertyName.charAt(i);
            if (character == '.' && bracketDepth == 0) {
                segments.add(segment.toString());
                segment.setLength(0);
            }
            else {
                if (character == '[') {
                    bracketDepth++;
                }
                else if (character == ']' && bracketDepth > 0) {
                    bracketDepth--;
                }
                segment.append(character);
            }
        }
        segments.add(segment.toString());
        return segments.toArray(new String[0]);
    }

    private static boolean indexedPropertyPathMatches(String indexedPropertyName, String propertyName, int exactPrefixSegments) {
        String[] indexedPropertySegments = splitPropertyPath(indexedPropertyName);
        String[] propertySegments = splitPropertyPath(propertyName);
        if (indexedPropertySegments.length != propertySegments.length) {
            return false;
        }
        for (int i = 0; i < propertySegments.length; i++) {
            if (indexedPropertySegments[i].equals(propertySegments[i])) {
                continue;
            }
            if (i < exactPrefixSegments) {
                return false;
            }
            if (!indexedSegmentMatches(indexedPropertySegments[i], propertySegments[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean indexedSegmentMatches(String indexedSegment, String segment) {
        return indexedSegment.startsWith(segment + "[") && indexedSegment.endsWith("]");
    }

    private static Set<String> getIndexedSourcePropertyNames(Object source, String indexedSourcePropertyPrefix) {
        Set<String> indexedSourcePropertyNames = new LinkedHashSet<>();
        for (String propertyName : getSourcePropertyNames(source)) {
            if (propertyName.startsWith(indexedSourcePropertyPrefix)) {
                int closingIndex = propertyName.indexOf(']', indexedSourcePropertyPrefix.length());
                if (closingIndex > -1) {
                    indexedSourcePropertyNames.add(propertyName.substring(0, closingIndex + 1));
                }
            }
        }
        return indexedSourcePropertyNames;
    }

    private static boolean containsSourceProperty(Object source, String propertyName) {
        if (source instanceof DataBindingSource) {
            return ((DataBindingSource) source).containsProperty(propertyName);
        }
        if (source instanceof Map) {
            return ((Map) source).containsKey(propertyName);
        }
        return false;
    }

    private static Set<String> getSourcePropertyNames(Object source) {
        Set<String> propertyNames = new LinkedHashSet<>();
        if (source instanceof DataBindingSource) {
            propertyNames.addAll(((DataBindingSource) source).getPropertyNames());
        }
        else if (source instanceof Map) {
            for (Object key : ((Map) source).keySet()) {
                propertyNames.add(key.toString());
            }
        }
        return propertyNames;
    }

    private static Object getSourcePropertyValue(Object source, String propertyName) {
        if (source instanceof DataBindingSource) {
            return ((DataBindingSource) source).getPropertyValue(propertyName);
        }
        return ((Map) source).get(propertyName);
    }

    private static String checkboxMarkerPropertyName(String propertyName) {
        int separator = propertyPathSeparator(propertyName, true);
        if (separator == -1) {
            return "_" + propertyName;
        }
        return propertyName.substring(0, separator + 1) + "_" + propertyName.substring(separator + 1);
    }

    private static boolean hasNestedSourceEntries(Map map) {
        for (Object value : map.values()) {
            if (value instanceof Map || value instanceof Collection || value instanceof DataBindingSource) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldExpandMapEntries(Object target, Class targetType, String propertyName) {
        Object value = getTargetPropertyValue(target, propertyName);
        if (value instanceof Map && hasStructuredTargetMapValues((Map) value)) {
            return true;
        }

        Class mapValueType = getMapValueType(target, targetType, propertyName);
        return mapValueType != null && isStructuredMapValueType(mapValueType);
    }

    private static boolean hasStructuredTargetMapValues(Map map) {
        for (Object value : map.values()) {
            if (value != null && isStructuredMapValueType(value.getClass())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStructuredMapValueType(Class valueType) {
        Package valuePackage = valueType.getPackage();
        return !valueType.isPrimitive() &&
            (valuePackage == null || !valuePackage.getName().startsWith("java.")) &&
            !CharSequence.class.isAssignableFrom(valueType) &&
            !Number.class.isAssignableFrom(valueType) &&
            !Boolean.class.isAssignableFrom(valueType) &&
            !Enum.class.isAssignableFrom(valueType) &&
            !Map.class.isAssignableFrom(valueType) &&
            !Collection.class.isAssignableFrom(valueType) &&
            !Object.class.equals(valueType);
    }

    private static Class getMapValueType(Object target, Class targetType, String propertyName) {
        Class resolvedTargetType = target == null ? targetType : target.getClass();
        if (resolvedTargetType == null) {
            return null;
        }

        MetaClass mc = GroovySystem.getMetaClassRegistry().getMetaClass(resolvedTargetType);
        MetaProperty metaProperty = mc.getMetaProperty(propertyName);
        if (metaProperty == null || !Map.class.isAssignableFrom(metaProperty.getType())) {
            return null;
        }

        Field field = findField(resolvedTargetType, propertyName);
        if (field == null) {
            return null;
        }
        return getMapValueType(field.getGenericType());
    }

    private static Class getMapValueType(Type type) {
        if (!(type instanceof ParameterizedType)) {
            return null;
        }

        Type[] typeArguments = ((ParameterizedType) type).getActualTypeArguments();
        if (typeArguments.length < 2) {
            return null;
        }
        Type valueType = typeArguments[1];
        if (valueType instanceof Class) {
            return (Class) valueType;
        }
        if (valueType instanceof ParameterizedType && ((ParameterizedType) valueType).getRawType() instanceof Class) {
            return (Class) ((ParameterizedType) valueType).getRawType();
        }
        return null;
    }

    private static Field findField(Class type, String propertyName) {
        Class currentType = type;
        while (currentType != null) {
            try {
                return currentType.getDeclaredField(propertyName);
            }
            catch (NoSuchFieldException e) {
                currentType = currentType.getSuperclass();
            }
        }
        return null;
    }

    private static Object getTargetObject(Object object, String targetPathPrefix) {
        if (targetPathPrefix == null || targetPathPrefix.length() == 0) {
            return object;
        }

        Object targetObject = object;
        for (String propertyName : splitPropertyPath(targetPathPrefix)) {
            if (targetObject == null) {
                return null;
            }
            targetObject = getPropertyValue(targetObject, propertyName);
        }
        return targetObject;
    }

    private static Object getTargetPropertyValue(Object target, String propertyName) {
        if (target == null) {
            return null;
        }

        try {
            MetaClass mc = GroovySystem.getMetaClassRegistry().getMetaClass(target.getClass());
            return mc.getProperty(target, propertyName);
        }
        catch (Exception e) {
            return null;
        }
    }

    private static String appendPropertyPath(String parentPath, String propertyName) {
        if (parentPath == null || parentPath.length() == 0) {
            return propertyName;
        }
        return parentPath + "." + propertyName;
    }

    private static void setPropertyToNull(Object object, String propertyName) {
        String[] propertyNames = splitPropertyPath(propertyName);
        Object currentObject = object;
        for (int i = 0; i < propertyNames.length - 1 && currentObject != null; i++) {
            currentObject = getPropertyValue(currentObject, propertyNames[i]);
        }
        if (currentObject != null) {
            setPropertyValueToNull(currentObject, propertyNames[propertyNames.length - 1]);
        }
    }

    private static Object getPropertyValue(Object object, String propertyName) {
        int bracket = propertyName.indexOf('[');
        try {
            if (bracket == -1) {
                MetaClass mc = GroovySystem.getMetaClassRegistry().getMetaClass(object.getClass());
                return mc.getProperty(object, propertyName);
            }

            MetaClass mc = GroovySystem.getMetaClassRegistry().getMetaClass(object.getClass());
            Object indexedProperty = mc.getProperty(object, propertyName.substring(0, bracket));
            return getIndexedValue(indexedProperty, propertyName.substring(bracket + 1, propertyName.indexOf(']', bracket)));
        }
        catch (Exception e) {
            return null;
        }
    }

    private static Object getIndexedValue(Object indexedProperty, String index) {
        if (indexedProperty instanceof List) {
            List list = (List) indexedProperty;
            Integer parsedIndex = parseIndex(index);
            return parsedIndex != null && parsedIndex >= 0 && parsedIndex < list.size() ? list.get(parsedIndex) : null;
        }
        if (indexedProperty instanceof Map) {
            return ((Map) indexedProperty).get(index);
        }
        return null;
    }

    private static void setPropertyValueToNull(Object object, String propertyName) {
        int bracket = propertyName.indexOf('[');
        try {
            if (bracket == -1) {
                MetaClass mc = GroovySystem.getMetaClassRegistry().getMetaClass(object.getClass());
                if (mc.hasProperty(object, propertyName) != null) {
                    mc.setProperty(object, propertyName, null);
                }
                return;
            }

            MetaClass mc = GroovySystem.getMetaClassRegistry().getMetaClass(object.getClass());
            Object indexedProperty = mc.getProperty(object, propertyName.substring(0, bracket));
            String index = propertyName.substring(bracket + 1, propertyName.indexOf(']', bracket));
            if (indexedProperty instanceof List) {
                List list = (List) indexedProperty;
                Integer parsedIndex = parseIndex(index);
                if (parsedIndex != null && parsedIndex >= 0 && parsedIndex < list.size()) {
                    list.set(parsedIndex, null);
                }
            }
            else if (indexedProperty instanceof Map) {
                ((Map) indexedProperty).put(index, null);
            }
        }
        catch (Exception e) {
            // ignore invalid indexed nullMissing paths
        }
    }

    private static Integer parseIndex(String index) {
        try {
            return Integer.valueOf(index);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    protected static String[] getMessageCodes(String messageCode,
            Class objectType) {
        String[] codes = {objectType.getName() + "." + messageCode, messageCode};
        return codes;
    }

    public static DataBindingSourceRegistry getDataBindingSourceRegistry(GrailsApplication grailsApplication) {
        DataBindingSourceRegistry registry = null;
        if (grailsApplication != null) {
            ApplicationContext context = grailsApplication.getMainContext();
            if (context != null) {
                if (context.containsBean(DataBindingSourceRegistry.BEAN_NAME)) {
                    registry = context.getBean(DataBindingSourceRegistry.BEAN_NAME, DataBindingSourceRegistry.class);
                }
            }
        }
        if (registry == null) {
            registry = new DefaultDataBindingSourceRegistry();
        }

        return registry;
    }

    public static DataBindingSource createDataBindingSource(GrailsApplication grailsApplication, Class bindingTargetType, Object bindingSource) {
        final DataBindingSourceRegistry registry = getDataBindingSourceRegistry(grailsApplication);
        final MimeType mimeType = getMimeType(grailsApplication, bindingSource);
        return registry.createDataBindingSource(mimeType, bindingTargetType, bindingSource);
    }

    public static CollectionDataBindingSource createCollectionDataBindingSource(GrailsApplication grailsApplication, Class bindingTargetType, Object bindingSource) {
        final DataBindingSourceRegistry registry = getDataBindingSourceRegistry(grailsApplication);
        final MimeType mimeType = getMimeType(grailsApplication, bindingSource);
        return registry.createCollectionDataBindingSource(mimeType, bindingTargetType, bindingSource);
    }

    public static MimeType getMimeType(GrailsApplication grailsApplication,
            Object bindingSource) {
        final MimeTypeResolver mimeTypeResolver = getMimeTypeResolver(grailsApplication);
        return resolveMimeType(bindingSource, mimeTypeResolver);
    }

    public static MimeTypeResolver getMimeTypeResolver(
            GrailsApplication grailsApplication) {
        MimeTypeResolver mimeTypeResolver = null;
        if (grailsApplication != null) {
            ApplicationContext context = grailsApplication.getMainContext();
            if (context != null) {
                if (context.containsBean(MimeTypeResolver.BEAN_NAME)) {
                    mimeTypeResolver = context.getBean(MimeTypeResolver.BEAN_NAME, MimeTypeResolver.class);
                }
            }
        }
        return mimeTypeResolver;
    }

    public static MimeType resolveMimeType(Object bindingSource, MimeTypeResolver mimeTypeResolver) {
        return MimeTypeUtils.resolveMimeType(bindingSource, mimeTypeResolver);
    }

    private static DataBinder getGrailsWebDataBinder(final GrailsApplication grailsApplication) {
        DataBinder dataBinder = null;
        if (grailsApplication != null) {
            final ApplicationContext mainContext = grailsApplication.getMainContext();
            if (mainContext != null && mainContext.containsBean(DATA_BINDER_BEAN_NAME)) {
                dataBinder = mainContext.getBean(DATA_BINDER_BEAN_NAME, DataBinder.class);
            }
        }
        if (dataBinder == null) {
            // this should really never happen in the running app as the binder
            // should always be found in the context
            dataBinder = new GrailsWebDataBinder(grailsApplication);
        }
        return dataBinder;
    }

    @SuppressWarnings("unchecked")
    public static Map convertPotentialGStrings(Map<Object, Object> args) {
        Map newArgs = new HashMap(args.size());
        for (Map.Entry<Object, Object> entry : args.entrySet()) {
            newArgs.put(unwrapGString(entry.getKey()), unwrapGString(entry.getValue()));
        }
        return newArgs;
    }

    private static Object unwrapGString(Object value) {
        if (value instanceof CharSequence) {
            return value.toString();
        }
        return value;
    }
}

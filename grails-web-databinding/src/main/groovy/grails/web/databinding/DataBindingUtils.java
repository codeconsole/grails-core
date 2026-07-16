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
import java.util.concurrent.ConcurrentHashMap;

import groovy.lang.GroovySystem;
import groovy.lang.MetaClass;

import jakarta.servlet.ServletRequest;

import org.springframework.context.ApplicationContext;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

import grails.core.GrailsApplication;
import grails.databinding.CollectionDataBindingSource;
import grails.databinding.DataBinder;
import grails.databinding.DataBindingSource;
import grails.util.Environment;
import grails.util.Holders;
import grails.validation.ValidationErrors;
import grails.web.mime.MimeType;
import grails.web.mime.MimeTypeResolver;
import grails.web.mime.MimeTypeUtils;
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

    public static final String DATA_BINDER_BEAN_NAME = "grailsWebDataBinder";
    private static final String BLANK = "";
    private static final Map<Class, List> CLASS_TO_BINDING_INCLUDE_LIST = new ConcurrentHashMap<>();
    private static final Map<Class, List> CLASS_TO_LEGACY_BINDING_INCLUDE_LIST = new ConcurrentHashMap<>();

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
                if (includeList != null && !Environment.getCurrent().isReloadEnabled()) {
                    includeListCache.put(objectClass, includeList);
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
        return getPropertyNamesWithBindableValue(object, Boolean.FALSE);
    }

    static List getPropertyNamesWithBindableValue(final Object object, final Boolean bindableValue) {
        final Map constrainedProperties = getConstrainedProperties(object);
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
            return application.getConfig().getProperty(DefaultASTDatabindingHelper.LEGACY_BINDABLE_DEFAULT, Boolean.class, false);
        }
        Object value = Holders.getFlatConfig().get(DefaultASTDatabindingHelper.LEGACY_BINDABLE_DEFAULT);
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
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
        if (include == null) {
            include = getBindingIncludeList(object);
        }
        else if (include.isEmpty()) {
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
        return bindObjectToDomainInstance(entity, object, source, include, exclude, filter);
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

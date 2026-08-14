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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import groovy.lang.GroovySystem;
import groovy.lang.MetaClass;

import jakarta.servlet.ServletRequest;

import org.springframework.context.ApplicationContext;
import org.springframework.util.ReflectionUtils;
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

    /**
     * The beans used by data binding for the most recently seen {@link ApplicationContext}. Data binding happens on
     * every request and each bind previously repeated the same {@code containsBean}/{@code getBean} lookups for
     * singletons which never change for the life of a context.
     * <p>
     * The cache holds a single entry on purpose. An application has one main context, so a single entry is enough to
     * remove the repeated lookups, while a map keyed by context would retain every context ever seen - which would
     * leak whole bean factories in test runs and in development where contexts are replaced. Whenever the context
     * differs from the cached one, the entry is replaced and the previous context becomes eligible for collection.
     * <p>
     * The field is volatile so that request threads see a consistent, fully constructed entry. A race merely results
     * in two threads resolving the same singletons, which is harmless.
     */
    private static volatile ContextBoundBeans contextBoundBeans;

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
        final Class<?> objectClass = object.getClass();
        List includeList = CLASS_TO_BINDING_INCLUDE_LIST.get(objectClass);
        if (includeList == null) {
            includeList = resolveBindingIncludeList(objectClass);
            if (!Environment.getCurrent().isReloadEnabled()) {
                // classes which are not enhanced with a whitelist resolve to an empty include list; that negative
                // result is cached as well so the field lookup is performed at most once per class
                CLASS_TO_BINDING_INCLUDE_LIST.put(objectClass, includeList);
            }
        }
        return includeList;
    }

    private static List resolveBindingIncludeList(final Class<?> objectClass) {
        // ReflectionUtils returns null instead of throwing NoSuchFieldException for the very common case of a class
        // which was never enhanced by the data binding AST transformation
        final Field whiteListField = ReflectionUtils.findField(objectClass, DefaultASTDatabindingHelper.DEFAULT_DATABINDING_WHITELIST);
        // only a whitelist declared on the class itself applies, an inherited one does not
        if (whiteListField != null && objectClass.equals(whiteListField.getDeclaringClass()) &&
                (whiteListField.getModifiers() & Modifier.STATIC) != 0) {
            try {
                final Object whiteListValue = whiteListField.get(objectClass);
                if (whiteListValue instanceof List) {
                    return (List) whiteListValue;
                }
            }
            catch (IllegalAccessException e) {
                // the whitelist is not readable, bind without an include list
            }
        }
        return Collections.emptyList();
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
        bindToCollection(targetType, collectionToPopulate, collectionBindingSource, Holders.findApplication());
    }

    private static <T> void bindToCollection(final Class<T> targetType, final Collection<T> collectionToPopulate,
            final CollectionDataBindingSource collectionBindingSource, final GrailsApplication application) throws InstantiationException, IllegalAccessException {
        final PersistentEntity entity = findPersistentEntity(application, targetType.getName());
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

            bindObjectToDomainInstance(entity, newObject, dataBindingSource, getBindingIncludeList(newObject), Collections.emptyList(), null, application);
            collectionToPopulate.add(newObject);
        }
    }

    public static <T> void bindToCollection(final Class<T> targetType, final Collection<T> collectionToPopulate, final ServletRequest request) throws InstantiationException, IllegalAccessException {
        final GrailsApplication grailsApplication = Holders.findApplication();
        final CollectionDataBindingSource collectionDataBindingSource = createCollectionDataBindingSource(grailsApplication, targetType, request);
        bindToCollection(targetType, collectionToPopulate, collectionDataBindingSource, grailsApplication);
    }

    private static PersistentEntity findPersistentEntity(final GrailsApplication application, final String className) {
        if (application != null) {
            try {
                return application.getMappingContext().getPersistentEntity(className);
            } catch (GrailsConfigurationException e) {
                //no-op
            }
        }
        return null;
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
        if (include == null && exclude == null) {
            include = getBindingIncludeList(object);
        }
        final GrailsApplication application = Holders.findApplication();
        final PersistentEntity entity = findPersistentEntity(application, object.getClass().getName());
        return bindObjectToDomainInstance(entity, object, source, include, exclude, filter, application);
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
    public static BindingResult bindObjectToDomainInstance(PersistentEntity entity, Object object,
                                                           Object source, List include, List exclude, String filter) {
        return bindObjectToDomainInstance(entity, object, source, include, exclude, filter, Holders.findApplication());
    }

    /**
     * Binds using an already resolved {@link GrailsApplication}. Resolving the application is not a simple field
     * read, it walks the registered discovery strategies and ends in a bean lookup, so callers which have already
     * resolved it pass it down instead of resolving it again for the same bind.
     */
    @SuppressWarnings("unchecked")
    private static BindingResult bindObjectToDomainInstance(PersistentEntity entity, Object object,
            Object source, List include, List exclude, String filter, GrailsApplication grailsApplication) {
        BindingResult bindingResult = null;

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
        final ContextBoundBeans beans = getContextBoundBeans(grailsApplication);
        DataBindingSourceRegistry registry = beans == null ? null : beans.getDataBindingSourceRegistry();
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
        final ContextBoundBeans beans = getContextBoundBeans(grailsApplication);
        return beans == null ? null : beans.getMimeTypeResolver();
    }

    public static MimeType resolveMimeType(Object bindingSource, MimeTypeResolver mimeTypeResolver) {
        return MimeTypeUtils.resolveMimeType(bindingSource, mimeTypeResolver);
    }

    private static DataBinder getGrailsWebDataBinder(final GrailsApplication grailsApplication) {
        final ContextBoundBeans beans = getContextBoundBeans(grailsApplication);
        DataBinder dataBinder = beans == null ? null : beans.getDataBinder();
        if (dataBinder == null) {
            // this should really never happen in the running app as the binder
            // should always be found in the context
            dataBinder = new GrailsWebDataBinder(grailsApplication);
        }
        return dataBinder;
    }

    /**
     * @param grailsApplication the application to resolve the main context from, may be null
     * @return the cached beans of the application's main context or null if there is no context to look beans up in
     */
    private static ContextBoundBeans getContextBoundBeans(final GrailsApplication grailsApplication) {
        if (grailsApplication == null) {
            return null;
        }
        final ApplicationContext context = grailsApplication.getMainContext();
        if (context == null) {
            return null;
        }
        ContextBoundBeans beans = contextBoundBeans;
        if (beans == null || beans.applicationContext != context) {
            beans = new ContextBoundBeans(context);
            contextBoundBeans = beans;
        }
        return beans;
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

    /**
     * The data binding beans of a single {@link ApplicationContext}, resolved on first use and kept for as long as
     * that context is the one being bound against.
     */
    private static final class ContextBoundBeans {

        private final ApplicationContext applicationContext;
        private final CachedBean<DataBindingSourceRegistry> dataBindingSourceRegistry =
                new CachedBean<>(DataBindingSourceRegistry.BEAN_NAME, DataBindingSourceRegistry.class);
        private final CachedBean<MimeTypeResolver> mimeTypeResolver =
                new CachedBean<>(MimeTypeResolver.BEAN_NAME, MimeTypeResolver.class);
        private final CachedBean<DataBinder> dataBinder =
                new CachedBean<>(DATA_BINDER_BEAN_NAME, DataBinder.class);

        private ContextBoundBeans(ApplicationContext applicationContext) {
            this.applicationContext = applicationContext;
        }

        private DataBindingSourceRegistry getDataBindingSourceRegistry() {
            return dataBindingSourceRegistry.get(applicationContext);
        }

        private MimeTypeResolver getMimeTypeResolver() {
            return mimeTypeResolver.get(applicationContext);
        }

        private DataBinder getDataBinder() {
            return dataBinder.get(applicationContext);
        }
    }

    /**
     * A singleton bean looked up at most once per {@link ApplicationContext}. Each bean is resolved lazily so that
     * asking for one of them never triggers the creation of another.
     * <p>
     * Both fields are volatile and {@code resolved} is written last, so a thread which sees {@code resolved} also
     * sees the bean it was resolved to. Two threads racing simply look the same singleton up twice.
     *
     * @param <T> the type of the bean
     */
    private static final class CachedBean<T> {

        private final String beanName;
        private final Class<T> beanType;
        private volatile T bean;
        private volatile boolean resolved;

        private CachedBean(String beanName, Class<T> beanType) {
            this.beanName = beanName;
            this.beanType = beanType;
        }

        private T get(ApplicationContext applicationContext) {
            if (!resolved) {
                bean = applicationContext.containsBean(beanName) ? applicationContext.getBean(beanName, beanType) : null;
                resolved = true;
            }
            return bean;
        }
    }
}

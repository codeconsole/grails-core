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
package org.grails.datastore.gorm.events;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEvent;

import grails.gorm.annotation.AutoTimestamp;
import org.grails.datastore.gorm.timestamp.DefaultTimestampProvider;
import org.grails.datastore.gorm.timestamp.TimestampProvider;
import org.grails.datastore.mapping.config.Entity;
import org.grails.datastore.mapping.config.Settings;
import org.grails.datastore.mapping.core.Datastore;
import org.grails.datastore.mapping.engine.EntityAccess;
import org.grails.datastore.mapping.engine.event.AbstractPersistenceEvent;
import org.grails.datastore.mapping.engine.event.AbstractPersistenceEventListener;
import org.grails.datastore.mapping.engine.event.EventType;
import org.grails.datastore.mapping.engine.event.PreInsertEvent;
import org.grails.datastore.mapping.engine.event.PreUpdateEvent;
import org.grails.datastore.mapping.model.ClassMapping;
import org.grails.datastore.mapping.model.MappingContext;
import org.grails.datastore.mapping.model.PersistentEntity;
import org.grails.datastore.mapping.model.PersistentProperty;

/**
 * An event listener that adds support for GORM-style auto-timestamping
 *
 * @author Graeme Rocher
 * @since 1.0
 */
public class AutoTimestampEventListener extends AbstractPersistenceEventListener implements MappingContext.Listener {

    // if false, will not set timestamp on insert event if value is not null
    @Value("${" + Settings.SETTING_AUTO_TIMESTAMP_INSERT_OVERWRITE + ":true}")
    public boolean insertOverwrite = true;

    public static final String DATE_CREATED_PROPERTY = "dateCreated";
    public static final String LAST_UPDATED_PROPERTY = "lastUpdated";

    protected Map<String, Optional<Set<String>>> entitiesWithDateCreated = new ConcurrentHashMap<>();
    protected Map<String, Optional<Set<String>>> entitiesWithLastUpdated = new ConcurrentHashMap<>();
    protected Collection<String> uninitializedEntities = new ConcurrentLinkedQueue<>();

    private final ThreadLocal<DisabledTimestamps> disabledDateCreated = new ThreadLocal<>();
    private final ThreadLocal<DisabledTimestamps> disabledLastUpdated = new ThreadLocal<>();

    private TimestampProvider timestampProvider = new DefaultTimestampProvider();

    public AutoTimestampEventListener(final Datastore datastore) {
        super(datastore);

        MappingContext mappingContext = datastore.getMappingContext();
        initForMappingContext(mappingContext);
    }

    protected AutoTimestampEventListener(final MappingContext mappingContext) {
        super(null);

        initForMappingContext(mappingContext);
    }

    protected void initForMappingContext(MappingContext mappingContext) {
        for (PersistentEntity persistentEntity : mappingContext.getPersistentEntities()) {
            storeDateCreatedAndLastUpdatedInfo(persistentEntity);
        }

        mappingContext.addMappingContextListener(this);
    }

    @Override
    protected void onPersistenceEvent(final AbstractPersistenceEvent event) {
        if (event.getEntity() == null) return;

        if (event.getEventType() == EventType.PreInsert) {
            beforeInsert(event.getEntity(), event.getEntityAccess());
        } else if (event.getEventType() == EventType.PreUpdate) {
            beforeUpdate(event.getEntity(), event.getEntityAccess());
        }
    }

    public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
        return PreInsertEvent.class.isAssignableFrom(eventType) ||
               PreUpdateEvent.class.isAssignableFrom(eventType);
    }

    public boolean beforeInsert(PersistentEntity entity, EntityAccess ea) {
        final String name = entity.getName();
        initializeIfNecessary(entity, name);
        Class<?> dateCreatedType = null;
        Object timestamp = null;
        Set<String> props = getDateCreatedPropertyNames(name);
        if (props != null) {
            for (String prop : props) {
                if (insertOverwrite || ea.getPropertyValue(prop) == null) {
                    dateCreatedType = ea.getPropertyType(prop);
                    timestamp = timestampProvider.createTimestamp(dateCreatedType);
                    ea.setProperty(prop, timestamp);
                }
            }
        }
        props = getLastUpdatedPropertyNames(name);
        if (props != null) {
            for (String prop : props) {
                if (insertOverwrite || ea.getPropertyValue(prop) == null) {
                    Class<?> lastUpdateType = ea.getPropertyType(prop);
                    if (dateCreatedType == null || !lastUpdateType.isAssignableFrom(dateCreatedType)) {
                        timestamp = timestampProvider.createTimestamp(lastUpdateType);
                    }
                    ea.setProperty(prop, timestamp);
                }
            }
        }
        return true;
    }

    private void initializeIfNecessary(PersistentEntity entity, String name) {
        if (uninitializedEntities.contains(name)) {
            storeDateCreatedAndLastUpdatedInfo(entity);
            uninitializedEntities.remove(name);
        }
    }

    public boolean beforeUpdate(PersistentEntity entity, EntityAccess ea) {
        Set<String> props = getLastUpdatedPropertyNames(entity.getName());
        if (props != null) {
            for (String prop : props) {
                Class<?> lastUpdateType = ea.getPropertyType(prop);
                Object timestamp = timestampProvider.createTimestamp(lastUpdateType);
                ea.setProperty(prop, timestamp);
            }
        }
        return true;
    }

    protected Set<String> getLastUpdatedPropertyNames(String entityName) {
        if (isDisabled(disabledLastUpdated, entityName)) {
            return null;
        }
        Optional<Set<String>> properties = entitiesWithLastUpdated.get(entityName);
        return properties == null ? null : properties.orElse(null);
    }

    protected Set<String> getDateCreatedPropertyNames(String entityName) {
        if (isDisabled(disabledDateCreated, entityName)) {
            return null;
        }
        Optional<Set<String>> properties = entitiesWithDateCreated.get(entityName);
        return properties == null ? null : properties.orElse(null);
    }

    private static Field getFieldFromHierarchy(Class<?> entity, String fieldName) {
        Class<?> clazz = entity;
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    protected void storeDateCreatedAndLastUpdatedInfo(PersistentEntity persistentEntity) {
        if (persistentEntity.isInitialized()) {
            ClassMapping<?> classMapping = persistentEntity.getMapping();
            Entity<?> mappedForm = classMapping.getMappedForm();
            if (mappedForm == null || mappedForm.isAutoTimestamp()) {
                for (PersistentProperty<?> property : persistentEntity.getPersistentProperties()) {
                    if (property.getName().equals(LAST_UPDATED_PROPERTY)) {
                        storeTimestampAvailability(entitiesWithLastUpdated, persistentEntity, property);
                    } else if (property.getName().equals(DATE_CREATED_PROPERTY)) {
                        storeTimestampAvailability(entitiesWithDateCreated, persistentEntity, property);
                    } else {
                        Field field = getFieldFromHierarchy(persistentEntity.getJavaClass(), property.getName());
                        if (field != null && field.isAnnotationPresent(AutoTimestamp.class)) {
                            AutoTimestamp autoTimestamp = field.getAnnotation(AutoTimestamp.class);
                            if (autoTimestamp.value() == AutoTimestamp.EventType.UPDATED) {
                                storeTimestampAvailability(entitiesWithLastUpdated, persistentEntity, property);
                            } else {
                                storeTimestampAvailability(entitiesWithDateCreated, persistentEntity, property);
                            }
                        }
                    }
                }
            }
        } else {
            uninitializedEntities.add(persistentEntity.getName());
        }
    }

    protected void storeTimestampAvailability(Map<String, Optional<Set<String>>> timestampAvailabilityMap, PersistentEntity persistentEntity, PersistentProperty<?> property) {
        if (property != null && timestampProvider.supportsCreating(property.getType())) {
            Optional<Set<String>> timestampProperties = timestampAvailabilityMap.computeIfAbsent(persistentEntity.getName(), k -> Optional.of(new HashSet<>()));
            if (timestampProperties.isPresent()) {
                timestampProperties.get().add(property.getName());
            }
            else {
                throw new IllegalStateException("Timestamp properties for entity [" + persistentEntity.getName() + "] have been disabled. Cannot add property [" + property.getName() + "]");
            }
        }
    }

    public void persistentEntityAdded(PersistentEntity entity) {
        storeDateCreatedAndLastUpdatedInfo(entity);
    }

    public TimestampProvider getTimestampProvider() {
        return timestampProvider;
    }

    public void setTimestampProvider(TimestampProvider timestampProvider) {
        this.timestampProvider = timestampProvider;
    }

    private static boolean isDisabled(final ThreadLocal<DisabledTimestamps> disabledTimestamps, final String entityName) {
        DisabledTimestamps disabled = disabledTimestamps.get();
        return disabled != null && disabled.isDisabled(entityName);
    }

    private static DisabledTimestamps getOrCreateDisabled(final ThreadLocal<DisabledTimestamps> disabledTimestamps) {
        DisabledTimestamps disabled = disabledTimestamps.get();
        if (disabled == null) {
            disabled = new DisabledTimestamps();
            disabledTimestamps.set(disabled);
        }
        return disabled;
    }

    private static void removeIfEmpty(final ThreadLocal<DisabledTimestamps> disabledTimestamps, final DisabledTimestamps disabled) {
        if (disabled.isEmpty()) {
            disabledTimestamps.remove();
        }
    }

    private static void runWithAllDisabled(final ThreadLocal<DisabledTimestamps> disabledTimestamps, final Runnable runnable) {
        DisabledTimestamps disabled = getOrCreateDisabled(disabledTimestamps);
        disabled.allDisabledDepth++;
        try {
            runnable.run();
        } finally {
            disabled.allDisabledDepth--;
            removeIfEmpty(disabledTimestamps, disabled);
        }
    }

    private static void runWithDisabled(final ThreadLocal<DisabledTimestamps> disabledTimestamps, final List<Class> classes, final Runnable runnable) {
        // only the names this scope newly disables may be re-enabled on exit; a name already
        // disabled by an enclosing scope on this thread must survive this scope's finally
        List<String> added = new ArrayList<>(classes.size());
        DisabledTimestamps disabled = getOrCreateDisabled(disabledTimestamps);
        try {
            for (Class clazz : classes) {
                String entityName = clazz.getName();
                if (disabled.entityNames.add(entityName)) {
                    added.add(entityName);
                }
            }
            runnable.run();
        } finally {
            disabled.entityNames.removeAll(added);
            removeIfEmpty(disabledTimestamps, disabled);
        }
    }

    /**
     * Temporarily disables the last updated processing during the execution of the runnable.
     * The processing is only disabled for the calling thread; concurrently executing threads
     * are unaffected.
     *
     * @param runnable The code to execute while the last updated listener is disabled
     */
    public void withoutLastUpdated(final Runnable runnable) {
        runWithAllDisabled(disabledLastUpdated, runnable);
    }

    /**
     * Temporarily disables the last updated processing only on the provided classes during the
     * execution of the runnable. The processing is only disabled for the calling thread;
     * concurrently executing threads are unaffected.
     *
     * @param classes Which classes to disable the last updated processing for
     * @param runnable The code to execute while the last updated listener is disabled
     */
    public void withoutLastUpdated(final List<Class> classes, final Runnable runnable) {
        runWithDisabled(disabledLastUpdated, classes, runnable);
    }

    /**
     * Temporarily disables the last updated processing only on the provided class during the
     * execution of the runnable. The processing is only disabled for the calling thread;
     * concurrently executing threads are unaffected.
     *
     * @param clazz Which class to disable the last updated processing for
     * @param runnable The code to execute while the last updated listener is disabled
     */
    public void withoutLastUpdated(final Class clazz, final Runnable runnable) {
        ArrayList<Class> list = new ArrayList<>(1);
        list.add(clazz);
        withoutLastUpdated(list, runnable);
    }

    /**
     * Temporarily disables the date created processing during the execution of the runnable.
     * The processing is only disabled for the calling thread; concurrently executing threads
     * are unaffected.
     *
     * @param runnable The code to execute while the date created listener is disabled
     */
    public void withoutDateCreated(final Runnable runnable) {
        runWithAllDisabled(disabledDateCreated, runnable);
    }

    /**
     * Temporarily disables the date created processing only on the provided classes during the
     * execution of the runnable. The processing is only disabled for the calling thread;
     * concurrently executing threads are unaffected.
     *
     * @param classes Which classes to disable the date created processing for
     * @param runnable The code to execute while the date created listener is disabled
     */
    public void withoutDateCreated(final List<Class> classes, final Runnable runnable) {
        runWithDisabled(disabledDateCreated, classes, runnable);
    }

    /**
     * Temporarily disables the date created processing only on the provided class during the
     * execution of the runnable. The processing is only disabled for the calling thread;
     * concurrently executing threads are unaffected.
     *
     * @param clazz Which class to disable the date created processing for
     * @param runnable The code to execute while the date created listener is disabled
     */
    public void withoutDateCreated(final Class clazz, final Runnable runnable) {
        ArrayList<Class> list = new ArrayList<>(1);
        list.add(clazz);
        withoutDateCreated(list, runnable);
    }

    /**
     * Temporarily disables the timestamp processing during the execution of the runnable.
     * The processing is only disabled for the calling thread; concurrently executing threads
     * are unaffected.
     *
     * @param runnable The code to execute while the timestamp listeners are disabled
     */
    public void withoutTimestamps(final Runnable runnable) {
        withoutDateCreated(() -> withoutLastUpdated(runnable));
    }

    /**
     * Temporarily disables the timestamp processing only on the provided classes during the
     * execution of the runnable. The processing is only disabled for the calling thread;
     * concurrently executing threads are unaffected.
     *
     * @param classes Which classes to disable the timestamp processing for
     * @param runnable The code to execute while the timestamp listeners are disabled
     */
    public void withoutTimestamps(final List<Class> classes, final Runnable runnable) {
        withoutDateCreated(classes, () -> withoutLastUpdated(classes, runnable));
    }

    /**
     * Temporarily disables the timestamp processing during the execution of the runnable.
     * The processing is only disabled for the calling thread; concurrently executing threads
     * are unaffected.
     *
     * @param clazz Which class to disable the timestamp processing for
     * @param runnable The code to execute while the timestamp listeners are disabled
     */
    public void withoutTimestamps(final Class clazz, final Runnable runnable) {
        withoutDateCreated(clazz, () -> withoutLastUpdated(clazz, runnable));
    }

    /**
     * The entities for which timestamp processing is temporarily disabled on the current thread.
     * All entities are disabled while {@code allDisabledDepth} is greater than zero; otherwise
     * only the entities named in {@code entityNames} are disabled.
     */
    private static final class DisabledTimestamps {

        private int allDisabledDepth;

        private final Set<String> entityNames = new HashSet<>();

        private boolean isDisabled(String entityName) {
            return allDisabledDepth > 0 || entityNames.contains(entityName);
        }

        private boolean isEmpty() {
            return allDisabledDepth == 0 && entityNames.isEmpty();
        }

    }

}

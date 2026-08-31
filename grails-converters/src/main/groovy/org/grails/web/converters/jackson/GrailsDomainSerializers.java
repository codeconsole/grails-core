/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.web.converters.jackson;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.fasterxml.jackson.annotation.JsonFormat;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.ser.Serializers;

import grails.core.support.proxy.ProxyHandler;
import org.grails.core.exceptions.GrailsConfigurationException;
import org.grails.datastore.mapping.model.MappingContext;
import org.grails.datastore.mapping.model.PersistentEntity;

/**
 * Supplies a serializer for a mapped domain type, resolved the first time that type is written.
 *
 * <p>The mapper is built early -- anything injecting Boot's {@code JsonMapper} pulls Jackson's
 * auto-configuration into the bean graph ahead of GORM -- and reading the mapping context at that
 * point fails. Resolving per type on first use also means domain classes registered after the
 * mapper was built are still serialized with their persistent metadata.</p>
 *
 * @since 8.0
 */
final class GrailsDomainSerializers implements Serializers {

    private final Supplier<MappingContext> mappingContext;
    private final Predicate<Class<?>> domainArtefact;
    private final ProxyHandler proxyHandler;
    private final Supplier<Boolean> includeVersion;
    private final Supplier<Boolean> includeClass;
    private final Map<Class<?>, ValueSerializer<?>> resolved = new ConcurrentHashMap<>();

    GrailsDomainSerializers(Supplier<MappingContext> mappingContext, Predicate<Class<?>> domainArtefact,
            ProxyHandler proxyHandler, Supplier<Boolean> includeVersion, Supplier<Boolean> includeClass) {
        this.mappingContext = mappingContext;
        this.domainArtefact = domainArtefact;
        this.proxyHandler = proxyHandler;
        this.includeVersion = includeVersion;
        this.includeClass = includeClass;
    }

    @Override
    public ValueSerializer<?> findSerializer(SerializationConfig config, JavaType type,
            BeanDescription.Supplier beanDescription, JsonFormat.Value formatOverrides) {
        Class<?> rawType = type.getRawClass();
        ValueSerializer<?> serializer = this.resolved.get(rawType);
        if (serializer != null) {
            return serializer;
        }
        serializer = domainSerializer(rawType);
        if (serializer == null) {
            if (!mappingContextReady() && this.domainArtefact.test(rawType)) {
                // A domain class asked for before GORM is ready. Returning null here would let
                // Jackson select and cache its ordinary bean serializer for this type, and that
                // choice would survive GORM starting, so the class would serialize with the wrong
                // shape for the life of the mapper. Hand back a serializer of our own instead and
                // resolve the metadata when it is actually written.
                serializer = new DeferredDomainSerializer(this);
            }
            else {
                // Genuinely not a mapped type; Jackson's own choice is correct and may be cached.
                return null;
            }
        }
        this.resolved.put(rawType, serializer);
        return serializer;
    }

    /**
     * @return a serializer bound to the type's persistent metadata, or null if the metadata is not
     * available -- either because the type is not mapped or because GORM has not initialized
     */
    ValueSerializer<?> domainSerializer(Class<?> type) {
        PersistentEntity entity = persistentEntity(type);
        if (entity == null) {
            return null;
        }
        return new GrailsDomainJsonSerializer(entity, this.proxyHandler,
                Boolean.TRUE.equals(this.includeVersion.get()), Boolean.TRUE.equals(this.includeClass.get()));
    }

    private boolean mappingContextReady() {
        try {
            return this.mappingContext.get() != null;
        }
        catch (GrailsConfigurationException ignored) {
            return false;
        }
    }

    private PersistentEntity persistentEntity(Class<?> type) {
        try {
            MappingContext context = this.mappingContext.get();
            if (context == null) {
                return null;
            }
            // Walks superclasses so that a proxy, which subclasses its domain class, is serialized
            // with that class's metadata rather than falling through to bean serialization.
            for (Class<?> candidate = type; candidate != null && candidate != Object.class;
                    candidate = candidate.getSuperclass()) {
                PersistentEntity entity = context.getPersistentEntity(candidate.getName());
                if (entity != null) {
                    return entity;
                }
            }
            return null;
        }
        catch (GrailsConfigurationException ignored) {
            // Raised only when GORM's metadata is read before it has initialized. Any other
            // failure is a real mapping defect and must surface rather than quietly falling back
            // to ordinary bean serialization, which could expose unmapped properties.
            return null;
        }
    }

    /**
     * Stands in for a domain class whose metadata was not readable when Jackson asked for a
     * serializer, and binds to that metadata the first time an instance is written.
     */
    private static final class DeferredDomainSerializer extends ValueSerializer<Object> {

        private final GrailsDomainSerializers serializers;

        private volatile ValueSerializer<Object> delegate;

        private DeferredDomainSerializer(GrailsDomainSerializers serializers) {
            this.serializers = serializers;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void serialize(Object value, JsonGenerator generator, SerializationContext context) {
            ValueSerializer<Object> bound = this.delegate;
            if (bound == null) {
                bound = (ValueSerializer<Object>) this.serializers.domainSerializer(value.getClass());
                if (bound == null) {
                    throw new IllegalStateException("Cannot serialize [" + value.getClass().getName() +
                            "]: it is a domain class, but GORM has not made its mapping available. " +
                            "Writing it before GORM has initialized would produce a different shape " +
                            "from every later response.");
                }
                this.delegate = bound;
            }
            bound.serialize(value, generator, context);
        }
    }
}

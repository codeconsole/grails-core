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
import java.util.function.Supplier;

import com.fasterxml.jackson.annotation.JsonFormat;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.ser.Serializers;

import grails.core.support.proxy.ProxyHandler;
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
    private final ProxyHandler proxyHandler;
    private final Supplier<Boolean> includeVersion;
    private final Supplier<Boolean> includeClass;
    private final Map<Class<?>, ValueSerializer<?>> resolved = new ConcurrentHashMap<>();

    GrailsDomainSerializers(Supplier<MappingContext> mappingContext, ProxyHandler proxyHandler,
            Supplier<Boolean> includeVersion, Supplier<Boolean> includeClass) {
        this.mappingContext = mappingContext;
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
        PersistentEntity entity = persistentEntity(rawType);
        if (entity == null) {
            // Not a domain class, or GORM is not up yet; let Jackson pick a serializer. Nothing is
            // cached in that case, so a type resolved before GORM started is reconsidered later.
            return null;
        }
        serializer = new GrailsDomainJsonSerializer(entity, this.proxyHandler,
                Boolean.TRUE.equals(this.includeVersion.get()), Boolean.TRUE.equals(this.includeClass.get()));
        this.resolved.put(rawType, serializer);
        return serializer;
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
        catch (RuntimeException ignored) {
            // GORM raises when its metadata is read too early; treat the type as unmapped.
            return null;
        }
    }
}

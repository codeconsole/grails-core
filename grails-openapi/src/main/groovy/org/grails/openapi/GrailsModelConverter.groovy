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
package org.grails.openapi

import java.lang.reflect.Method
import java.lang.reflect.Type

import groovy.transform.CompileStatic

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.type.TypeFactory
import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.core.util.PrimitiveType
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import org.codehaus.groovy.runtime.InvokerHelper
import org.springframework.beans.BeanUtils
import org.springframework.validation.Errors
import org.springframework.validation.Validator

import grails.gorm.validation.Constrained
import grails.gorm.validation.ConstrainedEntity
import grails.gorm.validation.ConstrainedProperty
import grails.validation.Validateable
import grails.web.databinding.DataBindingUtils
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.PersistentProperty
import org.grails.datastore.mapping.model.types.Association
import org.grails.datastore.mapping.model.types.Embedded
import org.grails.datastore.mapping.model.types.EmbeddedCollection
import org.grails.datastore.mapping.model.types.ToMany

/**
 * Teaches swagger-core what a Groovy class, a command object and a GORM entity look like, so the
 * schema of such a type is right wherever it is resolved: as a resource, as a command object, or
 * as the implementation an annotation names.
 *
 * <ul>
 *   <li>The {@code metaClass} every Groovy object has, and the {@code errors} a validateable type
 *   has, are not properties of the resource and are left out.</li>
 *   <li>The declared constraints become the matching schema keywords.</li>
 *   <li>The identifier and version of an entity are read only, because the server assigns them.</li>
 *   <li>An association to another entity is described by the identifier that Grails renders and
 *   binds for it, rather than by the whole associated resource.</li>
 * </ul>
 *
 * <p>The converter is registered with swagger-core once and applies to every type it resolves.
 * The GORM metadata it uses is supplied by whatever is resolving at the time, through
 * {@link #withMappingContexts}.</p>
 */
@CompileStatic
class GrailsModelConverter implements ModelConverter {

    static final GrailsModelConverter INSTANCE = new GrailsModelConverter()

    private static final String EMAIL_FORMAT = 'email'
    private static final String URI_FORMAT = 'uri'
    private static final String INT64_FORMAT = 'int64'
    private static final String REFERENCE_PREFIX = '#/components/schemas/'

    private static final ThreadLocal<Collection<MappingContext>> MAPPING_CONTEXTS = new ThreadLocal<>()

    private static final ThreadLocal<Boolean> INCLUDE_VERSION = new ThreadLocal<>()

    private static final String DATABINDING_WHITELIST = '$defaultDatabindingWhiteList'

    private static final ThreadLocal<SchemaNames> SCHEMA_NAMES = new ThreadLocal<>()

    private static final ThreadLocal<Deque<Class<?>>> RESOLVING = ThreadLocal.<Deque<Class<?>>> withInitial {
        (Deque<Class<?>>) new ArrayDeque<Class<?>>()
    }

    /**
     * Registers the converter with both of swagger-core's converter lists, once.
     */
    static synchronized void register() {
        for (boolean openapi31 : [false, true]) {
            ModelConverters converters = ModelConverters.getInstance(openapi31)
            if (!converters.converters.any { it instanceof GrailsModelConverter }) {
                converters.addConverter(INSTANCE)
            }
        }
    }

    /**
     * Resolves with the entities of the given mapping contexts known, so their constraints,
     * identifiers and associations are described.
     */
    static <T> T withMappingContexts(Collection<MappingContext> mappingContexts, Closure<T> work) {
        withMappingContexts(mappingContexts, false, work)
    }

    /**
     * @param includeVersion whether Grails renders the version of an entity, which it does not
     * unless {@code grails.converters.domain.include.version} is set
     */
    static <T> T withMappingContexts(Collection<MappingContext> mappingContexts, boolean includeVersion, Closure<T> work) {
        Collection<MappingContext> previous = MAPPING_CONTEXTS.get()
        Boolean previousIncludeVersion = INCLUDE_VERSION.get()
        MAPPING_CONTEXTS.set(mappingContexts)
        INCLUDE_VERSION.set(includeVersion)
        try {
            return work.call()
        }
        finally {
            MAPPING_CONTEXTS.set(previous)
            INCLUDE_VERSION.set(previousIncludeVersion)
        }
    }

    /**
     * Resolves with every class named apart from the others sharing its name, through the names
     * of the document being described.
     */
    static <T> T withSchemaNames(SchemaNames names, Closure<T> work) {
        SchemaNames previous = SCHEMA_NAMES.get()
        SCHEMA_NAMES.set(names)
        try {
            return work.call()
        }
        finally {
            SCHEMA_NAMES.set(previous)
        }
    }

    /**
     * @return the entity mapped for the type, if a mapping context in scope maps it
     */
    static PersistentEntity entityFor(Class<?> type) {
        if (type == null) {
            return null
        }
        for (MappingContext context : (MAPPING_CONTEXTS.get() ?: Collections.<MappingContext> emptyList())) {
            PersistentEntity entity = context.getPersistentEntity(type.name)
            if (entity != null) {
                return entity
            }
        }
        null
    }

    @Override
    Schema resolve(AnnotatedType annotatedType, ModelConverterContext context, Iterator<ModelConverter> chain) {
        Class<?> type = rawClass(annotatedType.type)

        if (annotatedType.propertyName != null) {
            if (type != null && (MetaClass.isAssignableFrom(type) || Errors.isAssignableFrom(type))) {
                return null
            }
            Schema reference = associationReference(annotatedType.propertyName)
            if (reference != null) {
                return reference
            }
        }

        if (!chain.hasNext()) {
            return null
        }
        if (type == null) {
            return chain.next().resolve(annotatedType, context, chain)
        }
        nameApart(annotatedType)

        Deque<Class<?>> resolving = RESOLVING.get()
        resolving.push(type)
        Schema resolved
        try {
            resolved = chain.next().resolve(annotatedType, context, chain)
        }
        finally {
            resolving.pop()
        }

        Schema model = modelOf(resolved, context)
        if (model?.properties != null) {
            describe(type, model)
        }
        resolved
    }

    /**
     * Names a class that shares its name with another apart from it, before swagger-core names the
     * class and refers to it.
     */
    private static void nameApart(AnnotatedType annotatedType) {
        SchemaNames names = SCHEMA_NAMES.get()
        if (names == null || annotatedType.name) {
            return
        }
        JavaType type = javaType(annotatedType.type)
        if (type == null || !SchemaNames.isNamed(type)) {
            return
        }
        String natural = SchemaNames.naturalName(annotatedType, type)
        String name = names.claim(type, natural)
        if (name != natural) {
            annotatedType.setName(name)
        }
    }

    /**
     * A property the enclosing entity maps as an association to another entity is described by
     * the associated identifier, which is what Grails renders for it and accepts to bind it.
     */
    private static Schema associationReference(String propertyName) {
        Class<?> owner = RESOLVING.get().peek()
        PersistentEntity entity = entityFor(owner)
        if (entity == null) {
            return null
        }
        PersistentProperty property = entity.getPropertyByName(propertyName)
        if (!(property instanceof Association) || property instanceof Embedded || property instanceof EmbeddedCollection) {
            return null
        }
        Association association = (Association) property
        PersistentEntity associated = association.associatedEntity
        if (associated == null) {
            return null
        }

        Schema reference = new ObjectSchema()
                .addProperty(associated.identity?.name ?: 'id', identifierSchema(associated))
                .description("The identifier of the associated ${associated.javaClass.simpleName}".toString())
        reference.addRequiredItem(associated.identity?.name ?: 'id')

        association instanceof ToMany
                ? new ArraySchema().items(reference)
                : reference
    }

    /**
     * The schema of the identifier an entity is addressed and associated by: an integer of the
     * width it is declared with, a UUID, or a string for any other type.
     */
    static Schema identifierSchema(PersistentEntity entity) {
        Class<?> identifierType = entity.identity?.type
        Schema schema = identifierType != null ? PrimitiveType.createProperty(identifierType) : null
        schema ?: new StringSchema()
    }

    private static Schema modelOf(Schema resolved, ModelConverterContext context) {
        if (resolved?.$ref) {
            String name = resolved.$ref.startsWith(REFERENCE_PREFIX)
                    ? resolved.$ref.substring(REFERENCE_PREFIX.length())
                    : resolved.$ref
            return context.definedModels?.get(name)
        }
        resolved
    }

    private static void describe(Class<?> type, Schema model) {
        PersistentEntity entity = entityFor(type)
        if (entity != null) {
            describeEntity(entity, model)
        }
        else if (Validateable.isAssignableFrom(type)) {
            applyConstraints(model, writable(type, validateableConstraints(type), model), null)
        }
        if (entity != null || Validateable.isAssignableFrom(type) || declaresBindableProperties(type)) {
            markUnbound(type, model)
        }
    }

    /**
     * A property data binding does not bind - one the server assigns, such as {@code dateCreated},
     * one constrained {@code bindable: false}, or a transient - is not something a client sends,
     * so it is read only. A property renamed in the document is left as it is.
     */
    private static void markUnbound(Class<?> type, Schema model) {
        List<String> bindable = DataBindingUtils.getBindingIncludeListForType(type)
        if (bindable == null) {
            return
        }
        ((Map<String, Schema>) model.properties).each { String name, Schema property ->
            if (!(name in bindable) && BeanUtils.getPropertyDescriptor(type, name) != null) {
                property.setReadOnly(true)
            }
        }
    }

    /**
     * Grails declares the properties it binds on a command object an action takes.
     */
    private static boolean declaresBindableProperties(Class<?> type) {
        try {
            type.getField(DATABINDING_WHITELIST)
            return true
        }
        catch (NoSuchFieldException | SecurityException ignored) {
            return false
        }
    }

    /**
     * A command property that can only be read - one with a getter and nothing to bind it to -
     * is not something a client sends, so it is read only and its constraints are not required
     * of a request.
     */
    private static Map<String, Constrained> writable(Class<?> type, Map<String, Constrained> constraints, Schema model) {
        Map<String, Constrained> result = [:]
        constraints.each { String name, Constrained constrained ->
            if (isWritable(type, name)) {
                result[name] = constrained
            }
            else {
                ((Schema) model.properties[name])?.setReadOnly(true)
            }
        }
        result
    }

    private static boolean isWritable(Class<?> type, String name) {
        String setter = 'set' + name.capitalize()
        type.methods.any { Method method -> method.name == setter && method.parameterCount == 1 }
    }

    /**
     * Grails renders an entity as its identifier, its persistent properties and, where it is
     * configured to, its version. A transient, a getter with nothing persisted behind it, and the
     * accessor GORM adds for the foreign key of an association are not part of it.
     */
    private static void describeEntity(PersistentEntity entity, Schema model) {
        Map<String, Schema> properties = model.properties
        String identityName = entity.identity?.name
        String versionName = entity.versioned ? entity.version?.name : null
        boolean includeVersion = INCLUDE_VERSION.get()

        properties.keySet().removeAll { String name ->
            name != identityName && (name == versionName || entity.getPropertyByName(name) == null)
        }

        markReadOnly(model, identityName)
        if (includeVersion) {
            markReadOnly(model, versionName)
        }

        applyConstraints(model, entityConstraints(entity), versionName)
    }

    /**
     * GORM adds the version through a transform swagger-core does not see, so a property the
     * server assigns is added where it is missing rather than only flagged.
     */
    private static void markReadOnly(Schema model, String propertyName) {
        if (!propertyName) {
            return
        }
        Schema property = (Schema) model.properties[propertyName]
        if (property == null) {
            property = new IntegerSchema().format(INT64_FORMAT)
            model.addProperty(propertyName, property)
        }
        property.setReadOnly(true)
    }

    private static Map<String, Constrained> entityConstraints(PersistentEntity entity) {
        Validator validator = entity.mappingContext?.getEntityValidator(entity)
        if (validator instanceof ConstrainedEntity) {
            Map<String, ConstrainedProperty> declared = ((ConstrainedEntity) validator).constrainedProperties
            return declared ? new LinkedHashMap<String, Constrained>(declared) : Collections.<String, Constrained> emptyMap()
        }
        Collections.<String, Constrained> emptyMap()
    }

    private static Map<String, Constrained> validateableConstraints(Class<?> type) {
        Map<String, Constrained> declared = (Map<String, Constrained>) InvokerHelper.invokeStaticMethod(
                type, 'getConstraintsMap', null)
        declared ?: Collections.<String, Constrained> emptyMap()
    }

    private static void applyConstraints(Schema model, Map<String, Constrained> constraints, String versionName) {
        Map<String, Schema> properties = model.properties
        constraints.each { String name, Constrained constrained ->
            Schema property = (Schema) properties[name]
            if (property == null) {
                return
            }
            applyConstraints(property, constrained)
            if (!constrained.nullable && name != versionName && !model.required?.contains(name)) {
                model.addRequiredItem(name)
            }
        }
    }

    private static void applyConstraints(Schema schema, Constrained constrained) {
        if (constrained.inList && !schema.enum) {
            Schema<Object> target = (Schema<Object>) schema
            constrained.inList.each { target.addEnumItemObject(it) }
        }

        // The string constraints throw rather than return null when read from a property of
        // another type, so they are consulted only where the property is a string. The test is
        // the one the constraint itself applies - the property type, not the schema, which
        // describes a date, a UUID and a byte array as a string too.
        Class<?> type = constrained instanceof ConstrainedProperty ? ((ConstrainedProperty) constrained).propertyType : null
        if (type != null && CharSequence.isAssignableFrom(type)) {
            applyStringConstraints(schema, constrained)
        }
        else if (type != null && (Number.isAssignableFrom(type) || type.primitive)) {
            applyNumericConstraints(schema, constrained)
        }
    }

    private static void applyStringConstraints(Schema schema, Constrained constrained) {
        if (constrained.matches) {
            schema.setPattern(constrained.matches)
        }
        if (constrained.email) {
            schema.setFormat(EMAIL_FORMAT)
        }
        else if (constrained.url) {
            schema.setFormat(URI_FORMAT)
        }

        // A zero bound is falsy in Groovy, so the presence of each value is tested rather than
        // its truth: maxSize: 0 and min: 0 are real constraints.
        Integer maxSize = constrained.maxSize != null ? constrained.maxSize
                : (constrained.size != null ? (Integer) constrained.size.to : null)
        Integer minSize = constrained.minSize != null ? constrained.minSize
                : (constrained.size != null ? (Integer) constrained.size.from : null)
        if (maxSize != null) {
            schema.setMaxLength(maxSize)
        }
        if (minSize != null) {
            schema.setMinLength(minSize)
        }
    }

    private static void applyNumericConstraints(Schema schema, Constrained constrained) {
        Comparable min = constrained.min != null ? constrained.min
                : (constrained.range != null ? (Comparable) constrained.range.from : null)
        Comparable max = constrained.max != null ? constrained.max
                : (constrained.range != null ? (Comparable) constrained.range.to : null)
        if (min instanceof Number) {
            schema.setMinimum(toBigDecimal((Number) min))
        }
        if (max instanceof Number) {
            schema.setMaximum(toBigDecimal((Number) max))
        }
    }

    private static BigDecimal toBigDecimal(Number value) {
        value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(value.toString())
    }

    private static Class<?> rawClass(Type type) {
        javaType(type)?.rawClass
    }

    private static JavaType javaType(Type type) {
        if (type == null) {
            return null
        }
        try {
            return TypeFactory.defaultInstance().constructType(type)
        }
        catch (IllegalArgumentException ignored) {
            return null
        }
    }
}

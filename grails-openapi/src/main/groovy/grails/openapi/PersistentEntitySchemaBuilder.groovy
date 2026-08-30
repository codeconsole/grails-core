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
package grails.openapi

import java.time.temporal.Temporal

import groovy.transform.CompileStatic

import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.BooleanSchema
import io.swagger.v3.oas.models.media.DateTimeSchema
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.NumberSchema
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import org.springframework.validation.Validator

import grails.gorm.validation.Constrained
import grails.gorm.validation.ConstrainedEntity
import grails.gorm.validation.ConstrainedProperty
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.PersistentProperty
import org.grails.datastore.mapping.model.types.Association
import org.grails.datastore.mapping.model.types.ToMany

/**
 * Builds OpenAPI schemas from the GORM mapping model, so that a Grails domain class is described in
 * the generated document the way an annotated Java type would be.
 *
 * <p>Two schemas are produced per domain class: a response schema carrying every persistent
 * property, and a request schema that omits the identifier and version, which a client does not
 * supply.</p>
 *
 * <p>Where the domain class declares constraints they are carried across - {@code maxSize} becomes
 * {@code maxLength}, {@code inList} becomes an enumeration, {@code matches} becomes a pattern, and a
 * property that is not nullable is reported as required.</p>
 *
 * @author Scott Murphy Heiberg
 * @since 8.0
 */
@CompileStatic
class PersistentEntitySchemaBuilder {

    static final String REQUEST_SCHEMA_SUFFIX = 'Request'

    private static final String EMAIL_FORMAT = 'email'
    private static final String URI_FORMAT = 'uri'

    /**
     * @param entity the GORM entity to describe
     * @param mappingContext supplies the entity validator that carries the declared constraints
     * @return an object schema whose properties mirror the entity's persistent properties
     */
    Schema<?> build(PersistentEntity entity, MappingContext mappingContext = null) {
        buildSchema(entity, constraintsFor(entity, mappingContext), true)
    }

    /**
     * @return a schema for request payloads, omitting the identifier and version
     */
    Schema<?> buildRequest(PersistentEntity entity, MappingContext mappingContext = null) {
        buildSchema(entity, constraintsFor(entity, mappingContext), false)
    }

    private Schema<?> buildSchema(PersistentEntity entity, Map<String, ConstrainedProperty> constraints,
                                  boolean includeGeneratedProperties) {
        ObjectSchema schema = new ObjectSchema()

        PersistentProperty identity = entity.identity
        if (includeGeneratedProperties && identity) {
            schema.addProperty(identity.name, schemaFor(identity, null))
        }

        PersistentProperty version = entity.version
        String versionName = entity.versioned ? version?.name : null

        for (PersistentProperty property : entity.persistentProperties) {
            if (!includeGeneratedProperties && property.name == versionName) {
                continue
            }

            Constrained constrained = constraints[property.name]
            schema.addProperty(property.name, schemaFor(property, constrained))

            boolean generated = property.name == versionName
            if (constrained != null && !constrained.nullable && !generated) {
                schema.addRequiredItem(property.name)
            }
        }

        schema
    }

    /**
     * The declared constraints, when the entity has a validator that exposes them. An entity mapped
     * without constraint evaluation yields an empty map rather than a misleading default.
     */
    private static Map<String, ConstrainedProperty> constraintsFor(PersistentEntity entity, MappingContext mappingContext) {
        if (mappingContext == null) {
            return Collections.<String, ConstrainedProperty> emptyMap()
        }
        Validator validator = mappingContext.getEntityValidator(entity)
        validator instanceof ConstrainedEntity
                ? ((ConstrainedEntity) validator).constrainedProperties ?: Collections.<String, ConstrainedProperty> emptyMap()
                : Collections.<String, ConstrainedProperty> emptyMap()
    }

    /**
     * The schema name a domain class is registered and referenced under.
     */
    static String schemaName(PersistentEntity entity) {
        entity.javaClass.simpleName
    }

    static String requestSchemaName(PersistentEntity entity) {
        schemaName(entity) + REQUEST_SCHEMA_SUFFIX
    }

    static String referencePath(String schemaName) {
        "#/components/schemas/${schemaName}".toString()
    }

    private static Schema<?> schemaFor(PersistentProperty property, Constrained constrained) {
        if (property instanceof Association) {
            Association association = (Association) property
            PersistentEntity associated = association.associatedEntity
            if (associated == null) {
                return new ObjectSchema()
            }
            Schema<?> reference = new Schema<>().$ref(referencePath(schemaName(associated)))
            return association instanceof ToMany ? new ArraySchema().items(reference) : reference
        }
        applyConstraints(schemaForType(property.type), constrained)
    }

    private static Schema<?> applyConstraints(Schema<?> schema, Constrained constrained) {
        if (constrained == null) {
            return schema
        }

        if (constrained.inList) {
            Schema<Object> enumTarget = (Schema<Object>) schema
            constrained.inList.each { enumTarget.addEnumItemObject(it) }
        }

        // The String-only constraints throw rather than return null when read from a property of
        // another type, so they are only consulted for a string schema.
        if (schema instanceof StringSchema) {
            applyStringConstraints(schema, constrained)
        }
        else {
            applyNumericConstraints(schema, constrained)
        }

        schema
    }

    private static void applyStringConstraints(Schema<?> schema, Constrained constrained) {
        if (constrained.matches) {
            schema.setPattern(constrained.matches)
        }
        if (constrained.email) {
            schema.setFormat(EMAIL_FORMAT)
        }
        else if (constrained.url) {
            schema.setFormat(URI_FORMAT)
        }

        Integer maxSize = constrained.maxSize ?: (constrained.size ? (Integer) constrained.size.to : null)
        Integer minSize = constrained.minSize ?: (constrained.size ? (Integer) constrained.size.from : null)
        if (maxSize != null) {
            schema.setMaxLength(maxSize)
        }
        if (minSize != null) {
            schema.setMinLength(minSize)
        }
    }

    private static void applyNumericConstraints(Schema<?> schema, Constrained constrained) {
        Comparable min = constrained.min ?: (constrained.range ? (Comparable) constrained.range.from : null)
        Comparable max = constrained.max ?: (constrained.range ? (Comparable) constrained.range.to : null)
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

    private static Schema<?> schemaForType(Class<?> type) {
        if (type == null) {
            return new ObjectSchema()
        }
        if (CharSequence.isAssignableFrom(type) || type.enum) {
            return new StringSchema()
        }
        if (type in [boolean, Boolean]) {
            return new BooleanSchema()
        }
        if (type in [long, Long, int, Integer, short, Short, byte, Byte] || BigInteger.isAssignableFrom(type)) {
            return new IntegerSchema().format(type in [long, Long] ? 'int64' : 'int32')
        }
        if (type in [double, Double, float, Float] || Number.isAssignableFrom(type)) {
            return new NumberSchema()
        }
        if (Date.isAssignableFrom(type) || Temporal.isAssignableFrom(type) || Calendar.isAssignableFrom(type)) {
            return new DateTimeSchema()
        }
        if (Collection.isAssignableFrom(type)) {
            return new ArraySchema().items(new ObjectSchema())
        }
        new ObjectSchema()
    }
}

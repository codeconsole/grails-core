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

import groovy.transform.CompileStatic

import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.core.converter.ResolvedSchema
import io.swagger.v3.oas.models.media.IntegerSchema
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

/**
 * Builds OpenAPI schemas for Grails domain classes.
 *
 * <p>The type is resolved through swagger-core, so a {@code @Schema} annotation on a domain class or
 * one of its properties is honored and associated classes are resolved without this class walking
 * them. The declared GORM constraints are then applied over that result, which swagger-core cannot
 * see: {@code nullable: false} becomes a required member, {@code maxSize} becomes {@code maxLength},
 * {@code inList} becomes an enumeration, {@code matches} becomes a pattern, and {@code email} or
 * {@code url} becomes a format.</p>
 *
 * <p>The identifier and version are marked {@code readOnly}, because the server assigns them.</p>
 *
 * @author Scott Murphy Heiberg
 * @since 8.0
 */
@CompileStatic
class PersistentEntitySchemaBuilder {

    private static final String EMAIL_FORMAT = 'email'
    private static final String URI_FORMAT = 'uri'
    private static final String INT64_FORMAT = 'int64'

    /**
     * @param entity the GORM entity to describe
     * @param mappingContext supplies the entity validator that carries the declared constraints
     * @return every schema the entity resolves to, keyed by schema name, including the classes it
     * is associated with
     */
    Map<String, Schema> build(PersistentEntity entity, MappingContext mappingContext = null) {
        ResolvedSchema resolved = ModelConverters.instance.readAllAsResolvedSchema(entity.javaClass)
        Map<String, Schema> schemas = [:]
        if (resolved?.referencedSchemas) {
            schemas.putAll(resolved.referencedSchemas)
        }
        else if (resolved?.schema) {
            schemas[schemaName(entity)] = resolved.schema
        }

        schemas.each { String name, Schema schema ->
            PersistentEntity described = entityNamed(name, mappingContext)
            if (described != null) {
                applyGormMetadata(schema, described, mappingContext)
            }
        }
        schemas
    }

    /**
     * The schema name a domain class is registered and referenced under.
     */
    static String schemaName(PersistentEntity entity) {
        entity.javaClass.simpleName
    }

    static String referencePath(String schemaName) {
        "#/components/schemas/${schemaName}".toString()
    }

    private static PersistentEntity entityNamed(String name, MappingContext mappingContext) {
        mappingContext?.persistentEntities?.find { PersistentEntity candidate -> schemaName(candidate) == name }
    }

    /**
     * Applies what GORM knows and swagger-core does not.
     */
    private static void applyGormMetadata(Schema schema, PersistentEntity entity, MappingContext mappingContext) {
        Map<String, Schema> properties = schema.properties
        if (properties == null) {
            return
        }

        // swagger-core surfaces the foreign key accessor GORM adds alongside a to-one association;
        // the association itself already describes the relationship.
        for (Association association : entity.associations) {
            properties.remove("${association.name}Id".toString())
        }

        markReadOnly(properties, entity.identity?.name, schema)
        if (entity.versioned) {
            markReadOnly(properties, entity.version?.name, schema)
        }

        Map<String, ConstrainedProperty> constraints = constraintsFor(entity, mappingContext)
        String versionName = entity.versioned ? entity.version?.name : null

        for (PersistentProperty property : entity.persistentProperties) {
            Constrained constrained = constraints[property.name]
            Schema propertySchema = properties[property.name]
            if (constrained == null || propertySchema == null) {
                continue
            }

            applyConstraints(propertySchema, constrained)

            if (!constrained.nullable && property.name != versionName && !isRequired(schema, property.name)) {
                schema.addRequiredItem(property.name)
            }
        }
    }

    /**
     * A property the server assigns. GORM adds the version through a transform that swagger-core
     * does not see, so it is described here rather than only flagged.
     */
    private static void markReadOnly(Map<String, Schema> properties, String propertyName, Schema owner) {
        if (!propertyName) {
            return
        }
        Schema property = properties[propertyName]
        if (property == null) {
            property = new IntegerSchema().format(INT64_FORMAT)
            owner.addProperty(propertyName, property)
        }
        property.setReadOnly(true)
    }

    private static boolean isRequired(Schema schema, String propertyName) {
        schema.required?.contains(propertyName)
    }

    private static Map<String, ConstrainedProperty> constraintsFor(PersistentEntity entity, MappingContext mappingContext) {
        if (mappingContext == null) {
            return Collections.<String, ConstrainedProperty> emptyMap()
        }
        Validator validator = mappingContext.getEntityValidator(entity)
        validator instanceof ConstrainedEntity
                ? ((ConstrainedEntity) validator).constrainedProperties ?: Collections.<String, ConstrainedProperty> emptyMap()
                : Collections.<String, ConstrainedProperty> emptyMap()
    }

    private static void applyConstraints(Schema schema, Constrained constrained) {
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

        Integer maxSize = constrained.maxSize ?: (constrained.size ? (Integer) constrained.size.to : null)
        Integer minSize = constrained.minSize ?: (constrained.size ? (Integer) constrained.size.from : null)
        if (maxSize != null) {
            schema.setMaxLength(maxSize)
        }
        if (minSize != null) {
            schema.setMinLength(minSize)
        }
    }

    private static void applyNumericConstraints(Schema schema, Constrained constrained) {
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
}

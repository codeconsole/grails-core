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

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.validation.Errors;

import grails.core.GrailsApplication;
import grails.core.support.proxy.DefaultProxyHandler;
import grails.core.support.proxy.ProxyHandler;
import org.grails.datastore.mapping.model.PersistentEntity;

/**
 * Adds Grails-specific serializers to Spring Boot's configured JSON mapper.
 *
 * @since 8.0
 */
public final class GrailsJsonMapperCustomizer implements JsonMapperBuilderCustomizer {

    /** Writer attribute holding the property names to include, as a List or a Map keyed by type. */
    public static final String INCLUDES_ATTRIBUTE = GrailsJsonMapperCustomizer.class.getName() + ".includes";

    /** Writer attribute holding the property names to exclude, as a List or a Map keyed by type. */
    public static final String EXCLUDES_ATTRIBUTE = GrailsJsonMapperCustomizer.class.getName() + ".excludes";

    private final GrailsApplication grailsApplication;
    private final ProxyHandler proxyHandler;

    public GrailsJsonMapperCustomizer() {
        this(null, new DefaultProxyHandler());
    }

    public GrailsJsonMapperCustomizer(GrailsApplication grailsApplication) {
        this(grailsApplication, new DefaultProxyHandler());
    }

    public GrailsJsonMapperCustomizer(GrailsApplication grailsApplication, ProxyHandler proxyHandler) {
        this.grailsApplication = grailsApplication;
        this.proxyHandler = proxyHandler;
    }

    @Override
    public void customize(JsonMapper.Builder builder) {
        SimpleModule module = new SimpleModule("grails-json");
        // Resolved per write rather than captured here: the mapper is built once, but the
        // application context that resolves messages is not necessarily available at that point.
        module.addSerializer(Errors.class, new SpringErrorsJsonSerializer(
                () -> this.grailsApplication == null ? null : this.grailsApplication.getMainContext()));
        if (grailsApplication != null && grailsApplication.getMappingContext() != null) {
            boolean defaultIncludeVersion = grailsApplication.getConfig()
                    .getProperty("grails.converters.domain.include.version", Boolean.class, false);
            boolean includeVersion = grailsApplication.getConfig()
                    .getProperty("grails.converters.json.domain.include.version", Boolean.class, defaultIncludeVersion);
            boolean defaultIncludeClass = grailsApplication.getConfig()
                    .getProperty("grails.converters.domain.include.class", Boolean.class, false);
            boolean includeClass = grailsApplication.getConfig()
                    .getProperty("grails.converters.json.domain.include.class", Boolean.class, defaultIncludeClass);
            for (PersistentEntity entity : grailsApplication.getMappingContext().getPersistentEntities()) {
                module.addSerializer(entity.getJavaClass(),
                        new GrailsDomainJsonSerializer(entity, proxyHandler, includeVersion, includeClass));
            }
        }
        builder.addModule(module);
    }
}

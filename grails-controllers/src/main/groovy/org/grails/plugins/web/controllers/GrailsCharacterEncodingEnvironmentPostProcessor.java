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
package org.grails.plugins.web.controllers;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;

import grails.config.Settings;

/**
 * Translates the deprecated {@code grails.filter.encoding} and {@code grails.filter.forceEncoding} into
 * {@code spring.servlet.encoding.charset} and {@code spring.servlet.encoding.force}, which configure the
 * character encoding filter Spring Boot registers. Grails no longer registers an encoding filter of its own.
 *
 * <p>A translated value is contributed as a Spring Boot default property (lowest precedence), and only when the
 * application does not set the Spring Boot property itself.
 *
 * @since 9.0
 * @deprecated only needed while applications move to {@code spring.servlet.encoding}; it will be removed
 * together with the two properties it translates
 */
@Deprecated(since = "9.0", forRemoval = true)
public class GrailsCharacterEncodingEnvironmentPostProcessor implements EnvironmentPostProcessor {

    // Spring Boot initializes logging after the environment post-processors run, and replays what was logged
    // through the DeferredLogFactory it passes in once it has.
    private final Log logger;

    public GrailsCharacterEncodingEnvironmentPostProcessor() {
        this.logger = LogFactory.getLog(GrailsCharacterEncodingEnvironmentPostProcessor.class);
    }

    public GrailsCharacterEncodingEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.logger = logFactory.getLog(GrailsCharacterEncodingEnvironmentPostProcessor.class);
    }

    @Override
    @SuppressWarnings("removal")
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var translated = new LinkedHashMap<String, Object>();
        translate(environment, Settings.FILTER_ENCODING, "spring.servlet.encoding.charset", translated);
        translate(environment, Settings.FILTER_FORCE_ENCODING, "spring.servlet.encoding.force", translated);
        if (!translated.isEmpty()) {
            DefaultPropertiesPropertySource.addOrMerge(translated, environment.getPropertySources());
        }
    }

    private void translate(ConfigurableEnvironment environment, String deprecatedProperty,
            String replacement, Map<String, Object> translated) {
        var value = environment.getProperty(deprecatedProperty);
        if (value == null) {
            return;
        }
        // DefaultPropertiesPropertySource.addOrMerge replaces a key, and the application may have set this one as a
        // default property itself
        if (environment.containsProperty(replacement)) {
            logger.warn("'" + deprecatedProperty + "' is deprecated and will be removed. It is ignored, because '" +
                    replacement + "' is set.");
            return;
        }
        logger.warn("'" + deprecatedProperty + "' is deprecated and will be removed. Spring Boot registers the " +
                "character encoding filter now, so use '" + replacement + "=" + value + "' instead.");
        translated.put(replacement, value);
    }
}

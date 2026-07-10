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
package org.grails.plugins.sitemesh3;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

import org.grails.web.util.WebUtils;

/**
 * Contributes the Grails defaults for the SiteMesh 3 configuration keys —
 * layout selection via the {@code layout} meta tag, the
 * {@code /layouts/} decorator prefix and, when configured, the application's
 * default layout — before the application context refreshes.
 *
 * <p>Because these defaults are in the {@link ConfigurableEnvironment} from the
 * start, both the SiteMesh starter's {@code @Value} placeholders and the Grails
 * configuration (which is built from the environment) observe them without any
 * post-hoc reassignment. This replaces the property-source manipulation the
 * plugin previously performed in {@code doWithSpring()}.</p>
 *
 * <p>Each default is contributed only when the application has not set the key
 * itself, and the source is appended with lowest precedence, so application
 * configuration always wins.</p>
 */
public class Sitemesh3EnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "defaultSitemesh3Properties";

    /**
     * A class unique to the SiteMesh 2 module (grails-layout). SiteMesh 3 is a
     * drop-in replacement for it — the two modules must never share a
     * classpath, so co-presence fails startup with a clear message instead of
     * producing an arbitrary winner.
     */
    static final String SITEMESH2_MARKER_CLASS = "org.apache.grails.web.layout.GrailsLayoutViewResolverPostProcessor";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (ClassUtils.isPresent(SITEMESH2_MARKER_CLASS, ClassUtils.getDefaultClassLoader())) {
            throw new IllegalStateException("grails-sitemesh3 is a drop-in replacement for grails-layout " +
                    "(SiteMesh 2) and the two must not be on the classpath together - remove one of the " +
                    "two dependencies.");
        }
        MapPropertySource defaults = getDefaultPropertySource(environment);
        if (!defaults.getSource().isEmpty()) {
            environment.getPropertySources().addLast(defaults);
        }
    }

    /**
     * The SiteMesh 3 defaults not already configured in the given environment.
     * Public because {@code grails.gsp.boot.GspAutoConfiguration} applies the
     * same defaults for plain Spring Boot GSP applications whose contexts are
     * built without {@code SpringApplication} (where no
     * {@code EnvironmentPostProcessor} runs).
     */
    public static MapPropertySource getDefaultPropertySource(ConfigurableEnvironment environment) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("sitemesh.decorator.metaTag", "layout");
        properties.put("sitemesh.decorator.attribute", WebUtils.LAYOUT_ATTRIBUTE);
        properties.put("sitemesh.decorator.prefix", "/layouts/");

        // The SiteMesh 3 specific key wins; fall back to the legacy
        // grails.views.layout.default key so existing apps keep their
        // configured default layout when switching.
        String defaultLayout = environment.getProperty("grails.sitemesh.default.layout");
        if (defaultLayout == null || defaultLayout.isEmpty()) {
            defaultLayout = environment.getProperty("grails.views.layout.default");
        }
        if (defaultLayout != null && !defaultLayout.isEmpty()) {
            properties.put("sitemesh.decorator.default", defaultLayout);
        }

        properties.keySet().removeIf(key -> environment.getProperty(key) != null);
        return new MapPropertySource(PROPERTY_SOURCE_NAME, properties);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}

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
package org.grails.forge.feature.logging;

import jakarta.inject.Singleton;
import org.grails.forge.application.ApplicationType;
import org.grails.forge.application.generator.GeneratorContext;
import org.grails.forge.build.dependencies.Dependency;

/**
 * Opt-in logging feature that omits {@code grails-app/conf/logback-spring.xml}
 * and relies on Spring Boot's zero-config Logback defaults.
 *
 * <p>The default {@link Logback} feature generates a starter
 * {@code logback-spring.xml}. Selecting this feature instead produces an
 * application with no logging configuration file at all: {@code grails-logging}
 * (which brings Spring Boot's logging starter) is still added, so Logback and
 * Spring Boot's own default Logback configuration apply automatically — an
 * {@code INFO} root level and a colorized console pattern. Levels and patterns
 * can then be tuned entirely from {@code application.yml}, including
 * per-environment levels via the Grails {@code environments} block. Because it
 * belongs to the same {@link LoggingFeature} group, selecting it supersedes the
 * default feature.</p>
 */
@Singleton
public class LogbackZeroConfig implements LoggingFeature {

    @Override
    public String getName() {
        return "logback-zero-config";
    }

    @Override
    public String getTitle() {
        return "Zero-config Logback Logging";
    }

    @Override
    public String getDescription() {
        return "Omits grails-app/conf/logback-spring.xml and relies on Spring Boot's default Logback configuration. Logging levels and patterns can be tuned from application.yml, including per-environment levels via the environments block.";
    }

    @Override
    public boolean isVisible() {
        return true;
    }

    @Override
    public void apply(GeneratorContext generatorContext) {
        generatorContext.addDependency(Dependency.builder()
                .groupId("org.apache.grails")
                .artifactId("grails-logging")
                .implementation());
    }

    @Override
    public boolean supports(ApplicationType applicationType) {
        return true;
    }
}

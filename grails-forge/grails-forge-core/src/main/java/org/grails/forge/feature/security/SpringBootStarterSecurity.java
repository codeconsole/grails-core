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
package org.grails.forge.feature.security;

import jakarta.inject.Singleton;

import org.grails.forge.application.generator.GeneratorContext;
import org.grails.forge.build.dependencies.Dependency;
import org.grails.forge.feature.security.template.securityConfig;
import org.grails.forge.feature.view.Scaffolding;
import org.grails.forge.template.RockerTemplate;

/**
 * Secures the application with plain Spring Security: the spring-boot-starter-security
 * dependency, the shared user artifacts and a {@code SecurityConfig} with form login.
 * No Grails security plugins are involved.
 *
 * @since 8.0
 */
@Singleton
public class SpringBootStarterSecurity extends SecurityFeature {

    public SpringBootStarterSecurity(Scaffolding scaffolding) {
        super(scaffolding);
    }

    @Override
    public String getName() {
        return "spring-boot-starter-security";
    }

    @Override
    public String getTitle() {
        return "Spring Boot Starter Security";
    }

    @Override
    public String getDescription() {
        return "Secures the application with a lightweight, plain Spring Security setup that does NOT use any Grails plugins: " +
                "spring-boot-starter-security, a GORM-backed User domain implementing UserDetails, form login and logout, " +
                "a scaffolded user admin, and a bootstrapped admin account with a generated password.";
    }

    @Override
    public String getDocumentation() {
        return "https://docs.spring.io/spring-boot/reference/web/spring-security.html";
    }

    @Override
    public void apply(GeneratorContext generatorContext) {
        generatorContext.addDependency(Dependency.builder()
                .groupId("org.springframework.boot")
                .artifactId("spring-boot-starter-security")
                .implementation());

        applyUserArtifacts(generatorContext);
        generatorContext.addTemplate("securityConfig",
                new RockerTemplate(generatorContext.getSourcePath("/{packagePath}/SecurityConfig"),
                        securityConfig.template(generatorContext.getProject())));
    }
}

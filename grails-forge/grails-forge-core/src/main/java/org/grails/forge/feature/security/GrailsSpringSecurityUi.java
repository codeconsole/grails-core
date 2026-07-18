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

import org.grails.forge.application.Project;
import org.grails.forge.application.generator.GeneratorContext;
import org.grails.forge.build.dependencies.Dependency;
import org.grails.forge.feature.security.template.role;
import org.grails.forge.feature.security.template.securityUiApplicationGroovy;
import org.grails.forge.feature.security.template.userClassic;
import org.grails.forge.feature.security.template.userClassicSpec;
import org.grails.forge.feature.security.template.userRole;
import org.grails.forge.feature.view.Scaffolding;
import org.grails.forge.template.RockerTemplate;

/**
 * Secures the application with the Grails Spring Security Core plugin plus the
 * Spring Security UI plugin's administration screens. The UI plugin manages roles
 * and role assignments, so this flavor generates the classic User/Role/UserRole
 * domain model (instead of the lighter shared user artifacts) and relies on the
 * plugin's own user admin controllers rather than a scaffolded one.
 *
 * @since 8.0
 */
@Singleton
public class GrailsSpringSecurityUi extends SecurityFeature {

    public GrailsSpringSecurityUi(Scaffolding scaffolding) {
        super(scaffolding);
    }

    @Override
    public String getName() {
        return "grails-spring-security-ui";
    }

    @Override
    public String getTitle() {
        return "Grails Spring Security UI Plugin";
    }

    @Override
    public String getDescription() {
        return "Secures the application with the Grails Spring Security Core plugin and adds the Spring Security UI plugin's " +
                "administration screens, rendered through the application's own layout: user and role CRUD, registration and " +
                "forgot-password flows (sending mail requires SMTP configuration), and a classic User/Role/UserRole domain model " +
                "with a bootstrapped ROLE_ADMIN account.";
    }

    @Override
    public String getDocumentation() {
        return "https://grails.apache.org/docs/latest/grails-spring-security-ui/";
    }

    @Override
    public void apply(GeneratorContext generatorContext) {
        generatorContext.addDependency(Dependency.builder()
                .groupId("org.apache.grails")
                .artifactId("grails-spring-security")
                .implementation());
        generatorContext.addDependency(Dependency.builder()
                .groupId("org.apache.grails")
                .artifactId("grails-spring-security-ui")
                .implementation());
        generatorContext.addDependency(Dependency.builder()
                .groupId("org.apache.grails")
                .artifactId("grails-mail")
                .implementation());

        final Project project = generatorContext.getProject();
        generatorContext.addTemplate("securityUiUser",
                new RockerTemplate("grails-app/domain/{packagePath}/User.groovy", userClassic.template(project)));
        generatorContext.addTemplate("securityUiRole",
                new RockerTemplate("grails-app/domain/{packagePath}/Role.groovy", role.template(project)));
        generatorContext.addTemplate("securityUiUserRole",
                new RockerTemplate("grails-app/domain/{packagePath}/UserRole.groovy", userRole.template(project)));
        generatorContext.addTemplate("securityUiUserSpec",
                new RockerTemplate(generatorContext.getTestSourcePath("/{packagePath}/User"), userClassicSpec.template(project)));
        generatorContext.addTemplate("securityUiApplicationGroovy",
                new RockerTemplate("grails-app/conf/application.groovy", securityUiApplicationGroovy.template(project)));

        generatorContext.getConfiguration().put("grails.mail.default.from", "do.not.reply@localhost");
    }
}

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

import org.grails.forge.application.ApplicationType;
import org.grails.forge.application.Project;
import org.grails.forge.application.generator.GeneratorContext;
import org.grails.forge.feature.Category;
import org.grails.forge.feature.FeatureContext;
import org.grails.forge.feature.FeaturePhase;
import org.grails.forge.feature.OneOfFeature;
import org.grails.forge.feature.security.template.user;
import org.grails.forge.feature.security.template.userController;
import org.grails.forge.feature.security.template.userService;
import org.grails.forge.feature.security.template.userSpec;
import org.grails.forge.feature.view.Scaffolding;
import org.grails.forge.template.RockerTemplate;

/**
 * Base of the mutually exclusive security features. Both flavors share the same
 * generated artifacts - a GORM-backed {@code User} implementing {@code UserDetails},
 * a scaffolded controller, a {@code UserService} that doubles as the
 * {@code UserDetailsService}, a seeded admin account and a domain spec - and differ
 * only in what enforces security around them.
 *
 * @since 8.0
 */
public abstract class SecurityFeature implements OneOfFeature {

    private final Scaffolding scaffolding;

    protected SecurityFeature(Scaffolding scaffolding) {
        this.scaffolding = scaffolding;
    }

    @Override
    public Class<?> getFeatureClass() {
        return SecurityFeature.class;
    }

    @Override
    public String getCategory() {
        return Category.SPRING_SECURITY;
    }

    @Override
    public boolean supports(ApplicationType applicationType) {
        return applicationType == ApplicationType.WEB;
    }

    @Override
    public int getOrder() {
        // After the default features, so overriding templates registered under
        // their keys (e.g. spring/resources.groovy) takes effect - but before
        // the BUILD phase renders build.gradle from the collected dependencies.
        return FeaturePhase.TEST.getOrder();
    }

    @Override
    public void processSelectedFeatures(FeatureContext featureContext) {
        if (!featureContext.isPresent(Scaffolding.class) && scaffolding != null) {
            featureContext.addFeature(scaffolding);
        }
    }

    protected void applyUserArtifacts(GeneratorContext generatorContext) {
        final Project project = generatorContext.getProject();
        generatorContext.addTemplate("securityUser",
                new RockerTemplate("grails-app/domain/{packagePath}/User.groovy", user.template(project)));
        generatorContext.addTemplate("securityUserController",
                new RockerTemplate("grails-app/controllers/{packagePath}/UserController.groovy", userController.template(project)));
        generatorContext.addTemplate("securityUserService",
                new RockerTemplate("grails-app/services/{packagePath}/UserService.groovy", userService.template(project)));
        generatorContext.addTemplate("securityUserSpec",
                new RockerTemplate(generatorContext.getTestSourcePath("/{packagePath}/User"), userSpec.template(project)));
    }
}

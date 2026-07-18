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
import org.grails.forge.feature.security.template.role;
import org.grails.forge.feature.security.template.userClassic;
import org.grails.forge.feature.security.template.userClassicSpec;
import org.grails.forge.feature.security.template.userRole;
import org.grails.forge.feature.view.Scaffolding;
import org.grails.forge.template.RockerTemplate;

/**
 * Base of the mutually exclusive security features. The two plugin flavors share the
 * classic User/Role/UserRole domain model so an application generated with the core
 * plugin can adopt the UI plugin later without restructuring; the plain-starter
 * flavor generates its own lighter artifacts.
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

    protected void applyClassicDomainModel(GeneratorContext generatorContext) {
        final Project project = generatorContext.getProject();
        generatorContext.addTemplate("securityUser",
                new RockerTemplate("grails-app/domain/{packagePath}/User.groovy", userClassic.template(project)));
        generatorContext.addTemplate("securityRole",
                new RockerTemplate("grails-app/domain/{packagePath}/Role.groovy", role.template(project)));
        generatorContext.addTemplate("securityUserRole",
                new RockerTemplate("grails-app/domain/{packagePath}/UserRole.groovy", userRole.template(project)));
        generatorContext.addTemplate("securityUserSpec",
                new RockerTemplate(generatorContext.getTestSourcePath("/{packagePath}/User"), userClassicSpec.template(project)));
    }
}

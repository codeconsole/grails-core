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
package org.grails.forge.feature.database;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.grails.forge.application.ApplicationType;
import org.grails.forge.application.generator.GeneratorContext;
import org.grails.forge.build.dependencies.Dependency;
import org.grails.forge.feature.Feature;
import org.grails.forge.feature.FeatureContext;
import org.grails.forge.options.GormImpl;
import org.grails.forge.options.Options;

import java.util.Map;
import java.util.Set;

@Singleton
public class GrailsDataMongoDB extends GormOneOfFeature {

    /**
     * Flapdoodle Embedded MongoDB, which runs a real mongod. Only production uses it, so
     * development and test never wait for the binary it downloads on first use.
     */
    private static final String FLAPDOODLE_VERSION = "4.33.0";

    private final TestContainers testContainers;

    public GrailsDataMongoDB(TestContainers testContainers) {
        this.testContainers = testContainers;
    }

    @Override
    public String getName() {
        return "gorm-mongodb";
    }

    @Override
    public String getTitle() {
        return "Grails Data for MongoDB";
    }

    @Override
    public String getDescription() {
        return "Configure Grails Data for using MongoDB.";
    }

    @Override
    public void processSelectedFeatures(FeatureContext featureContext) {
        super.processSelectedFeatures(featureContext);
        if (!featureContext.isPresent(TestContainers.class) && testContainers != null) {
            featureContext.addFeature(testContainers);
        }
    }

    @Override
    public void apply(GeneratorContext generatorContext) {
        applyDefaultGormConfig(generatorContext.getConfiguration());
        Map<String, Object> config = generatorContext.getConfiguration();
        config.put("grails.mongodb.url", "mongodb://${MONGO_HOST:localhost}:${MONGO_PORT:27017}/foo");
        generatorContext.addDependency(Dependency.builder()
                .groupId("org.apache.grails")
                .artifactId("grails-data-mongodb")
                .implementation());
        applyEmbeddedMongo(generatorContext, config);
    }

    /**
     * Without this a generated application only starts when a MongoDB happens to be
     * listening on the url above, so it is wired in by default rather than offered as a
     * separate feature. Setting an environment's embedded.mongodb.enabled to false points
     * that environment back at the url.
     *
     * <p>Development and test use the in-memory backend, which starts in milliseconds and
     * downloads nothing. Production uses flapdoodle, because it is the backend that can
     * keep the database between restarts, in the same way the Grails website application
     * persists its H2 database to ./prodDb.
     */
    private void applyEmbeddedMongo(GeneratorContext generatorContext, Map<String, Object> config) {
        generatorContext.addDependency(Dependency.builder()
                .groupId("org.apache.grails")
                .artifactId("grails-data-mongodb-embedded")
                .implementation());
        // Flapdoodle is not a dependency of grails-data-mongodb-embedded, because it pulls in
        // jgrapht under LGPL-2.1 or EPL-2.0 and an Apache release should not require it. An
        // application is free to depend on it, the same way it picks its own SQL driver.
        generatorContext.getBuildProperties().put("flapdoodleVersion", FLAPDOODLE_VERSION);
        generatorContext.addDependency(Dependency.builder()
                .groupId("de.flapdoodle.embed")
                .artifactId("de.flapdoodle.embed.mongo")
                .version("$flapdoodleVersion")
                .implementation());

        config.put("environments.development.embedded.mongodb.enabled", true);
        config.put("environments.development.embedded.mongodb.backend", "in-memory");
        config.put("environments.test.embedded.mongodb.enabled", true);
        config.put("environments.test.embedded.mongodb.backend", "in-memory");
        config.put("environments.production.embedded.mongodb.enabled", true);
        config.put("environments.production.embedded.mongodb.backend", "flapdoodle");
        config.put("environments.production.embedded.mongodb.database-dir", "./prodDb");
    }

    @Override
    public boolean shouldApply(ApplicationType applicationType, Options options, Set<Feature> selectedFeatures) {
        return selectedFeatures.stream().anyMatch(f -> f instanceof GrailsDataMongoDB) || options.getGormImpl() == GormImpl.MONGODB;
    }

    @Nullable
    @Override
    public String getThirdPartyDocumentation() {
        return "https://grails.apache.org/docs/latest/grails-data/mongodb/manual/";
    }
}

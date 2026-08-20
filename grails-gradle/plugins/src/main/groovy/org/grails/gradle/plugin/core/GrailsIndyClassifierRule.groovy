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

package org.grails.gradle.plugin.core

import javax.inject.Inject

import groovy.transform.CompileStatic

import org.gradle.api.artifacts.ComponentMetadataContext
import org.gradle.api.artifacts.ComponentMetadataRule
import org.gradle.api.artifacts.ModuleVersionIdentifier

/**
 * Derives an {@code indy} variant from a module's classifier artifact, inside the build that asked
 * for one.
 *
 * <p>The rule runs against every module on the graph but changes only those named in
 * {@code coordinates}. A module that publishes no {@code indy} classifier must not be touched:
 * the derived variant would point at a file that does not exist, and resolution fails when the
 * artifact is fetched rather than when the rule runs. Platforms are the same story — they publish
 * no jar at all.
 *
 * <p>The existing variants are stamped with {@code indy = false} as well as the derived one with
 * {@code true}, so that both candidates carry a value. A disambiguation rule can then pick between
 * them; without a value on both, the main variant would be invisible to that rule.
 *
 * @since 8.0
 */
@CompileStatic
abstract class GrailsIndyClassifierRule implements ComponentMetadataRule {

    private final Set<String> coordinates

    @Inject
    GrailsIndyClassifierRule(Set<String> coordinates) {
        this.coordinates = coordinates
    }

    @Override
    void execute(ComponentMetadataContext context) {
        ModuleVersionIdentifier id = context.details.id
        if (!coordinates.contains("${id.group}:${id.name}".toString())) {
            return
        }

        ['apiElements', 'runtimeElements'].each { String base ->
            context.details.withVariant(base) { variant ->
                variant.attributes { it.attribute(GrailsIndyVariants.INDY_ATTRIBUTE, false) }
            }
            context.details.addVariant("indy${base.capitalize()}", base) { variant ->
                variant.attributes { it.attribute(GrailsIndyVariants.INDY_ATTRIBUTE, true) }
                variant.withFiles {
                    it.removeAllFiles()
                    it.addFile("${id.name}-${id.version}-${GrailsIndyVariants.INDY_CLASSIFIER}.jar".toString())
                }
            }
        }
    }
}

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

import groovy.transform.CompileStatic

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.Directory
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.GroovyRuntime
import org.gradle.api.tasks.GroovySourceDirectorySet
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.jvm.toolchain.JavaToolchainService

import org.grails.gradle.plugin.util.SourceSets

/**
 * Publishes a Grails library in two flavours so that a consuming application can pick the Groovy
 * call-site implementation it wants without every producer having to agree on one.
 *
 * <p>The <em>default</em> artifact is compiled with Groovy's own default, which enables
 * {@code invokedynamic}. A second artifact carrying the {@code noindy} Maven classifier is compiled
 * with {@code invokedynamic} disabled, so its call sites use the older call-site-caching bytecode.
 * Both artifacts contain the same classes compiled from the same sources with the same AST
 * transformations; only the bytecode used to dispatch dynamic Groovy calls differs.
 *
 * <p>The {@code noindy} jar is published as a plain Maven classifier artifact and nothing else:
 * the module's published metadata keeps exactly the variants it always had. Publishing a second
 * variant instead would make the module ambiguous to any configuration that requests no attributes
 * at all — the shape used by tck and probe configurations, here and in other people's builds — and
 * no attribute rule can repair that, because the variant lacking the attribute never appears among
 * the candidate values a disambiguation rule is given.
 *
 * <p>Selection is therefore assembled on the consuming side. An application that asks for
 * {@code noindy} registers a {@link org.gradle.api.artifacts.ComponentMetadataRule} that derives a
 * variant from the classifier artifact for the modules that publish one. Because that variant only
 * ever exists inside a build that opted in, the schema there also carries the rules needed to
 * choose, and nobody else's resolution changes in any way.
 *
 * @since 8.0
 */
@CompileStatic
class GrailsIndyVariants {

    /**
     * Attribute used to request a Groovy call-site flavour. Declared only on the {@code noindy}
     * variants; see the class javadoc for why the default variants leave it unset.
     */
    public static final Attribute<Boolean> INDY_ATTRIBUTE = Attribute.of('org.apache.grails.indy', Boolean)

    /** Maven classifier of the artifact compiled without {@code invokedynamic}. */
    public static final String NOINDY_CLASSIFIER = 'noindy'

    /** Name of the secondary variant added to {@code apiElements} and {@code runtimeElements}. */
    public static final String NOINDY_VARIANT_NAME = 'noindy'

    /** Name of the {@link GroovyCompile} task that compiles the {@code noindy} classes. */
    public static final String NOINDY_COMPILE_TASK_NAME = 'compileNoindyGroovy'

    /** Name of the {@link Jar} task that packages the {@code noindy} classes. */
    public static final String NOINDY_JAR_TASK_NAME = 'noindyJar'

    /** Manifest attribute through which a module advertises that it publishes a noindy classifier. */
    public static final String NOINDY_MANIFEST_ATTRIBUTE = 'Grails-Noindy-Artifact'

    private GrailsIndyVariants() {
    }

    /**
     * Adds the {@code noindy} compilation, jar and published variants to a library project.
     *
     * <p>Safe to call more than once and from more than one plugin: the second call returns without
     * doing anything. In this repository both the {@code grails-plugin} Gradle plugin and the
     * mono-repo's own build conventions can reach the same project.
     *
     * @param project the library project whose artifacts should be published in both flavours
     */
    static void configureProducer(Project project) {
        project.pluginManager.withPlugin('groovy') {
            if (project.tasks.names.contains(NOINDY_JAR_TASK_NAME)) {
                return
            }

            SourceSet main = SourceSets.findMainSourceSet(project)
            if (main == null) {
                return
            }

            TaskProvider<GroovyCompile> noindyCompile = registerNoindyCompileTask(project, main)
            TaskProvider<Jar> noindyJar = registerNoindyJarTask(project, main, noindyCompile)

            publishNoindyClassifier(project, noindyJar)
            advertiseNoindyArtifact(project)
        }
    }

    /**
     * Makes every resolvable configuration of an application request a Groovy call-site flavour, so
     * that the framework and plugin artifacts on its classpath match the way the application itself
     * is compiled.
     *
     * <p>Dependencies that publish only the default artifact — a plugin built before this existed,
     * or one that opted out — stay resolvable: their single variant does not declare the attribute
     * and so remains a valid candidate whichever flavour is requested.
     *
     * @param project the application project
     * @param indy provider for the value of {@code grails.indy}
     */
    static void configureConsumer(Project project, Provider<Boolean> indy, Provider<Set<String>> noindyModules) {
        project.dependencies.attributesSchema { schema ->
            schema.attribute(INDY_ATTRIBUTE) { strategy ->
                strategy.disambiguationRules.add(GrailsIndyDisambiguationRule)
            }
        }

        project.afterEvaluate {
            if (indy.getOrElse(true)) {
                // Nothing to select: the default artifacts are already what everyone resolves.
                return
            }

            Set<String> coordinates = noindyModules.getOrElse([] as Set)
            if (!coordinates) {
                project.logger.warn('Grails: indy is disabled but no modules are listed as publishing a noindy artifact, so the dependencies on the classpath remain the invokedynamic ones.')
                project.logger.warn('        List them with grails { noindyModules = [\'group:name\'] } in build.gradle.')
                return
            }

            project.dependencies.components { components ->
                components.all(GrailsNoindyClassifierRule) { rule ->
                    rule.params(coordinates)
                }
            }

            project.configurations.configureEach { Configuration configuration ->
                if (configuration.canBeResolved) {
                    configuration.attributes.attribute(INDY_ATTRIBUTE, false)
                }
            }
        }
    }

    private static TaskProvider<GroovyCompile> registerNoindyCompileTask(Project project, SourceSet main) {
        GroovyRuntime groovyRuntime = project.extensions.getByType(GroovyRuntime)
        JavaPluginExtension javaExtension = project.extensions.getByType(JavaPluginExtension)
        Provider<Directory> destination = project.layout.buildDirectory.dir("classes/groovy/${NOINDY_CLASSIFIER}")

        return project.tasks.register(NOINDY_COMPILE_TASK_NAME, GroovyCompile) { GroovyCompile compile ->
            compile.description = 'Compiles the main Groovy source set with invokedynamic disabled.'
            compile.group = BasePlugin.BUILD_GROUP

            compile.source = main.extensions.getByType(GroovySourceDirectorySet)
            compile.classpath = main.compileClasspath
            compile.groovyClasspath = groovyRuntime.inferGroovyClasspath(main.compileClasspath)
            compile.destinationDirectory.set(destination)

            // The whole point of the second compilation. Every other GroovyCompile option is left to
            // the conventions that GrailsGradlePlugin applies to all GroovyCompile tasks, so this
            // task picks up the same AST transforms, compiler configuration script and fork settings
            // as the default compilation and differs only in call-site bytecode.
            compile.groovyOptions.optimizationOptions.put('indy', false)

            // Registered tasks do not inherit the java extension's release/toolchain wiring that the
            // java plugin applies to the source set's own compile tasks.
            compile.sourceCompatibility = javaExtension.sourceCompatibility.toString()
            compile.targetCompatibility = javaExtension.targetCompatibility.toString()
            if (javaExtension.toolchain.languageVersion.present) {
                JavaToolchainService toolchains = project.extensions.getByType(JavaToolchainService)
                compile.javaLauncher.set(toolchains.launcherFor(javaExtension.toolchain))
            }
        }
    }

    private static TaskProvider<Jar> registerNoindyJarTask(Project project, SourceSet main,
                                                           TaskProvider<GroovyCompile> noindyCompile) {
        return project.tasks.register(NOINDY_JAR_TASK_NAME, Jar) { Jar jar ->
            jar.description = 'Assembles a jar whose Groovy classes are compiled without invokedynamic.'
            jar.group = BasePlugin.BUILD_GROUP
            jar.archiveClassifier.set(NOINDY_CLASSIFIER)

            // Groovy classes come from the noindy compilation; everything else in the source set
            // output is unaffected by the call-site flavour and is reused as-is. Java bytecode does
            // not contain Groovy call sites, so recompiling it would produce identical classes.
            jar.from(noindyCompile.flatMap { GroovyCompile compile -> compile.destinationDirectory })
            jar.from(main.java.classesDirectory)
            jar.from(project.tasks.named(main.processResourcesTaskName))
        }
    }

    /**
     * Attaches the {@code noindy} jar to the module's publication as an ordinary classifier
     * artifact, leaving the published variants untouched.
     */
    private static void publishNoindyClassifier(Project project, TaskProvider<Jar> noindyJar) {
        project.pluginManager.withPlugin('maven-publish') {
            PublishingExtension publishing = project.extensions.getByType(PublishingExtension)
            publishing.publications.withType(MavenPublication).configureEach { MavenPublication publication ->
                publication.artifact(noindyJar)
            }
        }
    }

    /**
     * Records in the main jar's manifest that a {@code noindy} classifier accompanies it, so an
     * application can discover which of its dependencies publish one instead of being told.
     * Mirrors how a plugin advertises its CLI companion through {@code Grails-Cli-Artifact}.
     */
    private static void advertiseNoindyArtifact(Project project) {
        project.tasks.named('jar', Jar).configure { Jar jar ->
            jar.manifest.attributes((NOINDY_MANIFEST_ATTRIBUTE): project.provider {
                "${project.group}:${project.name}".toString()
            })
        }
    }
}

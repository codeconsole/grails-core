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

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry

import org.springframework.boot.gradle.tasks.bundling.BootJar

import org.grails.gradle.plugin.commands.GrailsCliArtifactGradlePlugin
import org.grails.gradle.plugin.run.FindMainClassTask
import org.grails.gradle.plugin.util.SourceSets

/**
 * A Gradle plugin for Grails plugins
 *
 * @author Graeme Rocher
 * @since 3.0
 *
 */
@CompileStatic
class GrailsPluginGradlePlugin extends GrailsGradlePlugin {

    public static final String PLUGIN_ID = 'org.apache.grails.gradle.grails-plugin'

    @Inject
    GrailsPluginGradlePlugin(ToolingModelBuilderRegistry registry) {
        super(registry)
    }

    @Override
    void apply(Project project) {
        super.apply(project)

        project.pluginManager.apply('java-library')

        checkForConfigurationClash(project)
        configureAstSources(project)
        configureAssembleTask(project)
        configurePluginResources(project)
        configureJarTask(project)
        configureSourcesJarTask(project)
    }

    @Override
    protected Closure<String> getGroovyCompilerScript(GroovyCompile compile, Project project) {
        def versionProvider = project.provider { project.version.toString() }
        compile.inputs.property('version', versionProvider)

        def projectNameProvider = project.provider { project.name }
        compile.inputs.property('name', projectNameProvider)

        Closure<String> parent = super.getGroovyCompilerScript(compile, project)
        return { ->
            """${parent?.call() ?: ''}

            withConfig(configuration) {
                inline(phase: 'CONVERSION') { source, context, classNode ->
                    classNode.putNodeMetaData('projectVersion', '${versionProvider.get()}')
                    classNode.putNodeMetaData('projectName', '${projectNameProvider.get()}')
                    classNode.putNodeMetaData('isPlugin', 'true')
                }
            }
            """ as String
        }
    }

    protected String getDefaultProfile() {
        'web-plugin'
    }

    @Override
    protected void createBuildPropertiesTask(Project project) {
        // no-op
    }

    @CompileStatic
    protected void configureSourcesJarTask(Project project) {
        if (!project.tasks.names.contains('sourcesJar')) {
            project.logger.info('A sourcesJar task was not found, creating one.', project.name)
            project.tasks.register('sourcesJar', Jar).configure { Jar jarTask ->
                jarTask.archiveClassifier.set('sources')
                jarTask.from(SourceSets.findMainSourceSet(project).allSource)
            }
        }
    }

    @Override
    protected void applySpringBootPlugin(Project project) {
        super.applySpringBootPlugin(project)
        project.tasks.withType(BootJar).configureEach { BootJar bootJar ->
            bootJar.enabled = false
        }
    }

    @CompileDynamic
    protected void configureAstSources(Project project) {
        SourceSetContainer sourceSets = SourceSets.findSourceSets(project)
        project.sourceSets {
            ast {
                groovy {
                    compileClasspath += project.configurations.compileClasspath
                }
            }
            main {
                compileClasspath += sourceSets.ast.output
            }
            test {
                compileClasspath += sourceSets.ast.output
            }
        }

        def copyAstClasses = project.tasks.register('copyAstClasses', Copy) {
            it.from(sourceSets.ast.output)
            it.into(project.layout.buildDirectory.dir('classes/groovy/main'))
        }

        project.tasks.named('findMainClass', FindMainClassTask).configure {
            it.dependsOn(copyAstClasses)
        }

        def taskContainer = project.tasks
        taskContainer.named('classes').configure { it.dependsOn(copyAstClasses) }

        taskContainer.withType(JavaExec).configureEach {
            it.classpath += sourceSets.ast.output
        }

        taskContainer.whenTaskAdded {
            if (it.name == 'compileWebappGroovyPages') {
                it.configure {
                    it.dependsOn(copyAstClasses)
                }
            }
        }

        project.afterEvaluate {
            Task sourcesJarTask = taskContainer.findByName('sourcesJar')
            if (sourcesJarTask) {
                project.rootProject.logger.info('Found sources jar task')
                sourcesJarTask.configure {
                    project.rootProject.logger.info('Including ast in sources jar')
                    from(sourceSets.ast.allSource)
                }
            } else {
                project.rootProject.logger.info('No sources jar task found')
            }

            Task javadocTask = taskContainer.findByName('javadoc')
            if (javadocTask) {
                javadocTask.configure {
                    source += sourceSets.ast.allJava
                }
            } else {
                project.rootProject.logger.info('Warning - a javadocTask was not found, so the ast source will not be included in the javadoc task')
            }

            Task groovydocTask = taskContainer.findByName('groovydoc')
            if (groovydocTask) {
                if (taskContainer.findByName('javadocJar') == null) {
                    taskContainer.create('javadocJar', Jar) {
                        archiveClassifier.set('javadoc')
                        from(groovydocTask.outputs)
                        outputs.cacheIf { true }
                    }.dependsOn(javadocTask)
                }

                groovydocTask.configure {
                    source += sourceSets.ast.allJava
                }
            } else {
                project.rootProject.logger.info('Warning - a groovydocTask was not found, so the ast source will not be included in the groovydoc task')
            }
        }
    }

    protected void configureAssembleTask(Project project) {
        // Assemble task in Grails Plugins should only produce a plain jar
        project.tasks.named('assemble').configure { Task assembleTask ->
            def disabledTasks = [
                    'bootDistTar',
                    'bootDistZip',
                    'bootJar',
                    'bootStartScripts',
                    'bootWar',
                    'bootWarMainClassName',
                    'distTar',
                    'distZip',
                    'startScripts',
                    'war'
            ]
            disabledTasks.each { String disabledTaskName ->
                project.tasks.findByName(disabledTaskName)?.enabled = false
            }
            // By default the assemble task does not create a plain jar
            assembleTask.dependsOn('jar')
        }
    }

    protected void configureJarTask(Project project) {
        project.tasks.named('jar', Jar).configure { Jar jarTask ->
            jarTask.enabled = true
            jarTask.archiveClassifier.set('') // Remove '-plain' suffix from jar file name
            jarTask.exclude(
                    'application.groovy',
                    'application.yml',
                    'logback.groovy',
                    'logback.xml',
                    'logback-spring.xml',
                    // Plugins must not ship spring/resources.groovy (use doWithSpring instead),
                    // but it must remain in build/resources/main/ so it is on the integration
                    // test classpath for plugin modules that test their own resources.groovy.
                    'spring/resources.groovy'
            )
        }
    }

    /**
     * Packages plugin templates into the runtime jar and routes command scripts into either the
     * runtime jar or the companion {@code -cli} jar.
     *
     * <p>When {@link GrailsCliArtifactGradlePlugin} is applied, {@code src/main/scripts} is copied
     * into the cli source set as {@code META-INF/commands} so Groovy/YAML/JSON command resources
     * ship only on {@code grailsCliClasspath} and stay out of {@code runtimeClasspath},
     * {@code bootJar}, and {@code bootWar}. Without a companion, the historical behavior is
     * preserved: scripts remain in the runtime plugin jar so unmigrated Grails 7 plugins and
     * {@code legacyCommandSupport} consumers keep discovering them on the application classpath.
     * Templates always stay on the runtime jar.</p>
     */
    @CompileDynamic
    protected void configurePluginResources(Project project) {
        project.afterEvaluate() {
            ProcessResources processResources = (ProcessResources) project.tasks.getByName('processResources')
            boolean hasCliCompanion = project.pluginManager.hasPlugin(GrailsCliArtifactGradlePlugin.PLUGIN_ID)
            ProcessResources commandResources = processResources
            if (hasCliCompanion) {
                SourceSet cliSourceSet = project.extensions.getByType(SourceSetContainer)
                        .getByName(GrailsCliArtifactGradlePlugin.CLI_SOURCE_SET_NAME)
                commandResources = (ProcessResources) project.tasks.getByName(cliSourceSet.processResourcesTaskName)
            }

            TaskProvider<Copy> copyCommands = project.tasks.register('copyCommands', Copy) { Copy copy ->
                copy.from("${project.projectDir}/src/main/scripts")
                // Resolve the destination lazily so the process*Resources output dir is final.
                copy.into {
                    "${commandResources.destinationDir}/META-INF/commands"
                }
            }

            TaskProvider<Copy> copyTemplates = project.tasks.register('copyTemplates', Copy) { Copy copy ->
                copy.from("${project.projectDir}/src/main/templates")
                copy.into {
                    "${processResources.destinationDir}/META-INF/templates"
                }
            }
            processResources.setDuplicatesStrategy(DuplicatesStrategy.INCLUDE)
            if (hasCliCompanion) {
                commandResources.setDuplicatesStrategy(DuplicatesStrategy.INCLUDE)
                commandResources.dependsOn(copyCommands)
                processResources.dependsOn(copyTemplates)
                // A prior non-companion (or pre-migration) build may have left src/main/scripts
                // copies under the main processResources output. Wipe META-INF/commands there so
                // incremental jars do not keep shipping them; processResources then re-runs (its
                // outputs changed) and restores any hand-authored src/main/resources/META-INF/commands.
                // Use the configured processResources destination, not a hard-coded build path.
                TaskProvider cleanStaleRuntimeCommands = project.tasks
                        .register('cleanStaleRuntimeCommandResources') { Task cleanTask ->
                            cleanTask.outputs.upToDateWhen { false }
                            cleanTask.doLast {
                                project.delete(new File(processResources.destinationDir, 'META-INF/commands'))
                            }
                        }
                processResources.dependsOn(cleanStaleRuntimeCommands)
                project.tasks.named('jar', Jar).configure { Jar jarTask ->
                    jarTask.dependsOn(cleanStaleRuntimeCommands)
                }
            }
            else {
                processResources.dependsOn(copyCommands, copyTemplates)
            }
            project.processResources {
                // spring/resources.groovy is excluded from the published jar (see configureJarTask)
                // but is allowed into build/resources/main/ so plugin integration tests can load it.
                exclude('**/*.gsp')
            }
        }
    }

    protected void checkForConfigurationClash(Project project) {
        File yamlConfig = new File(project.projectDir, 'grails-app/conf/plugin.yml')
        File groovyConfig = new File(project.projectDir, 'grails-app/conf/plugin.groovy')
        if (yamlConfig.exists() && groovyConfig.exists()) {
            throw new RuntimeException('A plugin may define a plugin.yml or a plugin.groovy, but not both')
        }
    }
}

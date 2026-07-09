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
package org.grails.gradle.plugin.commands

import java.util.jar.JarFile

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic

import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider

import grails.util.Environment
import grails.util.GrailsNameUtils
import org.grails.gradle.plugin.core.GrailsExtension
import org.grails.gradle.plugin.core.GrailsGradlePlugin
import org.grails.gradle.plugin.util.ClasspathUtils
import org.grails.gradle.plugin.util.SourceSets
import org.grails.io.support.FactoriesLoaderSupport
import org.grails.build.parsing.CommandLineParser

/**
 * Configures the CLI tier of a Grails application or plugin project: the {@code grailsCli}
 * configurations (with automatic discovery of companion {@code -cli} artifacts advertised by the
 * dependency graph), the per-command tasks, the generic {@code runCommand}/{@code runScript}
 * tasks, and the interactive {@code console}/{@code shell} tasks. Applied automatically by the
 * Grails Gradle plugin.
 *
 * @since 8.0
 */
@CompileStatic
class GrailsCliGradlePlugin implements Plugin<Project> {

    public static final String APPLICATION_CONTEXT_COMMAND_CLASS = 'org.apache.grails.core.cli.ApplicationCommand'

    /**
     * The dependency bucket carrying CLI-only dependencies (Grails commands and their libraries):
     * compile-visible so `grails-app/commands` sources compile against the cli-only contract, on
     * the command-runner classpath, but never on `runtimeClasspath`, `bootRun`, or packaged
     * artifacts.
     */
    public static final String GRAILS_CLI_CONFIGURATION = 'grailsCli'

    /** The resolvable view of {@link #GRAILS_CLI_CONFIGURATION} used by the command-runner tasks */
    public static final String GRAILS_CLI_CLASSPATH_CONFIGURATION = 'grailsCliClasspath'

    /**
     * Set this project property to {@code false} to stop the plugin from auto-provisioning the
     * CLI tier onto {@link #GRAILS_CLI_CONFIGURATION}; equivalent to
     * {@code grails { cliAutoProvision = false }}.
     */
    public static final String GRAILS_CLI_AUTO_PROVISION_PROPERTY = 'grailsCliAutoProvision'

    /** The internal probe configuration used to detect companion `-cli` modules */
    public static final String GRAILS_CLI_DETECT_CONFIGURATION = 'grailsCliDetect'

    @Override
    void apply(Project project) {
        // self-sufficient when applied standalone: the auto-provisioning behavior is configured
        // through the `grails` extension, normally registered by the Grails Gradle plugin
        if (project.extensions.findByName('grails') == null) {
            project.extensions.create('grails', GrailsExtension, project)
        }

        configureGrailsCliConfiguration(project)

        configureConsoleTask(project)

        configureApplicationCommands(project)

        configureRunScript(project)

        configureRunCommand(project)
    }

    /**
     * Registers the `grailsCli` dependency bucket and its resolvable `grailsCliClasspath` view.
     * `grailsCli` carries the CLI tier — command companion artifacts (`<artifactId>-cli`) and the
     * libraries they need. It extends the compile classpaths (the same wiring `compileOnly` uses)
     * so `grails-app/commands` sources compile against the cli-only contract, while staying off
     * `runtimeClasspath` — and therefore out of `bootRun`, `bootJar`, and `bootWar`.
     */
    protected void configureGrailsCliConfiguration(Project project) {
        ConfigurationContainer configurations = project.configurations
        if (configurations.names.contains(GRAILS_CLI_CONFIGURATION)) {
            return
        }

        Configuration grailsCli = configurations.create(GRAILS_CLI_CONFIGURATION)
        grailsCli.canBeResolved = false
        grailsCli.canBeConsumed = false
        grailsCli.description = 'CLI-only dependencies (Grails commands and the libraries they need); compile-visible and on the command-runner classpath, but never on runtimeClasspath, bootRun, or packaged artifacts.'

        // compile visibility for grails-app/commands sources plus the TEST classpaths (tests
        // exercise commands inside the test JVM), while the main runtimeClasspath — and therefore
        // bootRun, bootJar, and bootWar — never sees the cli tier; matching configurations that
        // appear later (e.g. the integrationTest pair) are included as they are created
        configurations.matching { Configuration it ->
            it.name in ['compileClasspath', 'testCompileClasspath', 'testRuntimeClasspath',
                        'integrationTestCompileClasspath', 'integrationTestRuntimeClasspath']
        }.configureEach { Configuration it ->
            it.extendsFrom(grailsCli)
        }

        Configuration grailsCliClasspath = configurations.create(GRAILS_CLI_CLASSPATH_CONFIGURATION)
        grailsCliClasspath.extendsFrom(grailsCli)
        grailsCliClasspath.canBeResolved = true
        grailsCliClasspath.canBeConsumed = false
        grailsCliClasspath.description = 'Resolvable view of grailsCli used by the command-runner tasks.'

        Configuration grailsCliDetect = configurations.create(GRAILS_CLI_DETECT_CONFIGURATION)
        grailsCliDetect.canBeResolved = true
        grailsCliDetect.canBeConsumed = false
        grailsCliDetect.visible = false
        grailsCliDetect.description = 'Internal probe used to discover companion -cli artifacts advertised by dependencies.'
        for (String bucket : ['api', 'implementation', 'runtimeOnly']) {
            configurations.matching { Configuration it -> it.name == bucket }.configureEach { Configuration it ->
                grailsCliDetect.extendsFrom(it)
            }
        }

        // computed when the configuration is first resolved, so every dependency (and the
        // extension configuration) declared by the build script is visible
        grailsCli.withDependencies { DependencySet dependencies ->
            autoProvisionCliDependencies(project, dependencies)
        }
    }

    /**
     * Auto-provisions the CLI tier onto {@code grailsCli}: the command contract and runner, plus
     * every companion {@code -cli} artifact advertised by a dependency of the application. A
     * module advertises its companion through the {@code Grails-Cli-Artifact} manifest attribute
     * of its runtime jar (stamped by the framework's cli-artifact build convention; third-party
     * plugins set it on their jar task). Discovery walks the full dependency graph (including
     * transitive plugins) through a lenient resolution of an internal probe configuration.
     * Disable with {@code grails { cliAutoProvision = false }}.
     */
    @CompileDynamic
    protected void autoProvisionCliDependencies(Project project, DependencySet dependencies) {
        GrailsExtension grails = project.extensions.getByType(GrailsExtension)
        if (!grails.cliAutoProvision.get()) {
            return
        }

        // command authoring and execution work out of the box: the command contract + the runner
        dependencies.add(project.dependencies.create('org.apache.grails:grails-core-cli'))
        dependencies.add(project.dependencies.create('org.apache.grails:grails-console'))

        Configuration probe = project.configurations.getByName(GRAILS_CLI_DETECT_CONFIGURATION)
        Set<String> companions = [] as Set
        def lenientArtifacts = probe.incoming.artifactView { it.lenient(true) }.artifacts
        for (ResolvedArtifactResult artifact : lenientArtifacts.artifacts) {
            String companion = findAdvertisedCliArtifact(project, artifact)
            if (companion) {
                companions.add(companion)
            }
        }

        for (String companion : companions) {
            List<String> coordinate = companion.tokenize(':')
            if (coordinate.size() != 2) {
                project.logger.warn('Ignoring malformed Grails-Cli-Artifact value [{}] found in the dependencies of project {}', companion, project.name)
                continue
            }
            boolean alreadyDeclared = dependencies.any { Dependency existing ->
                existing.group == coordinate[0] && existing.name == coordinate[1]
            }
            if (!alreadyDeclared) {
                project.logger.info('Detected cli companion artifact {}, adding it to the {} configuration of project {}',
                        companion, GRAILS_CLI_CONFIGURATION, project.name)
                dependencies.add(project.dependencies.create(companion))
            }
        }
    }

    /**
     * Returns the companion cli coordinate ({@code group:artifactId}) advertised by the given
     * resolved artifact, or {@code null}. For artifacts produced by a project of the same build
     * the (possibly not yet built) jar is not read — the coordinate comes from the project's
     * {@code cliArtifactId} property exported by the cli-artifact convention.
     */
    @CompileDynamic
    protected String findAdvertisedCliArtifact(Project project, ResolvedArtifactResult artifact) {
        def componentIdentifier = artifact.id.componentIdentifier
        if (componentIdentifier instanceof ProjectComponentIdentifier) {
            Project target = project.rootProject.findProject(((ProjectComponentIdentifier) componentIdentifier).projectPath)
            def cliArtifactId = target?.findProperty('cliArtifactId')
            return cliArtifactId ? "${target.group}:${cliArtifactId}" as String : null
        }

        File file = artifact.file
        if (file == null || !file.isFile() || !file.name.endsWith('.jar')) {
            return null
        }
        try (JarFile jarFile = new JarFile(file)) {
            return jarFile.manifest?.mainAttributes?.getValue('Grails-Cli-Artifact')
        }
        catch (IOException ignored) {
            return null
        }
    }

    @CompileDynamic
    protected void configureApplicationCommands(Project project) {
        def applicationContextCommands = FactoriesLoaderSupport.loadFactoryNames(APPLICATION_CONTEXT_COMMAND_CLASS,
                FactoriesLoaderSupport.classLoader, FactoriesLoaderSupport.CLI_FACTORIES_RESOURCE_LOCATION)
        project.afterEvaluate {
            FileCollection fileCollection = ClasspathUtils.buildClasspath(project, project.configurations.runtimeClasspath, project.configurations.grailsCliClasspath)
            // commands come from the buildscript classloader (legacy placement) and from the
            // project's cli tier, so auto-provisioned command artifacts register their tasks
            // without any buildscript classpath entry
            Set<String> commandClassNames = new LinkedHashSet<String>()
            if (applicationContextCommands) {
                commandClassNames.addAll(applicationContextCommands as List)
            }
            GrailsExtension grails = project.extensions.getByType(GrailsExtension)
            if (grails.cliAutoProvision.get()) {
                commandClassNames.addAll(loadCommandNamesFromCliClasspath(project))
            }
            for (ctxCommand in commandClassNames) {
                String taskName = GrailsNameUtils.getLogicalPropertyName(ctxCommand, 'Command')
                String commandName = GrailsNameUtils.getScriptName(GrailsNameUtils.getLogicalName(ctxCommand, 'Command'))
                if (!project.tasks.names.contains(taskName)) {
                    project.tasks.register(taskName, ApplicationContextCommandTask).configure {
                        it.classpath = fileCollection
                        it.command = commandName
                        it.systemProperty(Environment.KEY, System.getProperty(Environment.KEY, Environment.DEVELOPMENT.getName()))
                        List<Object> args = []
                        def otherArgs = project.findProperty('args')
                        if (otherArgs) {
                            args.addAll(CommandLineParser.translateCommandline(otherArgs as String))
                        }

                        def appClassProvider = GrailsGradlePlugin.getMainClassProvider(project)

                        it.doFirst {
                            args << appClassProvider.get()
                            it.args(args)
                        }
                    }
                }
            }
        }
    }

    /**
     * Loads command class names from the {@code META-INF/grails-cli.factories} files of the
     * resolved {@code grailsCliClasspath} jars, so per-command Gradle tasks (e.g. {@code dbmUpdate})
     * are registered for auto-provisioned cli artifacts. Resolution is lenient and jars that are
     * not built yet (project dependencies within a composite) are skipped — the generic
     * {@code runCommand} task can always execute those commands regardless.
     */
    @CompileDynamic
    protected Collection<String> loadCommandNamesFromCliClasspath(Project project) {
        Set<String> names = new LinkedHashSet<String>()
        Configuration cliClasspath = project.configurations.findByName(GRAILS_CLI_CLASSPATH_CONFIGURATION)
        if (cliClasspath == null) {
            return names
        }
        for (File file : cliClasspath.incoming.artifactView { it.lenient(true) }.files) {
            if (!file.isFile() || !file.name.endsWith('.jar')) {
                continue
            }
            try (JarFile jarFile = new JarFile(file)) {
                def entry = jarFile.getEntry(FactoriesLoaderSupport.CLI_FACTORIES_RESOURCE_LOCATION)
                if (entry == null) {
                    continue
                }
                Properties properties = new Properties()
                jarFile.getInputStream(entry).withCloseable { input ->
                    properties.load(input)
                }
                String factoryNames = properties.getProperty(APPLICATION_CONTEXT_COMMAND_CLASS)
                if (factoryNames) {
                    names.addAll(factoryNames.split(',').toList()*.trim())
                }
            }
            catch (IOException ignored) {
                // unreadable jar — skip
            }
        }
        names
    }

    protected void configureConsoleTask(Project project) {
        TaskContainer tasks = project.tasks
        if (!project.configurations.names.contains('console')) {
            if (!tasks.names.contains('findMainClass')) {
                project.logger.info('Project {} does not contain the findMainClass task so the console & shell tasks will not be created.', project.name)
                return
            }

            NamedDomainObjectProvider<Configuration> consoleConfiguration = project.configurations.register('console')
            createConsoleTask(project, tasks, consoleConfiguration)
            createShellTask(project, tasks, consoleConfiguration)
        }
    }

    @CompileDynamic
    protected TaskProvider<JavaExec> createConsoleTask(Project project, TaskContainer tasks, NamedDomainObjectProvider<Configuration> configuration) {
        def consoleTask = tasks.register('console', JavaExec)
        project.afterEvaluate {
            consoleTask.configure {
                it.dependsOn(tasks.named('classes'), tasks.named('findMainClass'))
                // grails-console arrives through the auto-provisioned grailsCli tier; the
                // `console` configuration remains for additional console-only dependencies
                it.classpath = project.sourceSets.main.runtimeClasspath + configuration.get() +
                        project.configurations.getByName(GRAILS_CLI_CLASSPATH_CONFIGURATION)
                it.mainClass.set('grails.ui.console.GrailsSwingConsole')

                def appClass = GrailsGradlePlugin.getMainClassProvider(project)

                it.doFirst {
                    it.args(appClass.get())
                }
            }
        }
        consoleTask
    }

    @CompileDynamic
    protected TaskProvider<JavaExec> createShellTask(Project project, TaskContainer tasks, NamedDomainObjectProvider<Configuration> configuration) {
        def shellTask = tasks.register('shell', JavaExec)
        project.afterEvaluate {
            shellTask.configure {
                it.dependsOn(tasks.named('classes'), tasks.named('findMainClass'))
                // grails-console arrives through the auto-provisioned grailsCli tier; the
                // `console` configuration remains for additional console-only dependencies
                it.classpath = project.sourceSets.main.runtimeClasspath + configuration.get() +
                        project.configurations.getByName(GRAILS_CLI_CLASSPATH_CONFIGURATION)
                it.mainClass.set('grails.ui.shell.GrailsShell')
                it.standardInput = System.in

                def appClass = GrailsGradlePlugin.getMainClassProvider(project)

                it.doFirst {
                    it.args(appClass.get())
                }
            }
        }
        shellTask
    }

    @CompileDynamic
    protected void configureRunScript(Project project) {
        if (!project.tasks.names.contains('runScript')) {
            def runTask = project.tasks.register('runScript', ApplicationContextScriptTask)
            project.afterEvaluate {
                runTask.configure {
                    SourceSet mainSourceSet = SourceSets.findMainSourceSet(project)
                    it.classpath = mainSourceSet.runtimeClasspath + project.configurations.getByName(GRAILS_CLI_CLASSPATH_CONFIGURATION)
                    it.systemProperty(Environment.KEY, System.getProperty(Environment.KEY, Environment.DEVELOPMENT.getName()))

                    // devtools' automatic restart mechanism uses a specialized classloader setup, which can interfere
                    // with Grails' plugin management and bean wiring when running CLI scripts via Gradle
                    it.systemProperty('spring.devtools.restart.enabled', 'false')

                    List<Object> args = []
                    def otherArgs = project.findProperty('args')
                    if (otherArgs) {
                        args.addAll(CommandLineParser.translateCommandline(otherArgs as String))
                    }

                    def appClassProvider = GrailsGradlePlugin.getMainClassProvider(project)

                    it.doFirst {
                        args << appClassProvider.get()
                        it.args(args)
                    }
                }
            }
        }
    }

    @CompileDynamic
    protected void configureRunCommand(Project project) {
        if (!project.tasks.names.contains('runCommand')) {
            def runTask = project.tasks.register('runCommand', ApplicationContextCommandTask)
            project.afterEvaluate {
                runTask.configure {
                    SourceSet mainSourceSet = SourceSets.findMainSourceSet(project)
                    it.classpath = mainSourceSet.runtimeClasspath + project.configurations.getByName(GRAILS_CLI_CLASSPATH_CONFIGURATION)
                    it.systemProperty(Environment.KEY, System.getProperty(Environment.KEY, Environment.DEVELOPMENT.getName()))

                    // devtools' automatic restart mechanism uses a specialized classloader setup, which can interfere
                    // with Grails' plugin management and bean wiring when running CLI commands via Gradle
                    it.systemProperty('spring.devtools.restart.enabled', 'false')

                    List<Object> args = []
                    def otherArgs = project.findProperty('args')
                    if (otherArgs) {
                        args.addAll(CommandLineParser.translateCommandline(otherArgs as String))
                    }

                    def appClassProvider = GrailsGradlePlugin.getMainClassProvider(project)

                    it.doFirst {
                        args << appClassProvider.get()
                        it.args(args)
                    }

                }
            }
        }
    }

}

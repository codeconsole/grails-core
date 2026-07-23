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
package org.apache.grails.core.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

import groovy.transform.CompileStatic

import org.springframework.core.Ordered

import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

class ApplicationCommandProviderSpec extends Specification {

    @TempDir
    Path tempDir

    private ClassLoader originalContextClassLoader
    private URLClassLoader factoryClassLoader

    def cleanup() {
        Thread.currentThread().contextClassLoader = originalContextClassLoader
        factoryClassLoader?.close()
    }

    def "continues loading providers when one provider constructor fails"() {
        given:
        File metaInf = new File(tempDir.toFile(), 'META-INF')
        assert metaInf.mkdirs()
        new File(metaInf, 'grails-cli.factories').text = "${ApplicationCommandProvider.name}=${ThrowingCommandProvider.name},${TestCommandProvider.name}"
        originalContextClassLoader = Thread.currentThread().contextClassLoader
        factoryClassLoader = new URLClassLoader([tempDir.toUri().toURL()] as URL[], getClass().classLoader)
        Thread.currentThread().contextClassLoader = factoryClassLoader

        when:
        ApplicationCommand command = new ApplicationContextCommandRegistry().findCommand('provider-test')

        then:
        command instanceof ProviderTestCommand
    }

    def "instantiates the same provider class once across registry and context classloaders"() {
        given:
        ConstructorCountingCommandProvider.constructorCalls = 0
        File metaInf = new File(tempDir.toFile(), 'META-INF')
        assert metaInf.mkdirs()
        new File(metaInf, 'grails-cli.factories').text = "${ApplicationCommandProvider.name}=${ConstructorCountingCommandProvider.name}"
        originalContextClassLoader = Thread.currentThread().contextClassLoader
        factoryClassLoader = new URLClassLoader([tempDir.toUri().toURL()] as URL[], getClass().classLoader)
        Thread.currentThread().contextClassLoader = factoryClassLoader

        when:
        new ApplicationContextCommandRegistry()

        then:
        ConstructorCountingCommandProvider.constructorCalls == 1
    }

    def "instantiates a modern command once when distinct resources declare the same class"() {
        given:
        ConstructorCountingApplicationCommand.constructorCalls = 0
        new ApplicationContextCommandRegistry()
        int registryClassLoaderConstructions = ConstructorCountingApplicationCommand.constructorCalls
        ConstructorCountingApplicationCommand.constructorCalls = 0
        URL firstPlugin = createFactoryJar(
                'first-counting-command.jar',
                'example.FirstFactory=example.FirstImplementation',
                "${ApplicationCommand.name}=${ConstructorCountingApplicationCommand.name}")
        URL secondPlugin = createFactoryJar(
                'second-counting-command.jar',
                'example.SecondFactory=example.SecondImplementation',
                "${ApplicationCommand.name}=${ConstructorCountingApplicationCommand.name}")
        useFactoryResources([firstPlugin, secondPlugin])

        when:
        ApplicationContextCommandRegistry registry = new ApplicationContextCommandRegistry()

        then:
        registry.findCommand('counting-modern') instanceof ConstructorCountingApplicationCommand
        ConstructorCountingApplicationCommand.constructorCalls == registryClassLoaderConstructions + 1
    }

    def "ordered modern commands win duplicate-name collisions"() {
        given:
        URL plugin = createFactoryJar(
                'ordered-command.jar',
                'example.OtherFactory=example.OtherImplementation',
                "${ApplicationCommand.name}=${AUnorderedCollisionCommand.name},${ZOrderedCollisionCommand.name}")
        useFactoryResources([plugin])

        expect:
        new ApplicationContextCommandRegistry().findCommand('ordered-collision') instanceof ZOrderedCollisionCommand
    }

    def "continues loading providers after a linkage error"() {
        given:
        URL plugin = createFactoryJar(
                'linkage-provider.jar',
                'example.OtherFactory=example.OtherImplementation',
                "${ApplicationCommandProvider.name}=${LinkageErrorCommandProvider.name},${TestCommandProvider.name}")
        useFactoryResources([plugin])

        when:
        ApplicationCommand command = new ApplicationContextCommandRegistry().findCommand('provider-test')

        then:
        command instanceof ProviderTestCommand
    }

    def "reports an application command load-time linkage error with its factory origin and continues discovery"() {
        given:
        URL plugin = createFactoryJar(
                'load-time-linkage-command.jar',
                'example.OtherFactory=example.OtherImplementation',
                "${ApplicationCommand.name}=missing.LinkageCommand,${ModernTestCommand.name}")
        useFactoryResources([plugin], false,
                ['missing.LinkageCommand': new NoClassDefFoundError('simulated missing command dependency')])
        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream()

        when:
        ApplicationContextCommandRegistry registry = captureStandardError(errorOutput) {
            new ApplicationContextCommandRegistry()
        }

        then:
        registry.findCommand('modern-test') instanceof ModernTestCommand
        errorOutput.toString('UTF-8').contains('missing.LinkageCommand')
        errorOutput.toString('UTF-8').contains('load-time-linkage-command.jar')
        errorOutput.toString('UTF-8').contains('report it to the Grails framework')
    }

    @Unroll
    def "propagates load-time fatal error #failure.class.simpleName from application commands"() {
        given:
        URL plugin = createFactoryJar(
                'load-time-fatal-command.jar',
                'example.OtherFactory=example.OtherImplementation',
                "${ApplicationCommand.name}=missing.FatalCommand")
        useFactoryResources([plugin], false, ['missing.FatalCommand': failure])

        when:
        new ApplicationContextCommandRegistry()

        then:
        Throwable thrown = thrown(Throwable)
        thrown.is(failure)

        where:
        failure << [new StackOverflowError(), new ThreadDeath()]
    }

    def "reports a provider load-time linkage error with its factory origin and continues discovery"() {
        given:
        URL plugin = createFactoryJar(
                'load-time-linkage-provider.jar',
                'example.OtherFactory=example.OtherImplementation',
                "${ApplicationCommandProvider.name}=missing.LinkageProvider,${TestCommandProvider.name}")
        useFactoryResources([plugin], false,
                ['missing.LinkageProvider': new NoClassDefFoundError('simulated missing provider dependency')])
        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream()

        when:
        ApplicationContextCommandRegistry registry = captureStandardError(errorOutput) {
            new ApplicationContextCommandRegistry()
        }

        then:
        registry.findCommand('provider-test') instanceof ProviderTestCommand
        errorOutput.toString('UTF-8').contains('missing.LinkageProvider')
        errorOutput.toString('UTF-8').contains('load-time-linkage-provider.jar')
        errorOutput.toString('UTF-8').contains('report it to the Grails framework')
    }

    @Unroll
    def "propagates load-time fatal error #failure.class.simpleName from command providers"() {
        given:
        URL plugin = createFactoryJar(
                'load-time-fatal-provider.jar',
                'example.OtherFactory=example.OtherImplementation',
                "${ApplicationCommandProvider.name}=missing.FatalProvider")
        useFactoryResources([plugin], false, ['missing.FatalProvider': failure])

        when:
        new ApplicationContextCommandRegistry()

        then:
        Throwable thrown = thrown(Throwable)
        thrown.is(failure)

        where:
        failure << [new StackOverflowError(), new ThreadDeath()]
    }

    @Unroll
    def "propagates direct fatal error #failure.class.simpleName from command providers"() {
        given:
        DirectFatalCommandProvider.failure = failure
        URL plugin = createFactoryJar(
                'direct-fatal-provider.jar',
                'example.OtherFactory=example.OtherImplementation',
                "${ApplicationCommandProvider.name}=${DirectFatalCommandProvider.name}")
        useFactoryResources([plugin])

        when:
        new ApplicationContextCommandRegistry()

        then:
        Throwable thrown = thrown(Throwable)
        thrown.is(failure)

        where:
        failure << [new StackOverflowError(), new ThreadDeath()]
    }

    @Unroll
    def "propagates reflection-wrapped fatal error #failure.class.simpleName from command provider constructors"() {
        given:
        ConstructorFatalCommandProvider.failure = failure
        URL plugin = createFactoryJar(
                'constructor-fatal-provider.jar',
                'example.OtherFactory=example.OtherImplementation',
                "${ApplicationCommandProvider.name}=${ConstructorFatalCommandProvider.name}")
        useFactoryResources([plugin])

        when:
        new ApplicationContextCommandRegistry()

        then:
        Throwable thrown = thrown(Throwable)
        thrown.is(failure)

        where:
        failure << [new StackOverflowError(), new ThreadDeath()]
    }

    def "reports each legacy command plugin once without loading its command classes"() {
        given:
        URL firstPlugin = createFactoryJar('first-plugin.jar',
                'grails.dev.commands.ApplicationCommand=missing.FirstLegacyCommand')
        URL secondPlugin = createFactoryJar('second-plugin.jar',
                'grails.dev.commands.ApplicationCommand=missing.SecondLegacyCommand')
        useFactoryResources([firstPlugin, secondPlugin], true)
        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream()

        when:
        ApplicationContextCommandRegistry registry = captureStandardError(errorOutput) {
            new ApplicationContextCommandRegistry()
        }

        then:
        registry.missingCommandHint == 'Grails 7 commands were detected; set grails { legacyCommandSupport = true } or upgrade the plugin.'
        !((TrackingFactoryClassLoader) factoryClassLoader).legacyCommandClassLoaded
        List<String> errors = errorOutput.toString('UTF-8').readLines().findAll {
            it.contains('ships Grails 7 commands')
        }
        errors.size() == 2
        errors.count { it.contains('first-plugin.jar') } == 1
        errors.count { it.contains('second-plugin.jar') } == 1
        errors.every {
            it.contains('set grails { legacyCommandSupport = true } or upgrade the plugin.')
        }
    }

    def "does not report legacy command diagnostics when no legacy factory key exists"() {
        given:
        URL plugin = createFactoryJar('modern-plugin.jar', 'example.OtherFactory=missing.OtherFactory')
        useFactoryResources([plugin])
        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream()

        when:
        ApplicationContextCommandRegistry registry = captureStandardError(errorOutput) {
            new ApplicationContextCommandRegistry()
        }

        then:
        registry.missingCommandHint == null
        !errorOutput.toString('UTF-8').contains('ships Grails 7 commands')
    }

    def "continues command discovery when a factory resource is malformed"() {
        given:
        String malformedFactory = 'grails.dev.commands.ApplicationCommand=' + '\\' + 'uInvalid'
        URL plugin = createFactoryJar('malformed-plugin.jar', malformedFactory)
        useFactoryResources([plugin])

        when:
        ApplicationContextCommandRegistry registry = new ApplicationContextCommandRegistry()

        then:
        registry.missingCommandHint == null
        noExceptionThrown()
    }

    def "continues command discovery when factory resources cannot be enumerated"() {
        given:
        originalContextClassLoader = Thread.currentThread().contextClassLoader
        factoryClassLoader = new ThrowingFactoryResourcesClassLoader(getClass().classLoader)
        Thread.currentThread().contextClassLoader = factoryClassLoader

        when:
        ApplicationContextCommandRegistry registry = new ApplicationContextCommandRegistry()

        then:
        registry.missingCommandHint == null
        noExceptionThrown()
    }

    def "uses the resource URL when plugin origin resolution fails"() {
        given:
        URL factoryResource = new URL(null, 'memory:broken-origin/META-INF/grails.factories',
                new FailingOriginUrlStreamHandler())
        originalContextClassLoader = Thread.currentThread().contextClassLoader
        factoryClassLoader = new FixedFactoryResourceClassLoader(factoryResource, getClass().classLoader)
        Thread.currentThread().contextClassLoader = factoryClassLoader
        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream()

        when:
        ApplicationContextCommandRegistry registry = captureStandardError(errorOutput) {
            new ApplicationContextCommandRegistry()
        }

        then:
        registry.missingCommandHint != null
        errorOutput.toString('UTF-8').contains('Plugin memory:broken-origin/META-INF/grails.factories ships Grails 7 commands')
    }

    def "does not report legacy command diagnostics when a provider handles the factory key"() {
        given:
        URL plugin = createFactoryJar(
                'supported-plugin.jar',
                'grails.dev.commands.ApplicationCommand=missing.SupportedLegacyCommand',
                "${ApplicationCommandProvider.name}=${DiagnosticSupportCommandProvider.name}")
        useFactoryResources([plugin])
        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream()

        when:
        ApplicationContextCommandRegistry registry = captureStandardError(errorOutput) {
            new ApplicationContextCommandRegistry()
        }

        then:
        registry.missingCommandHint == null
        !errorOutput.toString('UTF-8').contains('ships Grails 7 commands')
    }

    def "does not report missing support when a factory-key provider fails to contribute commands"() {
        given:
        URL plugin = createFactoryJar(
                'failing-supported-plugin.jar',
                'grails.dev.commands.ApplicationCommand=missing.SupportedLegacyCommand',
                "${ApplicationCommandProvider.name}=${ThrowingDiagnosticSupportCommandProvider.name}")
        useFactoryResources([plugin])
        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream()

        when:
        ApplicationContextCommandRegistry registry = captureStandardError(errorOutput) {
            new ApplicationContextCommandRegistry()
        }

        then:
        registry.missingCommandHint == null
        !errorOutput.toString('UTF-8').contains('ships Grails 7 commands')
    }

    private URL createFactoryJar(String name, String legacyFactories, String cliFactories = null) {
        Path jar = tempDir.resolve(name)
        new JarOutputStream(Files.newOutputStream(jar)).withCloseable { JarOutputStream output ->
            writeEntry(output, 'META-INF/grails.factories', legacyFactories)
            if (cliFactories != null) {
                writeEntry(output, 'META-INF/grails-cli.factories', cliFactories)
            }
        }
        jar.toUri().toURL()
    }

    private static void writeEntry(JarOutputStream output, String name, String content) {
        output.putNextEntry(new JarEntry(name))
        output.write(content.getBytes('UTF-8'))
        output.closeEntry()
    }

    private void useFactoryResources(
            List<URL> resources,
            boolean duplicateResources = false,
            Map<String, Throwable> loadFailures = [:]) {
        originalContextClassLoader = Thread.currentThread().contextClassLoader
        factoryClassLoader = new TrackingFactoryClassLoader(
                resources as URL[], getClass().classLoader, duplicateResources, loadFailures)
        Thread.currentThread().contextClassLoader = factoryClassLoader
    }

    private static <T> T captureStandardError(ByteArrayOutputStream output, Closure<T> action) {
        PrintStream originalError = System.err
        try {
            System.err = new PrintStream(output, true, 'UTF-8')
            action.call()
        }
        finally {
            System.err = originalError
        }
    }
}

class ThrowingCommandProvider implements ApplicationCommandProvider {

    ThrowingCommandProvider() {
        throw new RuntimeException('boom')
    }

    @Override
    void contributeCommands(ClassLoader registryClassLoader, ClassLoader contextClassLoader, ApplicationCommandRegistrar registrar) {
    }
}

class TestCommandProvider implements ApplicationCommandProvider {

    @Override
    void contributeCommands(ClassLoader registryClassLoader, ClassLoader contextClassLoader, ApplicationCommandRegistrar registrar) {
        registrar.register(new ProviderTestCommand())
    }
}

class ProviderTestCommand implements ApplicationCommand {

    @Override
    String getName() {
        'provider-test'
    }

    @Override
    boolean handle(ExecutionContext executionContext) {
        true
    }
}

class ModernTestCommand implements ApplicationCommand {

    @Override
    String getName() {
        'modern-test'
    }

    @Override
    String getDescription() {
        'Modern test command'
    }

    @Override
    boolean handle(ExecutionContext executionContext) {
        true
    }
}

class ConstructorCountingApplicationCommand implements ApplicationCommand {

    static int constructorCalls

    ConstructorCountingApplicationCommand() {
        constructorCalls++
    }

    @Override
    String getName() {
        'counting-modern'
    }

    @Override
    boolean handle(ExecutionContext executionContext) {
        true
    }
}

class AUnorderedCollisionCommand implements ApplicationCommand {

    @Override
    String getName() {
        'ordered-collision'
    }

    @Override
    boolean handle(ExecutionContext executionContext) {
        true
    }
}

class ZOrderedCollisionCommand implements ApplicationCommand, Ordered {

    @Override
    int getOrder() {
        Ordered.HIGHEST_PRECEDENCE
    }

    @Override
    String getName() {
        'ordered-collision'
    }

    @Override
    boolean handle(ExecutionContext executionContext) {
        true
    }
}

class ConstructorCountingCommandProvider implements ApplicationCommandProvider {

    static int constructorCalls

    ConstructorCountingCommandProvider() {
        constructorCalls++
    }

    @Override
    void contributeCommands(ClassLoader registryClassLoader, ClassLoader contextClassLoader, ApplicationCommandRegistrar registrar) {
    }
}

class LinkageErrorCommandProvider implements ApplicationCommandProvider {

    LinkageErrorCommandProvider() {
        throw new NoClassDefFoundError('simulated missing provider dependency')
    }

    @Override
    void contributeCommands(ClassLoader registryClassLoader, ClassLoader contextClassLoader, ApplicationCommandRegistrar registrar) {
    }
}

class DirectFatalCommandProvider implements ApplicationCommandProvider {

    static Throwable failure

    @Override
    void contributeCommands(ClassLoader registryClassLoader, ClassLoader contextClassLoader, ApplicationCommandRegistrar registrar) {
        throw failure
    }
}

class ConstructorFatalCommandProvider implements ApplicationCommandProvider {

    static Throwable failure

    ConstructorFatalCommandProvider() {
        throw failure
    }

    @Override
    void contributeCommands(ClassLoader registryClassLoader, ClassLoader contextClassLoader, ApplicationCommandRegistrar registrar) {
    }
}

class DiagnosticSupportCommandProvider implements ApplicationCommandFactoryKeyProvider {

    @Override
    Collection<String> getHandledFactoryKeys() {
        ['grails.dev.commands.ApplicationCommand']
    }

    @Override
    void contributeCommands(ClassLoader registryClassLoader, ClassLoader contextClassLoader, ApplicationCommandRegistrar registrar) {
    }
}

class ThrowingDiagnosticSupportCommandProvider extends DiagnosticSupportCommandProvider {

    @Override
    void contributeCommands(ClassLoader registryClassLoader, ClassLoader contextClassLoader, ApplicationCommandRegistrar registrar) {
        throw new IllegalStateException('boom')
    }
}

@CompileStatic
class TrackingFactoryClassLoader extends URLClassLoader {

    private final boolean duplicateResources
    private final Map<String, Throwable> loadFailures
    private boolean legacyCommandClassLoaded

    TrackingFactoryClassLoader(
            URL[] urls,
            ClassLoader parent,
            boolean duplicateResources,
            Map<String, Throwable> loadFailures) {
        super(urls, parent)
        this.duplicateResources = duplicateResources
        this.loadFailures = loadFailures
    }

    @Override
    Enumeration<URL> getResources(String name) throws IOException {
        List<URL> resources = Collections.list(super.getResources(name))
        if (duplicateResources && name == 'META-INF/grails.factories') {
            resources.addAll(new ArrayList<>(resources))
        }
        Collections.enumeration(resources)
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Throwable failure = loadFailures[name]
        if (failure != null) {
            throw failure
        }
        if (name.startsWith('missing.')) {
            legacyCommandClassLoaded = true
        }
        super.loadClass(name, resolve)
    }

    boolean isLegacyCommandClassLoaded() {
        legacyCommandClassLoaded
    }
}

@CompileStatic
class ThrowingFactoryResourcesClassLoader extends URLClassLoader {

    ThrowingFactoryResourcesClassLoader(ClassLoader parent) {
        super([] as URL[], parent)
    }

    @Override
    Enumeration<URL> getResources(String name) throws IOException {
        if (name == 'META-INF/grails.factories') {
            throw new AssertionError('unavailable')
        }
        super.getResources(name)
    }
}

@CompileStatic
class FixedFactoryResourceClassLoader extends URLClassLoader {

    private final URL factoryResource

    FixedFactoryResourceClassLoader(URL factoryResource, ClassLoader parent) {
        super([] as URL[], parent)
        this.factoryResource = factoryResource
    }

    @Override
    Enumeration<URL> getResources(String name) throws IOException {
        name == 'META-INF/grails.factories' ?
                Collections.enumeration([factoryResource]) : super.getResources(name)
    }
}

@CompileStatic
class FailingOriginUrlStreamHandler extends URLStreamHandler {

    private int connectionCount

    @Override
    protected URLConnection openConnection(URL url) throws IOException {
        connectionCount++
        if (connectionCount > 1) {
            throw new AssertionError('origin unavailable')
        }
        new URLConnection(url) {
            @Override
            void connect() {
            }

            @Override
            InputStream getInputStream() {
                new ByteArrayInputStream(
                        'grails.dev.commands.ApplicationCommand=missing.LegacyCommand'.getBytes('UTF-8'))
            }
        }
    }
}

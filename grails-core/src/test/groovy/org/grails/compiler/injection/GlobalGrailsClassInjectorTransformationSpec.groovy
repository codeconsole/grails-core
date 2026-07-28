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
package org.grails.compiler.injection

import groovy.xml.XmlSlurper
import org.codehaus.groovy.GroovyBugError
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.CompilationCustomizer

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir
import spock.util.environment.RestoreSystemProperties

import grails.artefact.Artefact
import grails.plugins.metadata.GrailsPlugin
import grails.util.GrailsNameUtils
import org.apache.grails.common.compiler.GroovyTransformOrder

class GlobalGrailsClassInjectorTransformationSpec extends Specification {

    @TempDir
    File tempDir

    @Subject
    def transformation = new GlobalGrailsClassInjectorTransformation()

    void "a correct plugin xml file is generated when the plugin xml doesn't exist"() {
        given: "a file that doesn't yet exist"
            def pluginXml = new File(tempDir, 'plugin-xml-gen-test.test.xml')

        and: "the class node for a plugin descriptor"
            def classNode = compilePlugin('class FooGrailsPlugin {}')

        expect: "the file doesn't exist"
            !pluginXml.exists()

        when: "the transformation generates the xml file"
            transformation.generatePluginXml(
                    classNode,
                    '1.0',
                    ['Foo'] as Set,
                    pluginXml
            )

        then: "the file exists"
            pluginXml.exists()

        when: "the xml is parsed"
            def xml = new XmlSlurper().parse(pluginXml)

        then: "the generated xml is valid"
            xml.@name.text() == 'foo'
            xml.type.text() == 'FooGrailsPlugin'
            xml.resources.size() == 1
            xml.resources.resource.text() == 'Foo'
    }

    void "a correct plugin xml file is updated when the plugin xml does exist"() {
        given: "a file that doesn't yet exist"
            def pluginXml = File.createTempFile('plugin-xml-gen-test', '.test.xml', tempDir)
            def classNode = compilePlugin('class BarGrailsPlugin {}')
            pluginXml.text = '''
                <plugin name="foo">
                    <type>FooGrailsPlugin</type>
                    <resources>
                        <resource>Foo</resource>
                        <resource>Bar</resource>
                        <resource>Baz</resource>
                    </resources>
                </plugin>
            '''

        expect: "the file does exist"
            pluginXml.exists()

        when: "the transformation generates the plugin.xml"
            transformation.generatePluginXml(
                    classNode,
                    '1.0',
                    ['Foo', 'Bar'] as Set,
                    pluginXml
            )

        then: "the file exists"
            pluginXml.exists()

        when: "the xml is parsed"
            def xml = new XmlSlurper().parse(pluginXml)

        then: "the generated plugin.xml is valid"
            xml.@name.text() == 'bar'
            xml.type.text() == 'BarGrailsPlugin'
            xml.resources.resource.size() == 3
            xml.resources.resource.text() == 'FooBarBaz'
    }

    @RestoreSystemProperties
    void "isIsolatedBuild reflects the 'grails.isolated.build' system property"() {
        when:
            System.setProperty('grails.isolated.build', value)

        then:
            GlobalGrailsClassInjectorTransformation.isIsolatedBuild() == expected

        where:
            value   || expected
            'true'  || true
            'false' || false
            'TRUE'  || true
            'y'     || true
            'null'  || false
            '1'     || true
            '0'     || false
            '-1'    || false
            ''      || false
    }

    void "resolveCompilationTargetDirectory returns the configured target directory"() {
        given:
            def targetDir = new File(tempDir, 'isolated-target/build/classes/groovy/main')
            def source = sourceUnitWithTarget(targetDir)

        expect: "the configured directory is used regardless of build isolation"
            GlobalGrailsClassInjectorTransformation.resolveCompilationTargetDirectory(source, false) == targetDir
            GlobalGrailsClassInjectorTransformation.resolveCompilationTargetDirectory(source, true) == targetDir
    }

    void "resolveCompilationTargetDirectory falls back to the shared relative path for a non-isolated build"() {
        given: "a source unit without a configured target directory"
            def source = sourceUnitWithTarget(null)

        when:
            def resolvedDir = GlobalGrailsClassInjectorTransformation.resolveCompilationTargetDirectory(source, false)

        then: "the legacy relative fallback is used"
            resolvedDir == new File('build/classes/main')
    }

    void "resolveCompilationTargetDirectory fails fast instead of falling back for an isolated build"() {
        given: "a source unit without a configured target directory"
            def source = sourceUnitWithTarget(null)

        when: "the target directory cannot be resolved in an isolated build"
            GlobalGrailsClassInjectorTransformation.resolveCompilationTargetDirectory(source, true)

        then: "the build fails loudly rather than writing to a shared location"
            def e = thrown(IllegalStateException)
            e.message.contains('grails.isolated.build')
    }

    @RestoreSystemProperties
    void "findSourceDirectory prefers the per-project base.dir system property when set"() {
        given: "base.dir points at an existing directory"
            System.setProperty('base.dir', tempDir.absolutePath)
            def targetDir = new File(tempDir, 'build/classes/groovy/main')

        when:
            def resolvedDir = FactoriesFileWriter.findSourceDirectory(targetDir)

        then: "the build-tool supplied base.dir wins"
            resolvedDir == tempDir
    }

    @RestoreSystemProperties
    void "findSourceDirectory walks up to the project directory when base.dir is not set"() {
        given: "no base.dir and a standard per-project compile target"
            System.clearProperty('base.dir')
            def targetDir = new File(tempDir, 'build/classes/groovy/main')

        when:
            def resolvedDir = FactoriesFileWriter.findSourceDirectory(targetDir)

        then: "it resolves to the parent of the build directory"
            resolvedDir == tempDir
    }

    void "priority returns the global grails transform order"() {
        expect:
            new GlobalGrailsClassInjectorTransformation().priority() == GroovyTransformOrder.GLOBAL_GRAILS_TRANSFORM_ORDER
    }

    void "the global transform ignores a source without a resolvable URL"() {
        when:
            new GlobalGrailsClassInjectorTransformation().visit([] as ASTNode[], Stub(SourceUnit) {
                getName() >> null
            })

        then:
            noExceptionThrown()
    }

    void "the global transform stamps a GrailsPlugin descriptor class with a version property"() {
        given: "a *GrailsPlugin class with no explicit version property, and nowhere for plugin.xml to exist yet"
            def sourceFile = new File(tempDir, 'PlainGrailsPlugin.groovy')
            def targetDir = new File(tempDir, 'build/classes/groovy/main')

        when: "the source is compiled, exercising the registered global transform"
            def classNode = compileToFile(
                    sourceFile,
                    'class PlainGrailsPlugin {}',
                    targetDir, [projectVersion: '1.5']
            )

        then: "the plugin class is stamped with the resolved version"
            classNode.getProperty('version') != null

        and: "the plugin.xml describing it is generated as a side effect"
            new File(targetDir, 'META-INF/grails-plugin.xml').exists()
    }

    void "the global transform resolves a plugin version declared on the plugin class"() {
        given: "a plugin descriptor with a declared version and no compiler project metadata"
            def sourceFile = new File(tempDir, 'DeclaredGrailsPlugin.groovy')
            def targetDir = new File(tempDir, 'build/classes/groovy/main')

        when:
            def classNode = compileToFile(
                    sourceFile,
                    '''
                        class DeclaredGrailsPlugin {
                            def version = '3.0'
                        }
                    ''',
                    targetDir
            )

        then:
            classNode.getProperty('version').initialExpression.text == '3.0'
            new File(targetDir, 'META-INF/grails-plugin.xml').exists()
    }

    void "the global transform fails when a plugin descriptor class has no version"() {
        given: "a plugin descriptor class without a declared or compiler-provided version"
            def sourceFile = new File(tempDir, 'UnversionedGrailsPlugin.groovy')
            def targetDir = new File(tempDir, 'build/classes/groovy/main')

        when:
            compileToFile(sourceFile, 'class UnversionedGrailsPlugin {}', targetDir)

        then:
            def exception = thrown(GroovyBugError)
            with(exception) {
                cause instanceof IllegalStateException
                cause.message.contains('does not define a plugin version')
            }
    }

    void "the global transform annotates a plain Grails resource class with @GrailsPlugin metadata"() {
        given: "a class under grails-app that isn't matched by any registered ArtefactHandler"
            def sourceFile = new File(tempDir, 'grails-app/services/FooWidget.groovy')
            def targetDir = new File(tempDir, 'build/classes/groovy/main')

        when: "the source is compiled, exercising the registered global transform"
            def classNode = compileToFile(
                    sourceFile,
                    'class FooWidget {}',
                    targetDir,
                    [projectName: 'foowidget', projectVersion: '2.0']
            )

        then: "the class is stamped with the project's @GrailsPlugin metadata"
            def annotations = classNode.getAnnotations(ClassHelper.make(GrailsPlugin))
            annotations.size() == 1
            with(annotations.first()) {
                getMember('name').text == GrailsNameUtils.getPropertyNameForLowerCaseHyphenSeparatedName('foowidget')
                getMember('version').text == '2.0'
            }
    }

    void "the global transform processes a Grails service as an artefact"() {
        given: "a service source under grails-app"
            def sourceFile = new File(tempDir, 'grails-app/services/FooService.groovy')
            def targetDir = new File(tempDir, 'build/classes/groovy/main')
            TraitInjectionUtils.@traitInjectors = []

        when:
            def classNode = compileToFile(
                    sourceFile,
                    'class FooService {}',
                    targetDir
            )

        then:
            with(classNode.getAnnotations(ClassHelper.make(Artefact))) {
                size() == 1
                first().getMember('value').text == 'Service'
            }

        cleanup:
            TraitInjectionUtils.@traitInjectors = null
    }

    void "the global transform registers a concrete artefact handler in grails.factories"() {
        given: "an artefact handler source"
            def sourceFile = new File(tempDir, 'src/main/groovy/TestHandler.groovy')
            def targetDir = new File(tempDir, 'build/classes/groovy/main')

        when:
            compileToFile(
                    sourceFile,
                    '''
                        class TestHandler extends grails.core.ArtefactHandlerAdapter {
                            TestHandler() {
                                super('Test', null, null, 'Handler')
                            }
                        }
                    ''',
                    targetDir
            )

        then:
            def factories = new File(targetDir, 'META-INF/grails.factories')
            factories.exists()
            factories.text.contains('TestHandler')
    }

    void "the global transform skips classes whose source falls outside the Grails resource patterns"() {
        given: "a plain source under src/main/groovy, which is project source but not a Grails resource"
            def sourceFile = new File(tempDir, 'src/main/groovy/PlainClass.groovy')
            def targetDir = new File(tempDir, 'build/classes/groovy/main')

        when: "the source is compiled, exercising the registered global transform"
            def classNode = compileToFile(
                    sourceFile,
                    'class PlainClass {}',
                    targetDir,
                    [projectName: 'plain', projectVersion: '1.0']
            )

        then: "no @GrailsPlugin metadata is stamped on the class"
            classNode.getAnnotations(ClassHelper.make(GrailsPlugin)).empty
    }

    void "plugin xml excludes are honoured and metadata refreshed when updating an existing file"() {
        given: "an existing plugin.xml with a resource that the plugin now wants excluded"
            def pluginXml = File.createTempFile('plugin-xml-excludes', 'test.xml', tempDir)
            def classNode = null
            def cu = new CompilationUnit(new GroovyClassLoader())
            cu.addSource('BazGrailsPlugin', '''
                class BazGrailsPlugin {
                    def pluginExcludes = ['Excluded*']
                    def grailsVersion = '3.0 > *'
                }
            ''')
            cu.addPhaseOperation({ SourceUnit source, GeneratorContext context, ClassNode cn ->
                if (cn.name.endsWith('GrailsPlugin')) {
                    classNode = cn
                }
            } as CompilationUnit.IPrimaryClassNodeOperation, Phases.CONVERSION)
            cu.compile(Phases.CONVERSION)
            pluginXml.text = '''
                <plugin name="baz" version="1.0" grailsVersion="1.0 > *">
                    <type>BazGrailsPlugin</type>
                    <resources>
                        <resource>ExcludedThing</resource>
                        <resource>ExistingThing</resource>
                    </resources>
                </plugin>
            '''

        when: "the transformation updates the plugin.xml"
            transformation.generatePluginXml(
                    classNode,
                    '2.0',
                    ['ExcludedThing', 'NewThing'] as Set,
                    pluginXml
            )

        then: "the file exists"
            pluginXml.exists()

        when: "the xml is parsed"
            def xml = new XmlSlurper().parse(pluginXml)

        then: "the excluded resource was removed, the kept resource was added, and metadata was refreshed"
            xml.@version.text() == '2.0'
            xml.@grailsVersion.text() == '3.0 > *'
            xml.resources.resource*.text() == ['ExistingThing', 'NewThing']
    }

    void "plugin xml excludes are applied when writing a new descriptor"() {
        given:
            def pluginXml = new File(tempDir, 'plugin-xml-write-excludes.xml')
            def classNode = compilePlugin('''
                class WrittenExcludesGrailsPlugin {
                    def pluginExcludes = ['Excluded*']
                    def grailsVersion = '4.0 > *'
                }
            ''')

        when:
            transformation.generatePluginXml(
                    classNode,
                    '1.0',
                    ['ExcludedThing', 'KeptThing'] as Set,
                    pluginXml
            )

        then:
            new XmlSlurper().parse(pluginXml).resources.resource*.text() == ['KeptThing']
    }

    void "plugin xml resources are updated when an existing descriptor has no plugin class"() {
        given:
            def pluginXml = new File(tempDir, 'existing-plugin.xml')
            pluginXml.text = '''
                <plugin>
                    <resources>
                        <resource>ExistingThing</resource>
                    </resources>
                </plugin>
            '''

        when:
            transformation.generatePluginXml(
                    null,
                    null,
                    ['NewThing'] as Set,
                    pluginXml
            )

        then:
            new XmlSlurper().parse(pluginXml).resources.resource*.text() == ['ExistingThing', 'NewThing']
    }

    void "plugin xml update recreates safely when the existing descriptor is malformed"() {
        given:
            def logger = LoggerFactory.getLogger(GlobalGrailsClassInjectorTransformation) as Logger
            def appender = new ListAppender<ILoggingEvent>().tap { start() }
            logger.addAppender(appender)

        and: 'a malformed plugin.xml that cannot be parsed'
            def pluginXml = new File(tempDir, 'malformed-plugin.xml')
            pluginXml.text = '<plugin><resources>'

        when: 'the transformation attempts to update the malformed plugin.xml'
            transformation.updatePluginXml(null, null, pluginXml, ['Foo'])

        then: 'no exception is thrown and a warning is logged'
            noExceptionThrown()
            appender.list.size() == 1
            with(appender.list[0]) {
                level == Level.WARN
                formattedMessage == "Failed to update existing file ${pluginXml.absolutePath}. Recreating it instead..."
            }

        cleanup:
            logger.detachAppender(appender)
            appender.stop()
    }

    private SourceUnit sourceUnitWithTarget(File targetDirectory) {
        def cc = new CompilerConfiguration()
        cc.setTargetDirectory((File) targetDirectory)
        Stub(SourceUnit) {
            getConfiguration() >> cc
            getName() >> 'TestSource'
        }
    }

    private static ClassNode compilePlugin(String pluginSource) {
        def classNode = null
        def cu = new CompilationUnit(new GroovyClassLoader())
        cu.addSource('GrailsPlugin', pluginSource)
        cu.addPhaseOperation({ SourceUnit source, GeneratorContext context, ClassNode cn ->
            //if (cn.name.endsWith('GrailsPlugin')) {
                classNode = cn
            //}
        } as CompilationUnit.IPrimaryClassNodeOperation, Phases.CONVERSION)
        cu.compile(Phases.CONVERSION)
        classNode
    }

    /**
     * Compiles the given source to a real file on disk (required so {@code GrailsASTUtils.getSourceUrl}
     * resolves a URL). Because {@code GlobalGrailsClassInjectorTransformation} is itself registered as a
     * global AST transformation (via {@code META-INF/services}) and grails-core's own compiled classes are
     * on this test's classpath, compiling all the way through {@code CANONICALIZATION} exercises the real
     * transformation exactly as production Grails builds do - no manual {@code visit()} call is needed.
     * {@code nodeMetaData} is stamped onto the class during {@code CONVERSION}, before the transformation's
     * own {@code CANONICALIZATION} pass runs, mirroring how the Grails Gradle plugin stamps project
     * name/version metadata via its own compiler customizer.
     */
    private static ClassNode compileToFile(File sourceFile, String source, File targetDirectory, Map<String, String> nodeMetaData = [:]) {
        sourceFile.parentFile.mkdirs()
        sourceFile.text = source
        def configuration = new CompilerConfiguration(targetDirectory: targetDirectory)
        if (nodeMetaData) {
            configuration.addCompilationCustomizers(new CompilationCustomizer(CompilePhase.CONVERSION) {
                @Override
                void call(SourceUnit source1, GeneratorContext context, ClassNode cn) throws CompilationFailedException {
                    nodeMetaData.each {
                        cn.putNodeMetaData(it.key, it.value)
                    }
                }
            })
        }
        def cu = new CompilationUnit(configuration)
        cu.addSource(sourceFile)
        def capturedClassNode = null
        cu.addPhaseOperation({ SourceUnit source1, GeneratorContext context, ClassNode cn ->
            capturedClassNode = cn
        } as CompilationUnit.IPrimaryClassNodeOperation, Phases.CANONICALIZATION)
        cu.compile(Phases.CANONICALIZATION)
        capturedClassNode
    }
}

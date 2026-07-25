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

import groovy.xml.MarkupBuilder
import groovy.xml.XmlSlurper
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
import spock.lang.Specification
import spock.util.environment.RestoreSystemProperties

import grails.plugins.metadata.GrailsPlugin
import grails.util.GrailsNameUtils
import org.apache.grails.common.compiler.GroovyTransformOrder

/**
 * Created by graemerocher on 19/09/14.
 */
class GlobalGrailsClassInjectorTransformationSpec extends Specification {

    void "Test that a correct plugin dot xml file is generated when the plugin dot xml doesn't exist"() {
        given:"A file that doesn't yet exist"
            File pluginXml = new File(System.getProperty("java.io.tmpdir"), "plugin-xml-gen-test.test.xml")
            pluginXml.delete()
            ClassNode classNode = null
            CompilationUnit cu = new CompilationUnit(new GroovyClassLoader())
            cu.addSource("FooGrailsPlugin", '''
class FooGrailsPlugin {
}
''')
            cu.addPhaseOperation(new CompilationUnit.PrimaryClassNodeOperation() {
                @Override
                void call(SourceUnit source, GeneratorContext context, ClassNode cn) throws CompilationFailedException {
                    if(cn.name.endsWith("GrailsPlugin")) {
                         classNode = cn
                    }
                }
            },Phases.CONVERSION)
            cu.compile(Phases.CONVERSION)


        expect:"the file doesn't exist"
            !pluginXml.exists()

        when:"the transformation generates the plugin.xml"
            def transformation = new GlobalGrailsClassInjectorTransformation()
            transformation.generatePluginXml(classNode,"1.0", ['Foo'] as Set, pluginXml)

        then:"the file exists"
            pluginXml.exists()

        when:"the xml is parsed"
            def xml = new XmlSlurper().parse(pluginXml)

        then:"The generated plugin.xml is valid"
            xml.@name.text() == "foo"
            xml.type.text() == "FooGrailsPlugin"
            xml.resources.size() == 1
            xml.resources.resource.text() == "Foo"
    }

    void "Test that a correct plugin dot xml file is updated when the plugin dot xml does exist"() {
        given:"A file that doesn't yet exist"
            File pluginXml = File.createTempFile("plugin-xml-gen", "test.xml")
            ClassNode classNode = null
            CompilationUnit cu = new CompilationUnit(new GroovyClassLoader())
            cu.addSource("BarGrailsPlugin", '''
    class BarGrailsPlugin {
    }
    ''')
            cu.addPhaseOperation(new CompilationUnit.PrimaryClassNodeOperation() {
                @Override
                void call(SourceUnit source, GeneratorContext context, ClassNode cn) throws CompilationFailedException {
                    if(cn.name.endsWith("GrailsPlugin")) {
                        classNode = cn
                    }
                }
            },Phases.CONVERSION)
            cu.compile(Phases.CONVERSION)
            pluginXml.withWriter { writer ->

                def mkp = new MarkupBuilder(writer)
                mkp.plugin(name:"foo") {
                    type "FooGrailsPlugin"
                    resources {
                        resource "Foo"
                        resource "Bar"
                    }
                }
            }
        expect:"the file does exist"
            pluginXml.exists()

        when:"the transformation generates the plugin.xml"
            def transformation = new GlobalGrailsClassInjectorTransformation()
            transformation.generatePluginXml(classNode, "1.0", ['Foo', "Bar"] as Set, pluginXml)

        then:"the file exists"
            pluginXml.exists()

        when:"the xml is parsed"
            def xml = new XmlSlurper().parse(pluginXml)

        then:"The generated plugin.xml is valid"
            xml.@name.text() == "bar"
            xml.type.text() == "BarGrailsPlugin"
            xml.resources.resource.size() == 2
            xml.resources.resource.text() == "FooBar"
    }

    @RestoreSystemProperties
    void "isIsolatedBuild reflects the grails.isolated.build system property"() {
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

    private SourceUnit sourceUnitWithTarget(File targetDirectory) {
        def configuration = new CompilerConfiguration()
        configuration.setTargetDirectory((File) targetDirectory)
        Stub(SourceUnit) {
            getConfiguration() >> configuration
            getName() >> 'TestSource'
        }
    }

    void "resolveCompilationTargetDirectory returns the configured target directory"() {
        given:
            File target = new File(System.getProperty('java.io.tmpdir'), 'isolated-target/build/classes/groovy/main')
            def source = sourceUnitWithTarget(target)

        expect: "the configured directory is used regardless of build isolation"
            GlobalGrailsClassInjectorTransformation.resolveCompilationTargetDirectory(source, false) == target
            GlobalGrailsClassInjectorTransformation.resolveCompilationTargetDirectory(source, true) == target
    }

    void "resolveCompilationTargetDirectory falls back to the shared relative path for a non-isolated build"() {
        given: "a source unit without a configured target directory"
            def source = sourceUnitWithTarget(null)

        when:
            File resolved = GlobalGrailsClassInjectorTransformation.resolveCompilationTargetDirectory(source, false)

        then: "the legacy relative fallback is used"
            resolved == new File('build/classes/main')
    }

    void "resolveCompilationTargetDirectory fails fast instead of falling back for an isolated build"() {
        given: "a source unit without a configured target directory"
            def source = sourceUnitWithTarget(null)

        when: "the target directory cannot be resolved in an isolated build"
            GlobalGrailsClassInjectorTransformation.resolveCompilationTargetDirectory(source, true)

        then: "the build fails loudly rather than writing to a shared location"
            IllegalStateException e = thrown()
            e.message.contains(GlobalGrailsClassInjectorTransformation.ISOLATED_BUILD_PROPERTY)
    }

    @RestoreSystemProperties
    void "findSourceDirectory prefers the per-project base.dir system property when set"() {
        given: "base.dir points at an existing directory"
            File baseDir = File.createTempDir()
            System.setProperty('base.dir', baseDir.absolutePath)
            File target = new File(baseDir, 'build/classes/groovy/main')

        when:
            File resolved = FactoriesFileWriter.findSourceDirectory(target)

        then: "the build-tool supplied base.dir wins"
            resolved == baseDir

        cleanup:
            baseDir.deleteDir()
    }

    @RestoreSystemProperties
    void "findSourceDirectory walks up to the project directory when base.dir is not set"() {
        given: "no base.dir and a standard per-project compile target"
            System.clearProperty('base.dir')
            File projectDir = File.createTempDir()
            File target = new File(projectDir, 'build/classes/groovy/main')

        when:
            File resolved = FactoriesFileWriter.findSourceDirectory(target)

        then: "it resolves to the parent of the build directory"
            resolved == projectDir

        cleanup:
            projectDir.deleteDir()
    }

    void "priority returns the global grails transform order"() {
        expect:
            new GlobalGrailsClassInjectorTransformation().priority() == GroovyTransformOrder.GLOBAL_GRAILS_TRANSFORM_ORDER
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
    private static List compileToFile(File sourceFile, String source, File targetDirectory, Map<String, String> nodeMetaData = [:]) {
        sourceFile.parentFile.mkdirs()
        sourceFile.text = source
        def configuration = new CompilerConfiguration()
        configuration.setTargetDirectory(targetDirectory)
        if (nodeMetaData) {
            configuration.addCompilationCustomizers(new CompilationCustomizer(CompilePhase.CONVERSION) {
                @Override
                void call(SourceUnit source1, GeneratorContext context, ClassNode cn) throws CompilationFailedException {
                    nodeMetaData.each { key, value -> cn.putNodeMetaData(key, value) }
                }
            })
        }
        CompilationUnit cu = new CompilationUnit(configuration)
        cu.addSource(sourceFile)
        ClassNode capturedClassNode = null
        SourceUnit capturedSourceUnit = null
        cu.addPhaseOperation(new CompilationUnit.PrimaryClassNodeOperation() {
            @Override
            void call(SourceUnit source1, GeneratorContext context, ClassNode cn) throws CompilationFailedException {
                capturedClassNode = cn
                capturedSourceUnit = source1
            }
        }, Phases.CANONICALIZATION)
        cu.compile(Phases.CANONICALIZATION)
        [capturedClassNode, capturedSourceUnit]
    }

    void "the global transform stamps a GrailsPlugin descriptor class with a version property"() {
        given: "a *GrailsPlugin class with no explicit version property, and nowhere for plugin.xml to exist yet"
            File projectDir = File.createTempDir()
            File sourceFile = new File(projectDir, 'PlainGrailsPlugin.groovy')
            File targetDirectory = new File(projectDir, 'build/classes/groovy/main')

        when: "the source is compiled, exercising the registered global transform"
            def (ClassNode classNode, SourceUnit sourceUnit) = compileToFile(sourceFile, '''
class PlainGrailsPlugin {
}
''', targetDirectory, [projectVersion: '1.5'])

        then: "the plugin class is stamped with the resolved version"
            classNode.getProperty('version') != null

        and: "the plugin.xml describing it is generated as a side effect"
            new File(targetDirectory, 'META-INF/grails-plugin.xml').exists()

        cleanup:
            projectDir.deleteDir()
    }

    void "the global transform annotates a plain Grails resource class with @GrailsPlugin metadata"() {
        given: "a class under grails-app that isn't matched by any registered ArtefactHandler"
            File projectDir = File.createTempDir()
            File sourceFile = new File(projectDir, 'grails-app/services/FooWidget.groovy')
            File targetDirectory = new File(projectDir, 'build/classes/groovy/main')

        when: "the source is compiled, exercising the registered global transform"
            def (ClassNode classNode, SourceUnit sourceUnit) = compileToFile(sourceFile, '''
class FooWidget {
}
''', targetDirectory, [projectName: 'foowidget', projectVersion: '2.0'])

        then: "the class is stamped with the project's @GrailsPlugin metadata"
            def annotations = classNode.getAnnotations(ClassHelper.make(GrailsPlugin))
            annotations.size() == 1
            annotations[0].getMember('name').text == GrailsNameUtils.getPropertyNameForLowerCaseHyphenSeparatedName('foowidget')
            annotations[0].getMember('version').text == '2.0'

        cleanup:
            projectDir.deleteDir()
    }

    void "the global transform skips classes whose source falls outside the Grails resource patterns"() {
        given: "a plain source under src/main/groovy, which is project source but not a Grails resource"
            File projectDir = File.createTempDir()
            File sourceFile = new File(projectDir, 'src/main/groovy/PlainClass.groovy')
            File targetDirectory = new File(projectDir, 'build/classes/groovy/main')

        when: "the source is compiled, exercising the registered global transform"
            def (ClassNode classNode, SourceUnit sourceUnit) = compileToFile(sourceFile, '''
class PlainClass {
}
''', targetDirectory, [projectName: 'plain', projectVersion: '1.0'])

        then: "no @GrailsPlugin metadata is stamped on the class"
            classNode.getAnnotations(ClassHelper.make(GrailsPlugin)).isEmpty()

        cleanup:
            projectDir.deleteDir()
    }

    void "Test that plugin dot xml excludes are honoured and metadata refreshed when updating an existing file"() {
        given: "an existing plugin.xml with a resource that the plugin now wants excluded"
            File pluginXml = File.createTempFile('plugin-xml-excludes', 'test.xml')
            ClassNode classNode = null
            CompilationUnit cu = new CompilationUnit(new GroovyClassLoader())
            cu.addSource('BazGrailsPlugin', '''
class BazGrailsPlugin {
    def pluginExcludes = ['Excluded*']
    def grailsVersion = "3.0 > *"
}
''')
            cu.addPhaseOperation(new CompilationUnit.PrimaryClassNodeOperation() {
                @Override
                void call(SourceUnit source, GeneratorContext context, ClassNode cn) throws CompilationFailedException {
                    if (cn.name.endsWith('GrailsPlugin')) {
                        classNode = cn
                    }
                }
            }, Phases.CONVERSION)
            cu.compile(Phases.CONVERSION)
            pluginXml.withWriter { writer ->
                def mkp = new MarkupBuilder(writer)
                mkp.plugin(name: 'baz', version: '1.0', grailsVersion: '1.0 > *') {
                    type 'BazGrailsPlugin'
                    resources {
                        resource 'ExcludedThing'
                    }
                }
            }

        when: "the transformation updates the plugin.xml"
            def transformation = new GlobalGrailsClassInjectorTransformation()
            transformation.generatePluginXml(classNode, '2.0', ['ExcludedThing', 'KeptThing'] as Set, pluginXml)

        then: "the file exists"
            pluginXml.exists()

        when: "the xml is parsed"
            def xml = new XmlSlurper().parse(pluginXml)

        then: "the excluded resource was removed, the kept resource was added, and metadata was refreshed"
            xml.@version.text() == '2.0'
            xml.@grailsVersion.text() == '3.0 > *'
            xml.resources.resource*.text() == ['KeptThing']

        cleanup:
            GlobalGrailsClassInjectorTransformation.pluginExcludes.clear()
            GlobalGrailsClassInjectorTransformation.pendingPluginClasses.clear()
    }
}

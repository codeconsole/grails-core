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

import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets

import groovy.transform.CompilationUnitAware
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.xml.MarkupBuilder
import groovy.xml.StreamingMarkupBuilder
import groovy.xml.slurpersupport.GPathResult
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation
import org.codehaus.groovy.transform.TransformWithPriority

import grails.artefact.Artefact
import grails.compiler.ast.ClassInjector
import grails.core.ArtefactHandler
import grails.io.IOUtils
import grails.plugins.metadata.GrailsPlugin
import grails.util.GrailsNameUtils
import org.apache.grails.common.compiler.GroovyTransformOrder
import org.grails.core.io.support.GrailsFactoriesLoader
import org.grails.io.support.AntPathMatcher
import org.grails.io.support.GrailsResourceUtils
import org.grails.io.support.UrlResource

/**
 * Global AST transformation that applies Grails compiler injection to project sources.
 *
 * <p>It identifies Grails artefacts, applies the relevant {@link ClassInjector} and
 * {@link grails.compiler.traits.TraitInjector} implementations, registers
 * artefact handlers and trait injectors, and generates the
 * {@code META-INF/grails-plugin.xml} descriptor for compiled plugins.</p>
 *
 * @since 3.0
 */
@CompileStatic
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
class GlobalGrailsClassInjectorTransformation implements ASTTransformation, CompilationUnitAware, TransformWithPriority {

    /**
     * The system property signalling that each project compiles into its own isolated output
     * directory. When set, the transform must never fall back to a shared/guessed location, which can
     * leak one module's generated metadata into another.
     */
    public static final String ISOLATED_BUILD_PROPERTY = 'grails.isolated.build'

    public static final ClassNode ARTEFACT_CLASS_NODE = new ClassNode(Artefact)
    public static final ClassNode ARTEFACT_HANDLER_CLASS = ClassHelper.make('grails.core.ArtefactHandler')
    public static final ClassNode TRAIT_INJECTOR_CLASS = ClassHelper.make('grails.compiler.traits.TraitInjector')

    static LinkedHashSet<String> pendingPluginClasses = []
    static Collection<String> pluginExcludes = []

    CompilationUnit compilationUnit

    @Override
    int priority() {
        GroovyTransformOrder.GLOBAL_GRAILS_TRANSFORM_ORDER
    }

    @Override
    void visit(ASTNode[] nodes, SourceUnit source) {
        def url = GrailsASTUtils.getSourceUrl(source)
        if (!shouldVisit(url)) {
            return
        }

        ClassNode pluginClassNode = null
        def pluginVersion = null
        def transformedClasses = new LinkedHashSet<String>()
        def compilationTargetDirectory = resolveCompilationTargetDirectory(source)
        def pluginXmlFile = new File(compilationTargetDirectory, 'META-INF/grails-plugin.xml')
        def artefactHandlers = GrailsFactoriesLoader.loadFactories(ArtefactHandler)
        def injectorCache = new LinkedHashMap<String, List<ClassInjector>>().withDefault { String key ->
            ArtefactTypeAstTransformation.findInjectors(
                    key,
                    GrailsAwareInjectionOperation.classInjectors
            )
        }

        for (def classNode : source.AST.classes.toList()) { // toList() to avoid concurrent modification exception
            def projectName = resolveProjectName(classNode)
            def projectVersion = resolveProjectVersion(classNode)
            pluginVersion = projectVersion
            if (isGrailsPluginDescriptorClass(classNode)) {
                pluginClassNode = classNode
                addPluginVersionProperty(classNode, pluginVersion)
                continue
            }
            if (updateGrailsFactoriesWithType(classNode, ARTEFACT_HANDLER_CLASS, compilationTargetDirectory)) {
                continue
            }
            if (updateGrailsFactoriesWithType(classNode, TRAIT_INJECTOR_CLASS, compilationTargetDirectory)) {
                continue
            }
            if (!GrailsResourceUtils.isGrailsResource(new UrlResource(url))) {
                continue
            }
            if (projectName && projectVersion) {
                addPluginAnnotation(classNode, projectName, projectVersion)
            }

            addImport(classNode, 'org.springframework.beans.factory.annotation.Autowired')

            for (def handler : artefactHandlers) {
                if (handler.isArtefact(classNode)) {
                    if (!classNode.getAnnotations(ARTEFACT_CLASS_NODE)) {
                        transformedClasses.add(classNode.name)
                        addArtefactAnnotation(classNode, handler.type)
                        def injectors = injectorCache[handler.type]
                        for (def injector : injectors) {
                            if (injector instanceof CompilationUnitAware) {
                                ((CompilationUnitAware) injector).compilationUnit = compilationUnit
                            }
                        }
                        ArtefactTypeAstTransformation.performInjection(source, classNode, injectors)
                        TraitInjectionUtils.processTraitsForNode(source, classNode, handler.type, compilationUnit)
                    }
                }
            }

            if (!transformedClasses.contains(classNode.name)) {
                def globalClassInjectors = GrailsAwareInjectionOperation.globalClassInjectors
                for (def injector : globalClassInjectors) {
                    injector.performInjection(source, classNode)
                }
            }
        }

        // now create or update grails-plugin.xml
        generatePluginXml(pluginClassNode, pluginVersion, transformedClasses, pluginXmlFile)
    }

    /**
     * @return {@code true} when the {@code grails.isolated.build} system property is {@code true}.
     */
    static boolean isIsolatedBuild() {
        System.getProperty(ISOLATED_BUILD_PROPERTY, 'false').toBoolean()
    }

    static File resolveCompilationTargetDirectory(SourceUnit source) {
        resolveCompilationTargetDirectory(source, isolatedBuild)
    }

    static File resolveCompilationTargetDirectory(SourceUnit source, boolean isolatedBuild) {
        File targetDirectory
        if (source.class.name == 'org.codehaus.jdt.groovy.control.EclipseSourceUnit') {
            targetDirectory = GroovyEclipseCompilationHelper.resolveEclipseCompilationTargetDirectory(source)
        } else {
            targetDirectory = source.configuration.targetDirectory
        }
        if (targetDirectory == null) {
            // The relative fallback is resolved against the compiler's working directory, which is shared
            // across every module of a multi-project build (e.g. the reused Gradle compiler worker). That
            // makes it a single path for all modules, so one module's generated grails.factories /
            // grails-plugin.xml can leak into another. In an isolated build, fail loudly instead.
            if (isolatedBuild) {
                throw new IllegalStateException(
                        "Unable to resolve the compilation target directory for '${source?.name}' while the " +
                        "'${ISOLATED_BUILD_PROPERTY}' system property is set. Refusing to fall back to the shared " +
                        "relative 'build/classes/main' path, which would leak generated metadata between modules. " +
                        'Ensure the Groovy compiler supplies CompilerConfiguration.targetDirectory.')
            }
            targetDirectory = new File('build/classes/main')
        }
        return targetDirectory
    }

    static boolean updateGrailsFactoriesWithType(ClassNode classNode, ClassNode superType, File compilationTargetDirectory) {
        FactoriesFileWriter.updateFactoriesWithType(
                classNode,
                superType,
                compilationTargetDirectory,
                'META-INF/grails.factories',
                ['src/main/resources/META-INF/grails.factories']
        )
    }

    protected static void generatePluginXml(ClassNode pluginClassNode, Object pluginVersion, Set<String> transformedClasses, File pluginXmlFile) {
        // first check if plugin.xml exists
        pluginXmlFile.parentFile.mkdirs()
        def pluginXmlExists = pluginXmlFile.exists()
        LinkedHashSet<String> pluginClasses = []
        pluginClasses.addAll(transformedClasses)
        pluginClasses.addAll(pendingPluginClasses)

        // if the class being transformed is a *GrailsPlugin class then if it doesn't exist create it
        if (pluginClassNode && !pluginClassNode.isAbstract()) {
            if (!pluginXmlExists) {
                writePluginXml(pluginClassNode, pluginVersion, pluginXmlFile, pluginClasses)
            } else {
                // otherwise if the file does exist, update it with the plugin name
                updatePluginXml(pluginClassNode, pluginVersion, pluginXmlFile, pluginClasses)
            }
        } else if (pluginXmlExists) {
            // if the class isn't the *GrailsPlugin class then only update the plugin.xml if it already exists
            updatePluginXml(null, pluginVersion, pluginXmlFile, pluginClasses)
        } else {
            // otherwise add it to a list of pending classes to populated when the plugin.xml is created
            pendingPluginClasses.addAll(transformedClasses)
        }
    }

    @CompileDynamic
    static void writePluginXml(ClassNode pluginClassNode, Object pluginVersion, File pluginXml, Collection<String> artefactClasses) {
        if (pluginClassNode) {
            PluginAstReader pluginAstReader = new PluginAstReader()
            def info = pluginAstReader.readPluginInfo(pluginClassNode)

            pluginXml.withWriter(StandardCharsets.UTF_8.name()) { Writer writer ->
                def mkp = new MarkupBuilder(writer)
                def pluginName = GrailsNameUtils.getLogicalPropertyName(pluginClassNode.name, 'GrailsPlugin')

                def pluginProperties = info.getProperties()
                def excludes = pluginProperties.get('pluginExcludes')
                if (excludes instanceof List) {
                    pluginExcludes.clear()
                    pluginExcludes.addAll(excludes)
                }

                def grailsVersion = pluginProperties['grailsVersion'] ?: getClass().package.implementationVersion + ' > *'
                mkp.plugin(name: pluginName, version: pluginVersion, grailsVersion: grailsVersion.toString()) {
                    type(pluginClassNode.name)

                    for (entry in pluginProperties) {
                        delegate."$entry.key"(entry.value)
                    }

                    // if there are pending classes to add to the plugin.xml add those
                    if (artefactClasses) {
                        def antPathMatcher = new AntPathMatcher()
                        resources {
                            for (String cn in artefactClasses) {
                                if (!pluginExcludes.any() { String exc -> antPathMatcher.match(exc, cn.replace('.', '/')) }) {
                                    resource(cn)
                                }
                            }
                        }
                    }
                }
            }

            pendingPluginClasses.clear()
        }
    }

    static void updatePluginXml(ClassNode pluginClassNode, Object pluginVersion, File pluginXmlFile, Collection<String> artefactClasses) {
        if (!artefactClasses) return
        try {
            def pluginXml = IOUtils.createXmlSlurper().parse(pluginXmlFile)
            if (pluginClassNode) {
                def pluginProperties = writePluginXmlProperties(pluginClassNode, pluginVersion.toString(), pluginXml)
                def excludes = pluginProperties.get('pluginExcludes')
                if (excludes instanceof List) {
                    pluginExcludes.clear()
                    pluginExcludes.addAll(excludes as List<String>)
                }
            }
            writePluginXmlResources(pluginXml, artefactClasses)
            handleExcludes(pluginXml)

            pluginXmlFile.withWriter(StandardCharsets.UTF_8.name()) {
                createMarkup(pluginXml).writeTo(it)
            }

            pendingPluginClasses.clear()

        } catch (ignored) {
            // corrupt, recreate
            writePluginXml(pluginClassNode, pluginVersion, pluginXmlFile, artefactClasses)
        }
    }

    @CompileDynamic
    protected static void handleExcludes(GPathResult pluginXml) {
        if (pluginExcludes) {
            def antPathMatcher = new AntPathMatcher()
            pluginXml.resources.resource.each {
                def resourceNode = it as GPathResult
                if (pluginExcludes.any() { antPathMatcher.match(it, resourceNode.text().replace('.', '/')) }) {
                    resourceNode.replaceNode {}
                }
            }
        }
    }

    @CompileDynamic
    private static Writable createMarkup(GPathResult node) {
        new StreamingMarkupBuilder().mkp.yield(node)
    }

    private static Object resolveProjectVersion(ClassNode classNode) {
        def projectVersion = classNode.getNodeMetaData('projectVersion')
        if (projectVersion == null) {
            projectVersion = getClass().package.implementationVersion
        }
        projectVersion
    }

    private static Object resolveProjectName(ClassNode classNode) {
        classNode.getNodeMetaData('projectName')
    }

    private static boolean shouldVisit(URL url) {
        url != null && GrailsResourceUtils.isProjectSource(new UrlResource(url))
    }

    private static boolean isGrailsPluginDescriptorClass(ClassNode classNode) {
        classNode.name.endsWith('GrailsPlugin') && !classNode.abstract
    }

    private static void addPluginVersionProperty(ClassNode classNode, Object pluginVersion) {
        if (!classNode.getProperty('version')) {
            classNode.addProperty(
                    new PropertyNode(
                            'version',
                            Modifier.PUBLIC,
                            ClassHelper.make(Object),
                            classNode,
                            new ConstantExpression(pluginVersion.toString()),
                            null,
                            null
                    )
            )
        }
    }

    private static void addPluginAnnotation(ClassNode classNode, Object projectName, Object projectVersion) {
        GrailsASTUtils.addAnnotationOrGetExisting(
                classNode,
                GrailsPlugin,
                [
                        name: GrailsNameUtils.getPropertyNameForLowerCaseHyphenSeparatedName(projectName.toString()),
                        version: projectVersion.toString()
                ] as Map<String, Object>
        )
    }

    private static void addImport(ClassNode classNode, String className) {
        classNode.module.addImport(
                className.tokenize('.')[-1],
                ClassHelper.make(className)
        )
    }

    private static void addArtefactAnnotation(ClassNode classNode, String handlerType) {
        def annotationNode = new AnnotationNode(new ClassNode(Artefact))
        annotationNode.addMember('value', new ConstantExpression(handlerType))
        classNode.addAnnotation(annotationNode)
    }

    @CompileDynamic
    private static Map writePluginXmlProperties(ClassNode pluginClassNode, Object pluginVersion, GPathResult pluginXml) {
        def pluginProperties = new PluginAstReader().readPluginInfo(pluginClassNode).getProperties()
        def grailsVersion = pluginProperties['grailsVersion'] ?: getClass().package.implementationVersion + ' > *'
        pluginXml.@name = GrailsNameUtils.getLogicalPropertyName(pluginClassNode.name, 'GrailsPlugin')
        pluginXml.@version = pluginVersion.toString()
        pluginXml.type = pluginClassNode.name
        pluginXml.@grailsVersion = grailsVersion.toString()
        for (def entry : pluginProperties) {
            pluginXml."$entry.key" = entry.value
        }
        pluginProperties
    }

    @CompileDynamic
    private static void writePluginXmlResources(GPathResult pluginXml, Collection<String> artefactClasses) {
        def resources = pluginXml.resources
        for (def className : artefactClasses) {
            if (!resources.resource.find { it.text() == className }) {
                resources.appendNode {
                    resource(className)
                }
            }
        }
    }
}

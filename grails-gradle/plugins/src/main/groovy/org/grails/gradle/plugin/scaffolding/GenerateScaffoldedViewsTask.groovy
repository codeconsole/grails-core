/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.grails.gradle.plugin.scaffolding

import java.util.jar.JarEntry
import java.util.jar.JarFile

import javax.inject.Inject

import groovy.transform.CompileStatic
import groovyjarjarasm.asm.AnnotationVisitor
import groovyjarjarasm.asm.ClassReader
import groovyjarjarasm.asm.ClassVisitor
import groovyjarjarasm.asm.Opcodes
import groovyjarjarasm.asm.Type

import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileVisitDetails
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations
import org.gradle.process.JavaExecSpec

/**
 * Expands the scaffolding templates at build time, so the pages a scaffolded controller renders are
 * compiled with the rest of the application instead of on the request that first asks for them.
 *
 * <p>Scaffolding expands a template into GSP source and compiles the result. At runtime that costs
 * the first request on the JVM, and a native image cannot do it at all: defining a class at runtime
 * is exactly what an ahead-of-time image gives up. The pages written here are compiled by the
 * ordinary GSP compiler instead.</p>
 *
 * <p>They are written under {@code grails-scaffolded/<domain class>/}, a directory no controller's
 * views resolve from, and each is named for its template and a digest of the template and the model
 * it was expanded with. The runtime resolver decides which page a request gets exactly as it would
 * without them - a view the application or a plugin declares, a namespace-specific template, a
 * template override - and only where it would expand a template does it look for the page expanded
 * from the same template and model. So a page cannot shadow a declared view, and a template this
 * task did not see finds nothing and is expanded at runtime as before.</p>
 *
 * <p>This task finds the scaffolded domain classes, by reading the controllers with ASM so that no
 * application class is loaded, and collects the templates: every copy of every template on the application's runtime classpath,
 * which the running application reads them from, and in its {@code src/main/templates/scaffolding}.
 * All copies are expanded rather than the one the resolver is expected to choose, so whichever copy
 * it does choose - an override, a plugin's, the stock one - has its page, and nothing here predicts
 * the resolver.
 * Namespace-specific templates such as {@code admin/show.gsp} are included, because which one a
 * controller uses depends on its namespace, which is only known when it is asked for. The pages
 * themselves are expanded and named by {@code org.apache.grails.scaffolding.ScaffoldedPagesGenerator},
 * run in a JVM on the application's runtime classpath, so they come from the same code and the same
 * Groovy as the resolver's.</p>
 *
 * @since 8.0
 */
@CacheableTask
@CompileStatic
abstract class GenerateScaffoldedViewsTask extends DefaultTask {

    /** Descriptor of the annotation that marks a scaffolded controller. */
    private static final String SCAFFOLD_ANNOTATION = 'Lgrails/plugin/scaffolding/annotation/Scaffold;'

    /** Path within an artifact holding the scaffolding templates. */
    private static final String TEMPLATE_PATH = 'META-INF/templates/scaffolding/'

    /** The class that expands and names the pages, from the application's scaffolding library. */
    static final String GENERATOR = 'org.apache.grails.scaffolding.ScaffoldedPagesGenerator'

    /** Compiled application classes, searched for scaffolded controllers. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getClassesDirs()

    /**
     * Application template overrides, normally the tree of {@code src/main/templates/scaffolding}.
     * A template's path within the tree is its path as the resolver asks for it, so
     * {@code admin/show.gsp} overrides the {@code show} template of the {@code admin} namespace.
     */
    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getTemplateOverrides()

    /**
     * The application's runtime classpath. The templates are read from it, as the running
     * application reads them, and the pages are expanded on it, by the scaffolding library and the
     * Groovy the application runs with.
     */
    @Classpath
    abstract ConfigurableFileCollection getRuntimeClasspath()

    /** The Java the pages are expanded with; the build's own when not set. */
    @Nested
    @Optional
    abstract Property<JavaLauncher> getJavaLauncher()

    /** Where the pages are written, as a tree to be compiled with the application's views. */
    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @Inject
    abstract ExecOperations getExecOperations()

    @TaskAction
    void generate() {
        File outputDir = outputDirectory.get().asFile
        outputDir.deleteDir()
        outputDir.mkdirs()

        Map<String, List<byte[]>> templates = loadTemplates()
        if (templates.isEmpty()) {
            logger.info('No scaffolding templates on the classpath; nothing to generate')
            return
        }
        Set<String> domains = findScaffoldedDomains()
        if (domains.isEmpty()) {
            logger.info('No scaffolded controllers; nothing to generate')
            return
        }
        if (!generatorAvailable()) {
            logger.warn('The scaffolding library on the runtime classpath does not provide {}, so no scaffolded page is ' +
                    'compiled and each is expanded when it is first rendered, which a native image cannot do. ' +
                    'Use a grails-scaffolding matching this Gradle plugin.', GENERATOR)
            return
        }

        // one directory per copy, so that each holds a template path at most once
        File work = temporaryDir
        File templatesRoot = new File(work, 'templates')
        templatesRoot.deleteDir()
        int copies = (int) templates.values()*.size().max()
        List<File> templateDirs = (0..<copies).collect { int copy -> new File(templatesRoot, String.valueOf(copy)) }
        templates.each { String path, List<byte[]> contents ->
            contents.eachWithIndex { byte[] content, int copy ->
                File file = new File(templateDirs[copy], "${path}.gsp")
                file.parentFile.mkdirs()
                file.bytes = content
            }
        }
        File domainList = new File(work, 'domains.txt')
        domainList.setText(domains.join('\n'), 'UTF-8')

        execOperations.javaexec(new Action<JavaExecSpec>() {
            @Override
            void execute(JavaExecSpec spec) {
                if (javaLauncher.present) {
                    spec.executable = javaLauncher.get().executablePath.asFile.absolutePath
                }
                spec.classpath = runtimeClasspath
                spec.mainClass.set(GENERATOR)
                spec.args([domainList.absolutePath, outputDir.absolutePath] + templateDirs*.absolutePath)
            }
        }).assertNormalExitValue()
    }

    /** Whether the generator is on its classpath; a scaffolding library older than this plugin lacks it. */
    private boolean generatorAvailable() {
        String entry = GENERATOR.replace('.', '/') + '.class'
        runtimeClasspath.files.any { File file ->
            if (file.isDirectory()) {
                return new File(file, entry).isFile()
            }
            if (file.isFile() && file.name.endsWith('.jar')) {
                return new JarFile(file).withCloseable { JarFile jar -> jar.getJarEntry(entry) != null }
            }
            false
        }
    }

    /**
     * Maps a template's path, without its extension, to every distinct copy of it: the
     * application's own, then each dependency's in classpath order. A copy identical to one already
     * found is left out, as it would expand to the same page.
     */
    private Map<String, List<byte[]>> loadTemplates() {
        Map<String, List<byte[]>> templates = new TreeMap<>()
        templateOverrides.asFileTree.visit { FileVisitDetails details ->
            if (!details.directory && details.name.endsWith('.gsp')) {
                addCopy(templates, baseName(details.relativePath.pathString), details.file.bytes)
            }
        }
        for (File entry : runtimeClasspath.files) {
            if (entry.isDirectory()) {
                File dir = new File(entry, TEMPLATE_PATH)
                if (dir.isDirectory()) {
                    dir.eachFileRecurse { File f ->
                        if (f.isFile() && f.name.endsWith('.gsp')) {
                            String path = dir.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/' as char)
                            addCopy(templates, baseName(path), f.bytes)
                        }
                    }
                }
            }
            else if (entry.name.endsWith('.jar') && entry.isFile()) {
                new JarFile(entry).withCloseable { JarFile jar ->
                    for (JarEntry e : jar.entries()) {
                        if (!e.directory && e.name.startsWith(TEMPLATE_PATH) && e.name.endsWith('.gsp')) {
                            addCopy(templates, baseName(e.name.substring(TEMPLATE_PATH.length())),
                                    jar.getInputStream(e).withCloseable { InputStream input -> input.bytes })
                        }
                    }
                }
            }
        }
        templates
    }

    private static void addCopy(Map<String, List<byte[]>> templates, String path, byte[] content) {
        List<byte[]> copies = templates.computeIfAbsent(path) { [] }
        if (!copies.any { byte[] copy -> Arrays.equals(copy, content) }) {
            copies.add(content)
        }
    }

    /**
     * The fully qualified name of every domain class a controller scaffolds. Qualified rather than
     * simple because a page declaring the type of its model has to name a type that resolves.
     */
    private Set<String> findScaffoldedDomains() {
        Set<String> domains = new TreeSet<>()
        for (File dir : classesDirs.files) {
            if (!dir.isDirectory()) {
                continue
            }
            dir.eachFileRecurse { File f ->
                if (!f.name.endsWith('Controller.class')) {
                    return
                }
                String domain = readScaffoldDomain(f)
                if (domain != null) {
                    domains.add(domain)
                }
            }
        }
        domains
    }

    /**
     * Returns the fully qualified name of the domain class a controller scaffolds, or {@code null}
     * when it is not scaffolded. Read with ASM so the application's classes are never loaded, which
     * keeps the task independent of the runtime classpath.
     *
     * <p>{@code domain} is what names the domain class, and it is read in preference to
     * {@code value}, which names it only when it is the sole attribute given. Every form is
     * normalised by the time this reads it - ScaffoldingControllerInjector writes the domain into
     * {@code domain} whether it was written as {@code @Scaffold(User)},
     * {@code @Scaffold(domain = User)} or {@code @Scaffold(RestfulServiceController<User>)} - so
     * for the last of those {@code value} is the class to extend, and taking it would name the
     * controller superclass as the domain. The precedence matters rather than merely tidying,
     * because the two attributes are written in no guaranteed order.</p>
     */
    private String readScaffoldDomain(File classFile) {
        boolean scaffolded = false
        String fromValue = null
        String fromDomain = null
        classFile.withInputStream { InputStream input ->
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (descriptor != SCAFFOLD_ANNOTATION) {
                        return null
                    }
                    scaffolded = true
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        void visit(String name, Object value) {
                            if (!(value instanceof Type)) {
                                return
                            }
                            String candidate = ((Type) value).className
                            if (candidate.tokenize('.').last() == 'Void') {
                                return
                            }
                            if (name == 'domain') {
                                fromDomain = candidate
                            }
                            else if (name == 'value') {
                                fromValue = candidate
                            }
                        }
                    }
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        }
        scaffolded ? (fromDomain ?: fromValue) : null
    }

    private static String baseName(String fileName) {
        fileName.endsWith('.gsp') ? fileName[0..<fileName.length() - 4] : fileName
    }
}

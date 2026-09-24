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

import java.nio.charset.StandardCharsets
import java.util.jar.JarEntry
import java.util.jar.JarFile

import groovy.text.GStringTemplateEngine
import groovy.transform.CompileStatic
import groovyjarjarasm.asm.AnnotationVisitor
import groovyjarjarasm.asm.ClassReader
import groovyjarjarasm.asm.ClassVisitor
import groovyjarjarasm.asm.Opcodes
import groovyjarjarasm.asm.Type

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileVisitDetails
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

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
 * views resolve from, and each is named for a digest of the template it was expanded from and the
 * model it was expanded with. The runtime resolver decides which page a request gets exactly as it
 * would without them - a view the application or a plugin declares, a namespace-specific template,
 * a template override - and only where it would expand a template does it look for the page expanded
 * here from the same template and model. So a page cannot shadow a declared view, and a template
 * this task did not see, or a model it derived differently, finds nothing and is expanded at runtime
 * as before rather than being served a different page.</p>
 *
 * <p>Which template a controller uses depends on its namespace, which is only known when it is asked
 * for, so every template is expanded for every scaffolded domain class, namespace-specific ones such
 * as {@code admin/show.gsp} included. Where a template path appears more than once, the application's
 * {@code src/main/templates/scaffolding} wins and then the first on the classpath, which is what the
 * resolver finds for an application's controller.</p>
 *
 * <p>No GORM, application context or application class is needed: the controllers are read with ASM
 * and the model is derived from the domain class name alone, as the runtime model is. The template
 * engine of the build's Groovy is relied on to expand a template the way the application's does.</p>
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

    /** Compiled application classes, searched for scaffolded controllers. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getClassesDirs()

    /** The classpath the scaffolding templates are read from. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getTemplateClasspath()

    /**
     * Application template overrides, normally the tree of {@code src/main/templates/scaffolding}.
     * A template's path within the tree is its path as the resolver asks for it, so
     * {@code admin/show.gsp} overrides the {@code show} template of the {@code admin} namespace.
     */
    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getTemplateOverrides()

    /** Where the pages are written, as a tree to be compiled with the application's views. */
    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @TaskAction
    void generate() {
        File outputDir = outputDirectory.get().asFile
        outputDir.deleteDir()
        outputDir.mkdirs()

        Map<String, byte[]> templates = loadTemplates()
        if (templates.isEmpty()) {
            logger.info('No scaffolding templates on the classpath; nothing to generate')
            return
        }

        int written = 0
        for (String domain : findScaffoldedDomains()) {
            Map<String, Object> model = ScaffoldedPages.model(domain)
            for (Map.Entry<String, byte[]> template : templates.entrySet()) {
                String page
                try {
                    page = expand(template.value, model)
                }
                catch (Exception e) {
                    logger.warn('Could not expand the scaffolding template {} for {}, so it is expanded when it is ' +
                            'first rendered instead, which a native image cannot do: {}', template.key, domain, e.toString())
                    continue
                }
                File target = new File(outputDir, ScaffoldedPages.path(model, template.value))
                target.parentFile.mkdirs()
                target.setText(page, StandardCharsets.UTF_8.name())
                written++
            }
        }
        logger.info('Generated {} scaffolded page(s)', written)
    }

    /** Expands a template with the names the runtime resolver binds. */
    private static String expand(byte[] template, Map<String, Object> model) {
        StringWriter out = new StringWriter()
        new GStringTemplateEngine()
                .createTemplate(new String(template, StandardCharsets.UTF_8))
                .make(model)
                .writeTo(out)
        out.toString()
    }

    /**
     * Maps a template's path, without its extension, to its content, with the application's
     * overrides winning over the templates a dependency contributes and an earlier dependency over
     * a later one.
     */
    private Map<String, byte[]> loadTemplates() {
        Map<String, byte[]> templates = new TreeMap<>()
        for (File entry : templateClasspath.files) {
            if (entry.isDirectory()) {
                File dir = new File(entry, TEMPLATE_PATH)
                if (dir.isDirectory()) {
                    dir.eachFileRecurse { File f ->
                        if (f.isFile() && f.name.endsWith('.gsp')) {
                            String path = dir.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/' as char)
                            templates.putIfAbsent(baseName(path), f.bytes)
                        }
                    }
                }
            }
            else if (entry.name.endsWith('.jar') && entry.isFile()) {
                new JarFile(entry).withCloseable { JarFile jar ->
                    for (JarEntry e : jar.entries()) {
                        if (!e.directory && e.name.startsWith(TEMPLATE_PATH) && e.name.endsWith('.gsp')) {
                            templates.putIfAbsent(baseName(e.name.substring(TEMPLATE_PATH.length())),
                                    jar.getInputStream(e).withCloseable { InputStream input -> input.bytes })
                        }
                    }
                }
            }
        }
        templateOverrides.asFileTree.visit { FileVisitDetails details ->
            if (!details.directory && details.name.endsWith('.gsp')) {
                templates.put(baseName(details.relativePath.pathString), details.file.bytes)
            }
        }
        templates
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

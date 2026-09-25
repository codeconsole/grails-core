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
import groovyjarjarasm.asm.FieldVisitor
import groovyjarjarasm.asm.MethodVisitor
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
import org.gradle.api.tasks.Input
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
 * task did not expand finds nothing and is expanded at runtime as before.</p>
 *
 * <p>For each scaffolded controller - the application's own, and those plugins on the runtime
 * classpath provide - this expands for its domain class every copy of each template the resolver
 * could choose for it:</p>
 * <ul>
 *   <li>Which copy the resolver finds is not something the build can know. On the JVM it looks
 *   beside the controller's class first, then at a plugin that overrides the templates, then along
 *   the classpath; a native image keeps no class files to look beside, and goes by the order of the
 *   classpath it was built from. So every distinct copy is expanded - the application's own, from
 *   {@code src/main/templates/scaffolding} or its resources, and each on the runtime classpath -
 *   and whichever the resolver chooses has its page.</li>
 *   <li>A namespace-specific template such as {@code admin/show.gsp} can only be chosen for a
 *   controller with a namespace, so it is expanded only for the domain classes such controllers
 *   scaffold.</li>
 * </ul>
 *
 * <p>No application class is loaded: the controllers are read with ASM, and the pages are expanded
 * and named by {@code org.apache.grails.scaffolding.ScaffoldedPagesGenerator}, run in a JVM on the
 * application's runtime classpath, from the same code and the same Groovy as the resolver's.</p>
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

    /** What marks an artifact as a Grails plugin, whose controllers are artefacts of the application. */
    private static final String PLUGIN_DESCRIPTOR = 'META-INF/grails-plugin.xml'

    /**
     * The directory, under the views, the pages are written to and the resolver looks in, as
     * {@code org.apache.grails.scaffolding.ScaffoldedPages} names it.
     */
    static final String PAGES_DIRECTORY = 'grails-scaffolded'

    /** The class that expands and names the pages, from the application's scaffolding library. */
    static final String GENERATOR = 'org.apache.grails.scaffolding.ScaffoldedPagesGenerator'

    /** Compiled application classes, searched for scaffolded controllers. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getClassesDirs()

    /**
     * The application's own templates, as trees rooted at the template directory: normally
     * {@code src/main/templates/scaffolding} and {@code META-INF/templates/scaffolding} under the
     * resource directories. A template's path within its tree is its path as the resolver asks for
     * it, so {@code admin/show.gsp} is the {@code show} template of the {@code admin} namespace.
     */
    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getTemplateOverrides()

    /**
     * The application's runtime classpath. The templates and the plugins' controllers are read from
     * it, as the running application reads them, and the pages are expanded on it, by the
     * scaffolding library and the Groovy the application runs with.
     */
    @Classpath
    abstract ConfigurableFileCollection getRuntimeClasspath()

    /**
     * The encoding the pages are written in, which must be the one they are compiled with, so that
     * a page reads back as it was expanded. Normally {@code compileGroovyPages}' encoding.
     */
    @Input
    abstract Property<String> getPageEncoding()

    /**
     * The separator the pages were expanded under. A template that mentions {@code packagePath}
     * expands differently on Windows, so pages built on one platform are not taken from the build
     * cache for another.
     */
    @Input
    String getFileSeparator() {
        File.separator
    }

    /** The Java the pages are expanded with; the build's own when not set. */
    @Nested
    @Optional
    abstract Property<JavaLauncher> getJavaLauncher()

    /** Where the pages are written, as a tree to be compiled with the application's views. */
    @OutputDirectory
    abstract DirectoryProperty getOutputDirectory()

    @Inject
    abstract ExecOperations getExecOperations()

    GenerateScaffoldedViewsTask() {
        pageEncoding.convention('UTF-8')
    }

    @TaskAction
    void generate() {
        File outputDir = outputDirectory.get().asFile
        outputDir.deleteDir()
        outputDir.mkdirs()

        Templates templates = findTemplates()
        if (templates.copies.isEmpty()) {
            logger.info('No scaffolding templates on the classpath; nothing to generate')
            return
        }
        List<Controller> controllers = findScaffoldedControllers()
        if (controllers.isEmpty()) {
            logger.info('No scaffolded controllers; nothing to generate')
            return
        }
        if (!templates.generator) {
            logger.warn('The scaffolding library on the runtime classpath does not provide {}, so no scaffolded page is ' +
                    'compiled and each is expanded when it is first rendered, which a native image cannot do. ' +
                    'Use a grails-scaffolding matching this Gradle plugin.', GENERATOR)
            return
        }

        // every distinct copy once, in a directory of its own, so a domain class is handed the copies
        // its controllers can choose
        File work = temporaryDir
        File templatesRoot = new File(work, 'templates')
        templatesRoot.deleteDir()
        templates.copies.eachWithIndex { TemplateCopy copy, int index ->
            copy.directory = new File(templatesRoot, String.valueOf(index))
            File file = new File(copy.directory, "${copy.path}.gsp")
            file.parentFile.mkdirs()
            file.bytes = copy.content
        }
        Map<String, Set<TemplateCopy>> plan = new TreeMap<>()
        for (Controller controller : controllers) {
            plan.computeIfAbsent(controller.domain) { new LinkedHashSet<TemplateCopy>() }.addAll(templates.choosableBy(controller))
        }
        File planFile = new File(work, 'plan.txt')
        planFile.setText(plan.collect { String domain, Set<TemplateCopy> copies ->
            ([domain] + copies*.directory*.absolutePath).join('\t')
        }.join('\n'), 'UTF-8')

        execOperations.javaexec(new Action<JavaExecSpec>() {
            @Override
            void execute(JavaExecSpec spec) {
                if (javaLauncher.present) {
                    spec.executable = javaLauncher.get().executablePath.asFile.absolutePath
                }
                spec.classpath = runtimeClasspath
                spec.mainClass.set(GENERATOR)
                spec.args(planFile.absolutePath, outputDir.absolutePath, pageEncoding.get())
            }
        }).assertNormalExitValue()
    }

    /**
     * Reads the templates: the application's own, then every distinct copy the runtime classpath
     * carries; and whether the classpath carries the generator, which a scaffolding library older
     * than this plugin does not.
     */
    private Templates findTemplates() {
        Templates templates = new Templates()
        templateOverrides.asFileTree.visit { FileVisitDetails details ->
            if (!details.directory && details.name.endsWith('.gsp')) {
                templates.add(baseName(details.relativePath.pathString), details.file.bytes)
            }
        }
        String generator = GENERATOR.replace('.', '/') + '.class'
        for (File entry : runtimeClasspath.files) {
            if (entry.isDirectory()) {
                templates.generator = templates.generator || new File(entry, generator).isFile()
                File dir = new File(entry, TEMPLATE_PATH)
                if (dir.isDirectory()) {
                    dir.eachFileRecurse { File f ->
                        if (f.isFile() && f.name.endsWith('.gsp')) {
                            String path = dir.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/' as char)
                            templates.add(baseName(path), f.bytes)
                        }
                    }
                }
            }
            else if (entry.name.endsWith('.jar') && entry.isFile()) {
                new JarFile(entry).withCloseable { JarFile jar ->
                    templates.generator = templates.generator || jar.getJarEntry(generator) != null
                    for (JarEntry e : jar.entries()) {
                        if (!e.directory && e.name.startsWith(TEMPLATE_PATH) && e.name.endsWith('.gsp')) {
                            templates.add(baseName(e.name.substring(TEMPLATE_PATH.length())),
                                    jar.getInputStream(e).withCloseable { InputStream input -> input.bytes })
                        }
                    }
                }
            }
        }
        templates
    }

    /**
     * Every scaffolded controller: the application's own, and each a plugin on the runtime classpath
     * provides, with the domain class it scaffolds and whether it has a namespace.
     */
    private List<Controller> findScaffoldedControllers() {
        List<Controller> controllers = []
        Map<String, Boolean> ancestors = [:]
        URL[] classpath = (classesDirs.files + runtimeClasspath.files).collect { it.toURI().toURL() } as URL[]
        new URLClassLoader(classpath, (ClassLoader) null).withCloseable { URLClassLoader resources ->
            for (File dir : classesDirs.files) {
                if (dir.isDirectory()) {
                    dir.eachFileRecurse { File f ->
                        if (f.name.endsWith('Controller.class')) {
                            Controller controller = readController(f.bytes, null, resources, ancestors)
                            if (controller != null) {
                                controllers.add(controller)
                            }
                        }
                    }
                }
            }
            for (File entry : runtimeClasspath.files) {
                if (entry.name.endsWith('.jar') && entry.isFile()) {
                    new JarFile(entry).withCloseable { JarFile jar ->
                        if (jar.getJarEntry(PLUGIN_DESCRIPTOR) == null) {
                            return
                        }
                        for (JarEntry e : jar.entries()) {
                            if (!e.directory && e.name.endsWith('Controller.class')) {
                                byte[] bytes = jar.getInputStream(e).withCloseable { InputStream input -> input.bytes }
                                Controller controller = readController(bytes, entry, resources, ancestors)
                                if (controller != null) {
                                    controllers.add(controller)
                                }
                            }
                        }
                    }
                }
                else if (entry.isDirectory() && new File(entry, PLUGIN_DESCRIPTOR).isFile()) {
                    entry.eachFileRecurse { File f ->
                        if (f.name.endsWith('Controller.class')) {
                            Controller controller = readController(f.bytes, entry, resources, ancestors)
                            if (controller != null) {
                                controllers.add(controller)
                            }
                        }
                    }
                }
            }
        }
        controllers
    }

    private Controller readController(byte[] bytes, File source, ClassLoader resources, Map<String, Boolean> ancestors) {
        ClassReader reader
        try {
            reader = new ClassReader(bytes)
        }
        catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            logger.info('Could not read a controller from {}: {}', source ?: 'the application', e.message)
            return null
        }
        String domain = readScaffoldDomain(reader)
        domain == null ? null : new Controller(domain, hasNamespace(reader, resources, ancestors))
    }

    /**
     * Whether a controller declares a namespace, itself or through a superclass, read from its
     * declarations without running any of its code.
     *
     * <p>Groovy traits rename their namespace fields but emit a static {@code getNamespace()}
     * accessor on the implementing class. Checking that accessor covers trait-supplied namespaces
     * without walking interfaces; only superclass declarations require an ancestor walk.</p>
     *
     * <p>A declaration is all this can see, not its value, so {@code static namespace = null}
     * still counts even though the runtime, which tests the value, gives that controller no
     * namespace. The value lives in {@code <clinit>} for the usual Groovy forms and code is not
     * read here; such a controller only has namespace-specific templates expanded for it that it
     * will not use.</p>
     */
    private boolean hasNamespace(ClassReader reader, ClassLoader resources, Map<String, Boolean> ancestors) {
        boolean declared = false
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (name == 'namespace' && (access & Opcodes.ACC_STATIC) != 0) {
                    declared = true
                }
                null
            }

            @Override
            MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (name == 'getNamespace' && descriptor.startsWith('()') && (access & Opcodes.ACC_STATIC) != 0) {
                    declared = true
                }
                null
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        if (declared || reader.superName == null || reader.superName == 'java/lang/Object') {
            return declared
        }
        String superName = reader.superName
        Boolean known = ancestors.get(superName)
        if (known != null) {
            return known
        }
        boolean inherited = ancestorHasNamespace(superName, resources, ancestors)
        ancestors.put(superName, inherited)
        inherited
    }

    /**
     * Superclasses can come from dependencies, whose class files may be newer than the bundled ASM
     * reads, or whose bytecode may be damaged or unreadable. One that cannot be read is taken to
     * declare no namespace rather than failing the build.
     */
    private boolean ancestorHasNamespace(String internalName, ClassLoader resources, Map<String, Boolean> ancestors) {
        try {
            InputStream parent = resources.getResourceAsStream("${internalName}.class")
            if (parent == null) {
                return false
            }
            ClassReader reader = parent.withCloseable { InputStream input -> new ClassReader(input) }
            return hasNamespace(reader, resources, ancestors)
        }
        catch (IllegalArgumentException | IOException | IndexOutOfBoundsException e) {
            logger.info('Could not read {} to look for an inherited namespace; treating it as declaring none: {}',
                    internalName.replace('/', '.'), e.message)
            return false
        }
    }

    /**
     * Returns the fully qualified name of the domain class a controller scaffolds, or {@code null}
     * when it is not scaffolded. Qualified rather than simple because a page declaring the type of
     * its model has to name a type that resolves.
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
    private String readScaffoldDomain(ClassReader reader) {
        boolean scaffolded = false
        String fromValue = null
        String fromDomain = null
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
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
        scaffolded ? (fromDomain ?: fromValue) : null
    }

    private static String baseName(String fileName) {
        fileName.endsWith('.gsp') ? fileName[0..<fileName.length() - 4] : fileName
    }

    /** A scaffolded controller: the domain class it scaffolds, and whether it has a namespace. */
    private static final class Controller {

        final String domain

        final boolean namespaced

        Controller(String domain, boolean namespaced) {
            this.domain = domain
            this.namespaced = namespaced
        }

    }

    /** One copy of a template. */
    private static final class TemplateCopy {

        final String path

        final byte[] content

        File directory

        TemplateCopy(String path, byte[] content) {
            this.path = path
            this.content = content
        }

    }

    /** Every distinct copy of every template, and whether the generator was found. */
    private static final class Templates {

        final List<TemplateCopy> copies = []

        boolean generator

        void add(String path, byte[] content) {
            if (!copies.any { TemplateCopy c -> c.path == path && Arrays.equals(c.content, content) }) {
                copies.add(new TemplateCopy(path, content))
            }
        }

        /**
         * The copies the resolver could choose for a controller: every copy of every template, but a
         * namespace-specific one only for a controller with a namespace.
         */
        List<TemplateCopy> choosableBy(Controller controller) {
            controller.namespaced ? copies : copies.findAll { TemplateCopy c -> !c.path.contains('/') }
        }

    }
}

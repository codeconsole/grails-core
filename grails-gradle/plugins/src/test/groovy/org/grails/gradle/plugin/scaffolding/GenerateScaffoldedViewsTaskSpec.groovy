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
import java.util.jar.JarOutputStream

import groovyjarjarasm.asm.AnnotationVisitor
import groovyjarjarasm.asm.ClassWriter
import groovyjarjarasm.asm.Opcodes
import groovyjarjarasm.asm.Type
import spock.lang.Specification
import spock.lang.TempDir

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder

class GenerateScaffoldedViewsTaskSpec extends Specification {

    @TempDir
    File projectDir

    private File classesDir
    private File templateJar

    void setup() {
        classesDir = new File(projectDir, 'classes')
        classesDir.mkdirs()
        templateJar = new File(projectDir, 'templates.jar')
        writeTemplateJar(templateJar, [
                index : 'list of ${propertyName} for ${className}',
                create: 'create ${className}',
                edit  : 'edit ${className}',
                show  : 'show ${className}'])
    }

    /** A jar shaped like the one the scaffolding plugin publishes. */
    private void writeTemplateJar(File jar, Map<String, String> templates) {
        new JarOutputStream(jar.newOutputStream()).withCloseable { JarOutputStream out ->
            templates.each { String name, String body ->
                out.putNextEntry(new JarEntry("META-INF/templates/scaffolding/${name}.gsp"))
                out.write(body.bytes)
                out.closeEntry()
            }
        }
    }

    /**
     * Writes a class carrying {@code @Scaffold}, so the task reads a real annotation rather than a
     * stand-in for one.
     */
    private void writeController(String controllerName, String domainClassName) {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/${controllerName}", null,
                'java/lang/Object', null)
        AnnotationVisitor annotation = writer.visitAnnotation(
                'Lgrails/plugin/scaffolding/annotation/Scaffold;', true)
        annotation.visit('value', Type.getObjectType("com/example/${domainClassName}"))
        annotation.visitEnd()
        writer.visitEnd()
        File target = new File(classesDir, "com/example/${controllerName}.class")
        target.parentFile.mkdirs()
        target.bytes = writer.toByteArray()
    }

    /**
     * Writes a class shaped like {@code @Scaffold(RestfulServiceController<Domain>)} after
     * ScaffoldingControllerInjector has run: the domain it extracted from the type argument is
     * written into {@code domain}, and {@code value} is left naming the class to extend. The two
     * are emitted in the order javac and groovyc actually emit them, domain first, which is what
     * makes reading whichever came last name the superclass as the domain.
     */
    private void writeSuperclassParameterizedController(String controllerName, String domainClassName) {
        String superclass = 'grails/plugin/scaffolding/RestfulServiceController'
        ClassWriter writer = new ClassWriter(0)
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/${controllerName}",
                "L${superclass}<Lcom/example/${domainClassName};>;", superclass, null)
        AnnotationVisitor annotation = writer.visitAnnotation(
                'Lgrails/plugin/scaffolding/annotation/Scaffold;', true)
        annotation.visit('domain', Type.getObjectType("com/example/${domainClassName}"))
        annotation.visit('value', Type.getObjectType(superclass))
        annotation.visitEnd()
        writer.visitEnd()
        File target = new File(classesDir, "com/example/${controllerName}.class")
        target.parentFile.mkdirs()
        target.bytes = writer.toByteArray()
    }

    /**
     * Writes a scaffolded controller into a package of its own, so two controllers of the same
     * simple name can be put in one classes directory the way an application does it.
     */
    private void writeControllerIn(String packagePath, String controllerName, String domainInternalName) {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "${packagePath}/${controllerName}", null,
                'java/lang/Object', null)
        AnnotationVisitor annotation = writer.visitAnnotation(
                'Lgrails/plugin/scaffolding/annotation/Scaffold;', true)
        annotation.visit('domain', Type.getObjectType(domainInternalName))
        annotation.visitEnd()
        writer.visitEnd()
        File target = new File(classesDir, "${packagePath}/${controllerName}.class")
        target.parentFile.mkdirs()
        target.bytes = writer.toByteArray()
    }

    private void writePlainController(String controllerName) {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/${controllerName}", null,
                'java/lang/Object', null)
        writer.visitEnd()
        File target = new File(classesDir, "com/example/${controllerName}.class")
        target.parentFile.mkdirs()
        target.bytes = writer.toByteArray()
    }

    private void writeNamespacedController(String name, String domainInternalName) {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, 'java/lang/Object', null)
        AnnotationVisitor annotation = writer.visitAnnotation('Lgrails/plugin/scaffolding/annotation/Scaffold;', true)
        annotation.visit('domain', Type.getObjectType(domainInternalName))
        annotation.visitEnd()
        writer.visitField(Opcodes.ACC_STATIC, 'namespace', 'Ljava/lang/String;', null, null).visitEnd()
        writer.visitEnd()
        File target = new File(classesDir, "${name}.class")
        target.parentFile.mkdirs()
        target.bytes = writer.toByteArray()
    }

    private GenerateScaffoldedViewsTask task(Object overrides = []) {
        Project project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.tasks.register('generateScaffoldedViews', GenerateScaffoldedViewsTask) {
            GenerateScaffoldedViewsTask it ->
                it.classesDirs.from(classesDir)
                it.runtimeClasspath.from(templateJar)
                it.runtimeClasspath.from(testClasspath())
                it.templateOverrides.from(overrides)
                it.outputDirectory.set(new File(projectDir, 'out'))
        }
        project.tasks.named('generateScaffoldedViews', GenerateScaffoldedViewsTask).get()
    }

    /** This test's own classpath, which carries the stand-in generator. */
    private static List<String> testClasspath() {
        System.getProperty('java.class.path').split(File.pathSeparator).toList()
    }

    /** What the generator was handed: each domain class and template path, with every copy of the template. */
    private Map<String, List<String>> handed(GenerateScaffoldedViewsTask task) {
        File root = new File(task.outputDirectory.get().asFile, 'grails-scaffolded')
        Map<String, List<String>> pages = new TreeMap<>()
        if (root.isDirectory()) {
            root.eachFileRecurse { File f ->
                if (f.isFile()) {
                    // <domain>/<copy>/<template path>.gsp, as the stand-in writes it
                    List<String> parts = root.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/' as char).tokenize('/')
                    String key = ([parts[0]] + parts.drop(2)).join('/') - '.gsp'
                    pages.computeIfAbsent(key) { [] } << f.getText('UTF-8')
                }
            }
        }
        pages.each { String key, List<String> copies -> copies.sort() }
        pages
    }

    void 'every template is expanded for every scaffolded domain class'() {
        given:
            writeController('UserController', 'User')
            writeController('BookController', 'Book')
            writeTemplateJar(templateJar, [show: 'show ${className}', index: 'index ${className}'])
            def task = task()

        when:
            task.generate()

        then:
            handed(task) == ['com.example.Book/index': ['index ${className}'], 'com.example.Book/show': ['show ${className}'],
                             'com.example.User/index': ['index ${className}'], 'com.example.User/show': ['show ${className}']]
    }

    void 'a controller without the annotation is left alone'() {
        given:
            writePlainController('PlainController')
            def task = task()

        when:
            task.generate()

        then:
            handed(task).isEmpty()
    }

    void 'the domain class named by the annotation drives the pages, not the controller'() {
        given: 'a controller whose name does not match the domain it scaffolds'
            writeController('AccountController', 'Person')
            def task = task()

        when:
            task.generate()

        then:
            handed(task).keySet()*.tokenize('/')*.first().unique() == ['com.example.Person']
    }

    void 'the domain attribute is read in preference to the class the annotation extends'() {
        given: 'a controller written as @Scaffold(RestfulServiceController<User>)'
            writeSuperclassParameterizedController('UserController', 'User')
            def task = task()

        when:
            task.generate()

        then:
            handed(task).keySet()*.tokenize('/')*.first().unique() == ['com.example.User']
    }

    void 'controllers of one name scaffolding different domains each get their own pages'() {
        given: 'com.example.UserController and com.example.community.UserController'
            writeControllerIn('com/example', 'UserController', 'com/example/User')
            writeControllerIn('com/example/community', 'UserController', 'com/example/community/User')
            def task = task()

        when:
            task.generate()

        then: 'pages are kept by domain class, so sharing a view directory name is nothing to them'
            handed(task).keySet()*.tokenize('/')*.first().unique() == ['com.example.User', 'com.example.community.User']
    }

    void 'a domain scaffolded by several controllers is handed over once'() {
        given:
            writeControllerIn('com/example', 'UserController', 'com/example/User')
            writeControllerIn('com/example/admin', 'UserController', 'com/example/User')
            def task = task()

        when:
            task.generate()

        then:
            handed(task).keySet().findAll { it.endsWith('/index') } == ['com.example.User/index'] as Set
    }

    void 'a namespaced controller is precompiled like any other'() {
        given:
            writeNamespacedController('com/example/admin/EventController', 'com/example/Event')
            def task = task()

        when:
            task.generate()

        then:
            handed(task)['com.example.Event/show'] == ['show ${className}']
    }

    void 'an application template and the dependency template it overrides are both expanded'() {
        given:
            writeController('UserController', 'User')
            File overrides = new File(projectDir, 'templates')
            overrides.mkdirs()
            new File(overrides, 'index.gsp').text = 'custom ${className}'
            def task = task(ProjectBuilder.builder().build().fileTree(overrides))

        when:
            task.generate()

        then: 'whichever the resolver chooses has its page, so nothing here decides for it'
            handed(task)['com.example.User/index'] == ['custom ${className}', 'list of ${propertyName} for ${className}']
            handed(task)['com.example.User/show'] == ['show ${className}']
    }

    void 'namespace-specific templates are handed over, from a dependency and from the application tree'() {
        given:
            writeTemplateJar(templateJar, [show: 'show ${className}', 'admin/show': 'admin show ${className}'])
            writeController('UserController', 'User')
            File overrides = new File(projectDir, 'templates')
            new File(overrides, 'staff').mkdirs()
            new File(overrides, 'staff/show.gsp').text = 'staff show ${className}'
            def task = task(ProjectBuilder.builder().build().fileTree(overrides))

        when:
            task.generate()

        then: 'which one a controller uses is only known when it is asked for, so all are ready'
            handed(task) == ['com.example.User/admin/show': ['admin show ${className}'], 'com.example.User/show': ['show ${className}'],
                             'com.example.User/staff/show': ['staff show ${className}']]
    }

    void 'templates are read from a classpath directory, namespace directories included'() {
        given:
            File resources = new File(projectDir, 'resources')
            new File(resources, 'META-INF/templates/scaffolding/admin').mkdirs()
            new File(resources, 'META-INF/templates/scaffolding/show.gsp').text = 'directory show'
            new File(resources, 'META-INF/templates/scaffolding/admin/show.gsp').text = 'directory admin show'
            writeController('UserController', 'User')
            def task = task()
            task.runtimeClasspath.setFrom([resources] + testClasspath())

        when:
            task.generate()

        then:
            handed(task) == ['com.example.User/admin/show': ['directory admin show'], 'com.example.User/show': ['directory show']]
    }

    void 'every copy of a template on the classpath is expanded'() {
        given:
            File later = new File(projectDir, 'later.jar')
            writeTemplateJar(later, [show: 'later show'])
            writeTemplateJar(templateJar, [show: 'show'])
            writeController('UserController', 'User')
            def task = task()
            task.runtimeClasspath.from(later)

        when:
            task.generate()

        then:
            handed(task) == ['com.example.User/show': ['later show', 'show']]
    }

    void 'identical copies of a template are expanded once'() {
        given:
            File same = new File(projectDir, 'same.jar')
            writeTemplateJar(same, [show: 'show'])
            writeTemplateJar(templateJar, [show: 'show'])
            writeController('UserController', 'User')
            def task = task()
            task.runtimeClasspath.from(same)

        when:
            task.generate()

        then:
            handed(task) == ['com.example.User/show': ['show']]
    }

    void 'a stale page from a previous run does not survive'() {
        given:
            writeController('UserController', 'User')
            def task = task()
            task.generate()
            File stale = new File(task.outputDirectory.get().asFile, 'grails-scaffolded/gone/stale.gsp')
            stale.parentFile.mkdirs()
            stale.text = 'stale'

        when:
            task.generate()

        then:
            !stale.exists()
    }

    void 'with no scaffolding library that generates pages, nothing is written and the build carries on'() {
        given:
            writeController('UserController', 'User')
            def task = task()
            task.runtimeClasspath.setFrom(templateJar)

        when:
            task.generate()

        then:
            handed(task).isEmpty()
    }

    void 'with no templates on the classpath nothing is written'() {
        given:
            writeController('UserController', 'User')
            templateJar.delete()
            def task = task()

        when:
            task.generate()

        then:
            handed(task).isEmpty()
    }

}

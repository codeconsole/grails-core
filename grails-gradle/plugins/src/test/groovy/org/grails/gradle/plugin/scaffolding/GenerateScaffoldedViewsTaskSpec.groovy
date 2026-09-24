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
                it.templateClasspath.from(templateJar)
                it.templateOverrides.from(overrides)
                it.outputDirectory.set(new File(projectDir, 'out'))
        }
        project.tasks.named('generateScaffoldedViews', GenerateScaffoldedViewsTask).get()
    }

    /** The pages written for a domain class, by content. */
    private Set<String> pages(GenerateScaffoldedViewsTask task, String domain) {
        File dir = new File(task.outputDirectory.get().asFile, "grails-scaffolded/${domain}")
        dir.isDirectory() ? (dir.listFiles()*.getText('UTF-8') as Set<String>) : ([] as Set<String>)
    }

    private List<String> domainsWithPages(GenerateScaffoldedViewsTask task) {
        File dir = new File(task.outputDirectory.get().asFile, 'grails-scaffolded')
        dir.isDirectory() ? dir.list().toList().sort() : []
    }

    void 'a scaffolded domain gets a page for every template'() {
        given:
            writeController('UserController', 'User')
            def task = task()

        when:
            task.generate()

        then:
            pages(task, 'com.example.User') == ['list of user for User', 'create User', 'edit User', 'show User'] as Set
    }

    void 'a page is written where the runtime resolver looks for it'() {
        given: 'the template and default-package domain of ScaffoldingViewResolverSpec, whose model is the same on every platform'
            writeTemplateJar(templateJar, [show: 'show ${className}'])
            writeControllerIn('com/example', 'MappingController', 'URLMapping')
            def task = task()

        when:
            task.generate()

        then: 'the name the resolver computes for the same template and model'
            new File(task.outputDirectory.get().asFile, 'grails-scaffolded/URLMapping/90edd843a67c1f52a2400a029acfc69e.gsp')
                    .getText('UTF-8') == 'show URLMapping'
    }

    void 'a template is expanded with every name the runtime binds'() {
        given:
            writeTemplateJar(templateJar, [show: '${className}|${fullName}|${propertyName}|${modelName}|${packageName}|' +
                    '${packagePath}|${simpleName}|${lowerCaseName}'])
            writeController('BookStoreController', 'BookStore')
            def task = task()

        when:
            task.generate()

        then:
            pages(task, 'com.example.BookStore') == ["BookStore|com.example.BookStore|bookStore|bookStore|com.example|" +
                    "com${File.separator}example|BookStore|book-store".toString()] as Set
    }

    void 'the qualified domain class is bound, so a template can declare the type of its model'() {
        given:
            writeTemplateJar(templateJar, [index: 'model="List<${fullName}> ${propertyName}List" in ${packageName}'])
            writeController('UserController', 'User')
            def task = task()

        when:
            task.generate()

        then:
            pages(task, 'com.example.User') == ['model="List<com.example.User> userList" in com.example'] as Set
    }

    void 'a controller without the annotation is left alone'() {
        given:
            writePlainController('PlainController')
            def task = task()

        when:
            task.generate()

        then:
            domainsWithPages(task).isEmpty()
    }

    void 'the domain class named by the annotation drives the model, not the controller'() {
        given: 'a controller whose name does not match the domain it scaffolds'
            writeController('AccountController', 'Person')
            def task = task()

        when:
            task.generate()

        then:
            domainsWithPages(task) == ['com.example.Person']
            'list of person for Person' in pages(task, 'com.example.Person')
    }

    void 'an application template overrides the one a dependency contributes'() {
        given:
            writeController('UserController', 'User')
            File overrides = new File(projectDir, 'templates')
            overrides.mkdirs()
            new File(overrides, 'index.gsp').text = 'custom ${className}'
            def task = task(ProjectBuilder.builder().build().fileTree(overrides))

        when:
            task.generate()

        then: 'only the page the resolver would expand for an application controller is written'
            pages(task, 'com.example.User') == ['custom User', 'create User', 'edit User', 'show User'] as Set
    }

    void 'a namespace-specific template from a dependency is expanded too'() {
        given:
            writeTemplateJar(templateJar, [show: 'show ${className}', 'admin/show': 'admin show ${className}'])
            writeController('UserController', 'User')
            def task = task()

        when:
            task.generate()

        then: 'which one a controller uses is only known when it is asked for, so both are ready'
            pages(task, 'com.example.User') == ['show User', 'admin show User'] as Set
    }

    void 'a namespace-specific override keeps its directory within the application tree'() {
        given:
            writeTemplateJar(templateJar, [show: 'show ${className}'])
            writeController('UserController', 'User')
            File overrides = new File(projectDir, 'templates')
            new File(overrides, 'admin').mkdirs()
            new File(overrides, 'admin/show.gsp').text = 'custom admin show ${className}'
            def task = task(ProjectBuilder.builder().build().fileTree(overrides))

        when:
            task.generate()

        then:
            pages(task, 'com.example.User') == ['show User', 'custom admin show User'] as Set
    }

    void 'templates are read from a classpath directory, namespace directories included'() {
        given:
            File resources = new File(projectDir, 'resources')
            new File(resources, 'META-INF/templates/scaffolding/admin').mkdirs()
            new File(resources, 'META-INF/templates/scaffolding/show.gsp').text = 'directory show ${className}'
            new File(resources, 'META-INF/templates/scaffolding/admin/show.gsp').text = 'directory admin show ${className}'
            writeController('UserController', 'User')
            def task = task()
            task.templateClasspath.setFrom(resources)

        when:
            task.generate()

        then:
            pages(task, 'com.example.User') == ['directory show User', 'directory admin show User'] as Set
    }

    void 'an earlier dependency template wins over a later one of the same path'() {
        given:
            File later = new File(projectDir, 'later.jar')
            writeTemplateJar(later, [show: 'later show ${className}'])
            writeTemplateJar(templateJar, [show: 'show ${className}'])
            writeController('UserController', 'User')
            def task = task()
            task.templateClasspath.from(later)

        when:
            task.generate()

        then:
            pages(task, 'com.example.User') == ['show User'] as Set
    }

    void 'a template of any name is expanded, not only the four a controller starts with'() {
        given:
            writeTemplateJar(templateJar, [search: 'search ${className}'])
            writeController('UserController', 'User')
            def task = task()

        when:
            task.generate()

        then:
            pages(task, 'com.example.User') == ['search User'] as Set
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

    void 'the domain attribute is read in preference to the class the annotation extends'() {
        given: 'a controller written as @Scaffold(RestfulServiceController<User>)'
            writeSuperclassParameterizedController('UserController', 'User')
            def task = task()

        when:
            task.generate()

        then: 'the pages are modelled on the domain, not on the class the annotation names'
            domainsWithPages(task) == ['com.example.User']
    }

    void 'controllers of one name scaffolding different domains each get their own pages'() {
        given: 'com.example.UserController and com.example.community.UserController'
            writeControllerIn('com/example', 'UserController', 'com/example/User')
            writeControllerIn('com/example/community', 'UserController', 'com/example/community/User')
            def task = task()

        when:
            task.generate()

        then: 'the pages are kept by domain, so sharing a view directory name is nothing to them'
            domainsWithPages(task) == ['com.example.User', 'com.example.community.User']
    }

    void 'a domain scaffolded by several controllers gets one set of pages'() {
        given:
            writeControllerIn('com/example', 'UserController', 'com/example/User')
            writeControllerIn('com/example/admin', 'UserController', 'com/example/User')
            def task = task()

        when:
            task.generate()

        then:
            new File(task.outputDirectory.get().asFile, 'grails-scaffolded/com.example.User').list().length == 4
    }

    void 'a namespaced controller is precompiled like any other'() {
        given:
            writeNamespacedController('com/example/admin/EventController', 'com/example/Event')
            def task = task()

        when:
            task.generate()

        then:
            pages(task, 'com.example.Event') == ['list of event for Event', 'create Event', 'edit Event', 'show Event'] as Set
    }

    void 'a template that cannot be expanded is left to the runtime and the others are still written'() {
        given:
            writeTemplateJar(templateJar, [show: 'show ${className}', broken: 'broken ${noSuchName}'])
            writeController('UserController', 'User')
            def task = task()

        when:
            task.generate()

        then:
            pages(task, 'com.example.User') == ['show User'] as Set
    }

    void 'with no templates on the classpath nothing is written'() {
        given:
            writeController('UserController', 'User')
            templateJar.delete()
            def task = task()

        when:
            task.generate()

        then:
            domainsWithPages(task).isEmpty()
    }

}

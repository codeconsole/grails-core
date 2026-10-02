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
package org.grails.io.support

import groovyjarjarasm.asm.ClassWriter
import groovyjarjarasm.asm.MethodVisitor
import groovyjarjarasm.asm.Opcodes
import spock.lang.Specification
import spock.lang.TempDir

class MainClassFinderSpec extends Specification {

    @TempDir
    File tmp

    void 'the main class is found on the compile classpath when the build directory is not build/'() {
        given:
        File app = project('app')
        File classes = new File(app, 'build-parent/build-8070/classes/groovy/main')
        writeMainClass(classes, 'example.Application')
        URI spec = new File(app, 'src/integration-test/groovy/example/SomeSpec.groovy').toURI()

        expect: 'nothing under build/, so only the classpath finds it'
        MainClassFinder.searchMainClass(spec, false) == null
        MainClassFinder.searchMainClass(spec, [classes], false) == 'example.Application'
    }

    void 'a classes directory of another project on the classpath is not searched'() {
        given: 'a plugin subproject inside the application directory, with a main class of its own'
        File app = project('app')
        File plugin = new File(app, 'modules/plugin')
        plugin.mkdirs()
        new File(plugin, 'build.gradle').text = ''
        File pluginClasses = new File(plugin, 'build/classes/groovy/main')
        writeMainClass(pluginClasses, 'plugin.Application')
        File appClasses = new File(app, 'out/classes/groovy/main')
        writeMainClass(appClasses, 'example.Application')
        URI spec = new File(app, 'src/integration-test/groovy/example/SomeSpec.groovy').toURI()

        expect:
        MainClassFinder.searchMainClass(spec, [pluginClasses, appClasses], false) == 'example.Application'
        MainClassFinder.searchMainClass(spec, [pluginClasses], false) == null
    }

    private File project(String name) {
        File dir = new File(tmp, name)
        new File(dir, 'grails-app').mkdirs()
        new File(dir, 'build.gradle').text = ''
        dir
    }

    /** A class with a main method extending GrailsAutoConfiguration, which is what the finder looks for. */
    private static void writeMainClass(File classesDir, String className) {
        String internalName = className.replace('.', '/')
        ClassWriter writer = new ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, 'grails/boot/config/GrailsAutoConfiguration', null)
        MethodVisitor main = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, 'main', '([Ljava/lang/String;)V', null, null)
        main.visitCode()
        main.visitInsn(Opcodes.RETURN)
        main.visitMaxs(0, 1)
        main.visitEnd()
        writer.visitEnd()
        File file = new File(classesDir, "${internalName}.class")
        file.parentFile.mkdirs()
        file.bytes = writer.toByteArray()
    }
}

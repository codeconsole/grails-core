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
package org.grails.compiler.beans

import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The class a {@code beans} closure compiles to is generated rather than written, so nobody can
 * list it in {@code AutoConfiguration.imports} without first knowing it exists. These drive a real
 * compilation with a target directory, which is the only thing that makes the file observable.
 */
class AutoConfigurationImportsWriterSpec extends Specification {

    private static final String IMPORTS =
            'META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports'

    @TempDir
    File projectDir

    private File targetDir
    private String previousBaseDir

    void setup() {
        targetDir = new File(projectDir, 'build/classes/groovy/main')
        targetDir.mkdirs()
        previousBaseDir = System.setProperty('base.dir', projectDir.absolutePath)
    }

    void cleanup() {
        if (previousBaseDir == null) {
            System.clearProperty('base.dir')
        }
        else {
            System.setProperty('base.dir', previousBaseDir)
        }
    }

    void 'the generated sibling registers itself'() {
        when:
        compile(plugin('Greeting'))

        then: 'the name only the compiler knows is written where Spring Boot reads it'
        importsEntries() == ['com.example.GreetingAutoConfiguration']
    }

    void 'autoConfigurationName registers under the name it asks for'() {
        when:
        compile(plugin('Renamed', "autoConfigurationName = 'LegacyAutoConfiguration'"))

        then: 'what is registered is the name the class is actually generated under'
        importsEntries() == ['com.example.LegacyAutoConfiguration']
    }

    void 'siblings from separate source units accumulate rather than replacing one another'() {
        when: 'two descriptors compile separately, as they do in a real build'
        compile(plugin('First'))
        compile(plugin('Second'))

        then:
        importsEntries() == ['com.example.FirstAutoConfiguration', 'com.example.SecondAutoConfiguration']
    }

    void 'a renamed descriptor does not leave its old entry behind'() {
        given: 'a descriptor compiles, is renamed, and the stale output is cleaned - an incremental build'
        compile(plugin('Greeting'))
        new File(targetDir, 'com/example/GreetingAutoConfiguration.class').delete()
        new File(targetDir, 'com/example/GreetingGrailsPlugin.class').delete()

        when:
        compile(plugin('Farewell'))

        then: 'an entry naming a class that is no longer generated fails Spring Boot at startup'
        importsEntries() == ['com.example.FarewellAutoConfiguration']
    }

    void 'a descriptor left untouched by an incremental build keeps its entry'() {
        given: 'two descriptors, then only the one about to be rebuilt is cleaned, as Gradle does'
        compile(plugin('Greeting'))
        compile(plugin('Farewell'))
        new File(targetDir, 'com/example/FarewellAutoConfiguration.class').delete()

        when:
        compile(plugin('Farewell'))

        then: 'the one that was not rebuilt is still generated, so it is still registered'
        importsEntries() == ['com.example.FarewellAutoConfiguration', 'com.example.GreetingAutoConfiguration']
    }

    void 'two descriptors recompiling together do not prune one another'() {
        given: 'both were built before, and both stale outputs are cleaned'
        compile(plugin('Greeting'))
        compile(plugin('Farewell'))
        new File(targetDir, 'com/example/GreetingAutoConfiguration.class').delete()
        new File(targetDir, 'com/example/FarewellAutoConfiguration.class').delete()

        when: 'they compile as one unit, so neither class file exists while the other registers'
        compileTogether([plugin('Greeting'), plugin('Farewell')])

        then:
        importsEntries() == ['com.example.FarewellAutoConfiguration', 'com.example.GreetingAutoConfiguration']
    }

    void 'a module that keeps the file by hand keeps it'() {
        given: 'a hand-authored file, which may hold entries no compilation can discover'
        File handAuthored = new File(projectDir, "src/main/resources/${IMPORTS}")
        handAuthored.parentFile.mkdirs()
        handAuthored.text = 'com.example.GreetingAutoConfiguration\ncom.elsewhere.FromAnotherJar\n'

        when:
        compile(plugin('Greeting'))

        then: 'nothing is generated beside it, which would put the same resource at the same path twice'
        !new File(targetDir, IMPORTS).exists()

        and: 'and the entries it alone knows about are untouched'
        handAuthored.readLines().contains('com.elsewhere.FromAnotherJar')
    }

    void 'a hand-authored file missing the generated class is warned about'() {
        given:
        File handAuthored = new File(projectDir, "src/main/resources/${IMPORTS}")
        handAuthored.parentFile.mkdirs()
        handAuthored.text = 'com.elsewhere.FromAnotherJar\n'

        when:
        compile(plugin('Greeting'))

        then: 'silently registering nothing is the failure this exists to prevent'
        warnings().any {
            it.contains('com.example.GreetingAutoConfiguration') && it.contains(IMPORTS)
        }
    }

    private List<String> collectedWarnings = []

    private List<String> warnings() {
        collectedWarnings
    }

    private List<String> importsEntries() {
        File file = new File(targetDir, IMPORTS)
        file.exists() ? file.readLines().findAll { it.trim() && !it.startsWith('#') } : []
    }

    private static String plugin(String name, String grailsBeansMembers = '') {
        """
            package com.example

            import grails.compiler.beans.GrailsBeans
            import grails.plugins.Plugin
            import org.springframework.boot.autoconfigure.AutoConfiguration

            @GrailsBeans(${grailsBeansMembers})
            @AutoConfiguration
            class ${name}GrailsPlugin extends Plugin {
                def beans = {
                    bean('${name.uncapitalize()}Greeting', String) { 'hello' }
                }
            }
        """
    }

    private CompilerConfiguration compileTogether(List<String> sources) {
        CompilationUnit unit = newUnit()
        sources.eachWithIndex { String source, int index -> unit.addSource("Together${index}.groovy", source) }
        run(unit)
    }

    /** A real compilation, since only a target directory makes the generated file observable. */
    private CompilerConfiguration compile(String source) {
        CompilationUnit unit = newUnit()
        unit.addSource("Source${System.identityHashCode(source)}.groovy", source)
        run(unit)
    }

    private CompilationUnit newUnit() {
        CompilerConfiguration configuration = new CompilerConfiguration()
        configuration.targetDirectory = targetDir
        new CompilationUnit(configuration, null, new GroovyClassLoader(getClass().classLoader, configuration))
    }

    /**
     * Compiled through to OUTPUT, which is the phase that writes the class files. Stopping earlier
     * would leave the output directory empty, and what is still generated there is exactly what
     * decides whether an entry is kept.
     */
    private CompilerConfiguration run(CompilationUnit unit) {
        try {
            unit.compile(Phases.OUTPUT)
        }
        catch (Exception ignored) {
            // the generated file is what is under test, not the class
        }
        List warningMessages = unit.errorCollector.warnings
        if (warningMessages) {
            collectedWarnings.addAll(warningMessages.collect { it.message?.toString() ?: it.toString() })
        }
        unit.configuration
    }

}

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
package org.apache.grails.buildsrc

import groovy.io.FileType
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases
import org.springframework.boot.autoconfigure.AutoConfiguration
import spock.lang.TempDir

import spock.lang.Specification

/**
 * Compiles small fixture sources to real {@code .class} files (the same way {@code groovyc} would)
 * and scans them, rather than mocking the classloading behaviour {@link GenerateAutoConfigurationImportsTask#scan}
 * depends on.
 */
class GenerateAutoConfigurationImportsTaskSpec extends Specification {

    @TempDir
    File tempDir

    private static void compileFixtures(File srcDir, File destDir) {
        CompilerConfiguration config = new CompilerConfiguration()
        config.targetDirectory = destDir
        CompilationUnit unit = new CompilationUnit(config)
        srcDir.eachFileRecurse(FileType.FILES) { File source ->
            if (source.name.endsWith('.groovy')) {
                unit.addSource(source)
            }
        }
        unit.compile(Phases.OUTPUT)
    }

    private static File springBootAutoconfigureClasspathEntry() {
        new File(AutoConfiguration.protectionDomain.codeSource.location.toURI())
    }

    /**
     * Compiled fixture classes are themselves Groovy classes (they implement
     * {@code groovy.lang.GroovyObject}), so the scratch classloader needs the Groovy runtime too -
     * exactly as a real project's {@code runtimeClasspath} always would.
     */
    private static File groovyRuntimeClasspathEntry() {
        new File(GroovyObject.protectionDomain.codeSource.location.toURI())
    }

    private static Set<File> testClasspath(File destDir) {
        [destDir, springBootAutoconfigureClasspathEntry(), groovyRuntimeClasspathEntry()] as Set
    }

    def "finds a top-level class annotated @AutoConfiguration"() {
        given:
        File srcDir = new File(tempDir, 'src')
        srcDir.mkdirs()
        new File(srcDir, 'RealAutoConfig.groovy').text = '''
            package fixture

            import org.springframework.boot.autoconfigure.AutoConfiguration

            @AutoConfiguration
            class RealAutoConfig {
            }
        '''
        File destDir = new File(tempDir, 'classes')
        destDir.mkdirs()
        compileFixtures(srcDir, destDir)

        when:
        SortedSet<String> found = GenerateAutoConfigurationImportsTask.scan([destDir] as Set, testClasspath(destDir))

        then:
        found == ['fixture.RealAutoConfig'] as SortedSet
    }

    def "ignores classes with no @AutoConfiguration annotation"() {
        given:
        File srcDir = new File(tempDir, 'src')
        srcDir.mkdirs()
        new File(srcDir, 'PlainClass.groovy').text = '''
            package fixture

            class PlainClass {
            }
        '''
        File destDir = new File(tempDir, 'classes')
        destDir.mkdirs()
        compileFixtures(srcDir, destDir)

        when:
        SortedSet<String> found = GenerateAutoConfigurationImportsTask.scan([destDir] as Set, testClasspath(destDir))

        then:
        found.isEmpty()
    }

    def "ignores nested/inner classes even when annotated"() {
        given: "an @AutoConfiguration nested inside a plain outer class"
        File srcDir = new File(tempDir, 'src')
        srcDir.mkdirs()
        new File(srcDir, 'Outer.groovy').text = '''
            package fixture

            import org.springframework.boot.autoconfigure.AutoConfiguration

            class Outer {
                @AutoConfiguration
                static class Nested {
                }
            }
        '''
        File destDir = new File(tempDir, 'classes')
        destDir.mkdirs()
        compileFixtures(srcDir, destDir)

        expect: "compilation really did produce a \$-named class file, proving this isn't a vacuous pass"
        new File(destDir, 'fixture/Outer$Nested.class').exists()

        when:
        SortedSet<String> found = GenerateAutoConfigurationImportsTask.scan([destDir] as Set, testClasspath(destDir))

        then:
        found.isEmpty()
    }

    def "returns an empty set for a nonexistent classes directory"() {
        expect:
        GenerateAutoConfigurationImportsTask.scan(
                [new File(tempDir, 'does-not-exist')] as Set, [springBootAutoconfigureClasspathEntry()] as Set).isEmpty()
    }

    def "reports classes that fail to load instead of silently dropping them"() {
        given: "a compiled class whose superclass is deliberately removed from the classpath afterwards"
        File srcDir = new File(tempDir, 'src')
        srcDir.mkdirs()
        new File(srcDir, 'Missing.groovy').text = 'class Missing {}'
        new File(srcDir, 'Broken.groovy').text = 'class Broken extends Missing {}'
        File destDir = new File(tempDir, 'classes')
        destDir.mkdirs()
        compileFixtures(srcDir, destDir)
        assert new File(destDir, 'Missing.class').delete()

        when:
        List<String> reportedFailures = []
        SortedSet<String> found = GenerateAutoConfigurationImportsTask.scan(
                [destDir] as Set, testClasspath(destDir)) { String className, Throwable failure ->
            reportedFailures << className
            assert failure != null
        }

        then: "the unloadable class is excluded from the result but not silently - the callback fires for it"
        found.isEmpty()
        reportedFailures == ['Broken']
    }

}

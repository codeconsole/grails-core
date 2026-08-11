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
package org.grails.taglib.index

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

import spock.lang.Specification
import spock.lang.TempDir

/**
 * The index is written per tag library class so that libraries packaged in separate jars merge on the
 * classpath with no build step combining them. These exercise that merge directly, including the
 * cases where two jars contribute to one namespace and where they disagree about the same tag.
 */
class TagLibraryIndexSpec extends Specification {

    @TempDir
    Path tempDir

    void 'tag libraries in separate jars merge into one namespace'() {
        given:
        URLClassLoader loader = loaderOver(
                jar('a.jar', [( 'com.a.OneTagLib'): ['g', 'alpha,beta']]),
                jar('b.jar', [(' com.b.TwoTagLib'.trim()): ['g', 'gamma']]))

        when:
        TagLibraryIndex index = TagLibraryIndex.load(loader)

        then:
        index.getTagNames('g') == ['alpha', 'beta', 'gamma'] as Set
        index.lookup('g', 'alpha').tagLibraryClassName() == 'com.a.OneTagLib'
        index.lookup('g', 'gamma').tagLibraryClassName() == 'com.b.TwoTagLib'

        cleanup:
        loader.close()
    }

    void 'separate namespaces stay separate'() {
        given:
        URLClassLoader loader = loaderOver(
                jar('a.jar', [('com.a.OneTagLib'): ['g', 'alpha']]),
                jar('b.jar', [('com.b.TwoTagLib'): ['f', 'alpha']]))

        when:
        TagLibraryIndex index = TagLibraryIndex.load(loader)

        then:
        index.namespaces == ['f', 'g'] as Set
        index.lookup('g', 'alpha').tagLibraryClassName() == 'com.a.OneTagLib'
        index.lookup('f', 'alpha').tagLibraryClassName() == 'com.b.TwoTagLib'

        cleanup:
        loader.close()
    }

    void 'an empty classpath yields an empty index rather than failing'() {
        given:
        URLClassLoader loader = loaderOver()

        expect:
        TagLibraryIndex.load(loader).isEmpty()

        cleanup:
        loader.close()
    }

    void 'a descriptor missing its namespace or class is ignored'() {
        given:
        Path incomplete = tempDir.resolve('bad.jar')
        new JarOutputStream(Files.newOutputStream(incomplete)).withCloseable { jar ->
            jar.putNextEntry(new JarEntry(TagLibraryIndex.INDEX_LOCATION + 'index.properties'))
            jar.write('com.bad.BrokenTagLib=\n'.bytes)
            jar.closeEntry()
            jar.putNextEntry(new JarEntry(TagLibraryIndex.INDEX_LOCATION + 'com.bad.BrokenTagLib.properties'))
            jar.write('tags=orphan\n'.bytes)
            jar.closeEntry()
        }
        URLClassLoader loader = loaderOver(incomplete)

        expect: 'a malformed descriptor leaves the tag unknown, so it resolves dynamically'
        TagLibraryIndex.load(loader).lookup('g', 'orphan') == null

        cleanup:
        loader.close()
    }

    private URLClassLoader loaderOver(Path... jars) {
        new URLClassLoader(jars.collect { it.toUri().toURL() } as URL[], (ClassLoader) null)
    }

    void 'a tag declared by two tag libraries is ambiguous and is not resolved statically'() {
        given: 'two jars whose tag libraries both declare g:shared'
        URLClassLoader loader = loaderOver(
                jar('a.jar', [('com.a.OneTagLib'): ['g', 'shared,onlyA']]),
                jar('b.jar', [('com.b.TwoTagLib'): ['g', 'shared,onlyB']]))

        when:
        TagLibraryIndex index = TagLibraryIndex.load(loader)

        then: 'which one wins depends on registration order at runtime, so it is left unresolved'
        index.isAmbiguous('g', 'shared')
        index.lookup('g', 'shared') == null
        index.getAmbiguousTagNames('g') == ['shared'] as Set

        and: 'tags declared by only one of them still resolve'
        index.lookup('g', 'onlyA').tagLibraryClassName() == 'com.a.OneTagLib'
        index.lookup('g', 'onlyB').tagLibraryClassName() == 'com.b.TwoTagLib'

        cleanup:
        loader.close()
    }

    void 'the same tag library seen twice on the classpath is not ambiguous'() {
        given: 'the same descriptor present in two jars, as a duplicated dependency produces'
        URLClassLoader loader = loaderOver(
                jar('a.jar', [('com.a.OneTagLib'): ['g', 'shared']]),
                jar('b.jar', [('com.a.OneTagLib'): ['g', 'shared']]))

        expect: 'it names one implementation, so there is nothing to disambiguate'
        TagLibraryIndex.load(loader).lookup('g', 'shared').tagLibraryClassName() == 'com.a.OneTagLib'

        cleanup:
        loader.close()
    }

    void 'a closure based tag is recorded but is not bindable'() {
        given:
        Path jarPath = tempDir.resolve('legacy.jar')
        new JarOutputStream(Files.newOutputStream(jarPath)).withCloseable { jar ->
            jar.putNextEntry(new JarEntry(TagLibraryIndex.INDEX_LOCATION + 'index.properties'))
            jar.write('com.legacy.OldTagLib=\n'.bytes)
            jar.closeEntry()
            jar.putNextEntry(new JarEntry(TagLibraryIndex.INDEX_LOCATION + 'com.legacy.OldTagLib.properties'))
            jar.write(("version=${TagLibraryIndex.FORMAT_VERSION}\nclass=com.legacy.OldTagLib\n" +
                    'namespace=legacy\ntags=asMethod:METHOD,asClosure:LEGACY_CLOSURE\n').bytes)
            jar.closeEntry()
        }
        URLClassLoader loader = loaderOver(jarPath)

        when:
        TagLibraryIndex index = TagLibraryIndex.load(loader)

        then: 'both are known, so neither is reported as a misspelling'
        index.getTagNames('legacy') == ['asClosure', 'asMethod'] as Set

        and: 'only the method form can be compiled into a direct invocation'
        index.lookup('legacy', 'asMethod').isBindable()
        !index.lookup('legacy', 'asClosure').isBindable()

        cleanup:
        loader.close()
    }

    void 'a descriptor written by a different format version is ignored'() {
        given:
        Path other = tempDir.resolve('future.jar')
        new JarOutputStream(Files.newOutputStream(other)).withCloseable { jar ->
            jar.putNextEntry(new JarEntry(TagLibraryIndex.INDEX_LOCATION + 'index.properties'))
            jar.write('com.future.NewTagLib=\n'.bytes)
            jar.closeEntry()
            jar.putNextEntry(new JarEntry(TagLibraryIndex.INDEX_LOCATION + 'com.future.NewTagLib.properties'))
            jar.write("version=${TagLibraryIndex.FORMAT_VERSION + 1}\nclass=com.future.NewTagLib\nnamespace=g\ntags=future\n".bytes)
            jar.closeEntry()
        }
        URLClassLoader loader = loaderOver(other)

        expect: 'its tags resolve dynamically rather than being read under the wrong rules'
        TagLibraryIndex.load(loader).lookup('g', 'future') == null

        cleanup:
        loader.close()
    }

    private Path jar(String name, Map<String, List<String>> tagLibs) {
        Path path = tempDir.resolve(name)
        new JarOutputStream(Files.newOutputStream(path)).withCloseable { jar ->
            jar.putNextEntry(new JarEntry(TagLibraryIndex.INDEX_LOCATION + 'index.properties'))
            jar.write(tagLibs.keySet().collect { "${it}=\n" }.join().bytes)
            jar.closeEntry()
            tagLibs.each { String className, List<String> namespaceAndTags ->
                jar.putNextEntry(new JarEntry(TagLibraryIndex.INDEX_LOCATION + className + '.properties'))
                String encodedTags = namespaceAndTags[1].split(',')
                        .collect { "${it}:METHOD" }.join(',')
                jar.write(("version=${TagLibraryIndex.FORMAT_VERSION}\nclass=${className}\n" +
                        "namespace=${namespaceAndTags[0]}\ntags=${encodedTags}\n").bytes)
                jar.closeEntry()
            }
        }
        path
    }
}

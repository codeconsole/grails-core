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
package org.grails.datastore.gorm.query.transform

import java.lang.reflect.Constructor

import groovy.lang.Reference
import spock.lang.Specification

/**
 * The where-query transform generates nested closures for association criteria. Those closures
 * must capture exactly the local variables they reference; historically they shared the outer
 * where-closure's VariableScope, so their captures depended on whether a later transformation
 * happened to recompute the scopes, producing nondeterministic bytecode between builds.
 */
class WhereQueryClosureCaptureSpec extends Specification {

    // Historical note: these domain class names were made unique across the test JVM
    // (ClosureCaptureBook/ClosureCaptureAuthor rather than the more generic Book/Author)
    // because AstPropertyResolveUtils used to cache resolved properties in a single
    // static map keyed by class name, so a same-named fixture in another spec could
    // collide with this one. AstPropertyResolveUtils now caches per-ClassNode instance
    // (see its javadoc), so that collision can no longer happen regardless of naming -
    // the unique names are kept only because they make the fixture's purpose clearer,
    // not because uniqueness is required for correctness.
    private static final String SERVICE_SOURCE = '''
import grails.gorm.DetachedCriteria
import grails.gorm.annotation.Entity

class ClosureCaptureBookQueryService {
    protected DetachedCriteria<ClosureCaptureBook> findQueryByBookIdAndAuthorName(Serializable bookId, String authorName) {
        ClosureCaptureBook.where {
            bookId == bookId && author.name == authorName
        }
    }
}

@Entity
class ClosureCaptureBook {
    Long bookId
    ClosureCaptureAuthor author
}

@Entity
class ClosureCaptureAuthor {
    String name
}
'''

    void "association criteria closures capture only the variables they reference"() {
        given: 'a where query with an association criterion referencing one of two parameters'
        GroovyClassLoader gcl = new GroovyClassLoader()
        gcl.parseClass(SERVICE_SOURCE)

        when: 'the generated closure classes for the where query are located'
        List<Class<?>> queryClosures = findQueryClosures(gcl).sort { it.name.count('$_closure') }
        Class<?> outerClosure = queryClosures.first()
        Class<?> associationClosure = queryClosures.last()

        then: 'the outer closure captures both parameters it references'
        outerClosure != null
        capturedReferenceCount(outerClosure) == 2

        and: 'the innermost association closure captures only the parameter it references'
        associationClosure.name.count('$_closure') > 1
        capturedReferenceCount(associationClosure) == 1
    }

    void "generated closure constructors are identical across compilations"() {
        when: 'the same source is compiled twice in isolated class loaders'
        List<String> firstSignatures = closureConstructorSignatures(new GroovyClassLoader())
        List<String> secondSignatures = closureConstructorSignatures(new GroovyClassLoader())

        then:
        !firstSignatures.empty
        firstSignatures == secondSignatures
    }

    private static List<String> closureConstructorSignatures(GroovyClassLoader gcl) {
        gcl.parseClass(SERVICE_SOURCE)
        findQueryClosures(gcl).collect { Class<?> closureClass ->
            Constructor<?> constructor = closureClass.declaredConstructors.first()
            "${closureClass.name.replaceAll(/^.*\$_/, '')}(${constructor.parameterTypes*.simpleName.join(', ')})".toString()
        }.sort()
    }

    private static List<Class<?>> findQueryClosures(GroovyClassLoader gcl) {
        gcl.loadedClasses.findAll { it.name.contains('_findQueryByBookIdAndAuthorName_') }
    }

    private static int capturedReferenceCount(Class<?> closureClass) {
        Constructor<?> constructor = closureClass.declaredConstructors.first()
        constructor.parameterTypes.count { it == Reference }
    }
}

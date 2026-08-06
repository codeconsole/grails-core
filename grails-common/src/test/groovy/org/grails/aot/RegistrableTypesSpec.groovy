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
package org.grails.aot

import spock.lang.Specification

/**
 * Covers which scanned types may be registered for reflection. Registering one that cannot be loaded
 * fails the image build rather than degrading at run time, which is why this is asked at all.
 */
class RegistrableTypesSpec extends Specification {

    ClassLoader loader = getClass().classLoader

    private InputStream bytecodeOf(Class<?> type) {
        loader.getResourceAsStream(type.name.replace('.', '/') + '.class')
    }

    void 'a type that loads may be registered'() {
        expect:
            RegistrableTypes.loads('java.lang.String', loader)
    }

    void 'a type that is absent may not'() {
        expect:
            !RegistrableTypes.loads('com.example.NotOnTheClasspath', loader)
    }

    void 'a nested type whose declaring class is absent may not'() {
        expect: 'this is the closure whose enclosing class extends something absent, which the ' +
                'closure reaches through invokedynamic and so never names itself'
            !RegistrableTypes.loads('com.example.Missing$_run_closure1', loader)
    }

    void 'a nested type whose declaring class loads may be'() {
        expect:
            RegistrableTypes.loads(Outer.Inner.name, loader)
    }

    void 'bytecode naming only types that load may be registered'() {
        expect:
            RegistrableTypes.referencesLoad(bytecodeOf(Outer), loader)
    }

    void 'bytecode is rejected when it cannot be read'() {
        expect:
            !RegistrableTypes.referencesLoad(new ByteArrayInputStream('not a class'.bytes), loader)
    }

    void 'a class loader without the framework on it accepts none of its types'() {
        given: 'a bootstrap-only loader, which still has the JDK but nothing else'
            ClassLoader empty = new URLClassLoader(new URL[0], null)

        expect:
            !RegistrableTypes.loads(RegistrableTypes.name, empty)
            RegistrableTypes.loads('java.lang.String', empty)
    }

    static class Outer {

        String describe() {
            new Inner().toString()
        }

        static class Inner {
        }
    }
}

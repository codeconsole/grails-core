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
package org.grails.io.watch

import java.lang.reflect.Field

import spock.lang.Specification

/**
 * Tests the watcher implementation {@link DirectoryWatcher} selects.
 *
 * <p>{@code io.methvin:directory-watcher} is {@code compileOnly}, so it is absent from this
 * module's test runtime — the same situation as an application that does not opt into it.
 * {@code net.java.dev.jna:jna} <em>is</em> on the test runtime classpath, reproducing the
 * common case of JNA arriving transitively (Testcontainers, docker-java, ...) without the
 * watcher library.</p>
 */
class DirectoryWatcherSpec extends Specification {

    private static AbstractDirectoryWatcher delegateOf(DirectoryWatcher watcher) {
        Field field = DirectoryWatcher.getDeclaredField('directoryWatcherDelegate')
        field.setAccessible(true)
        (AbstractDirectoryWatcher) field.get(watcher)
    }

    void 'the native macOS watcher is not selected when its optional dependency is absent'() {
        expect: 'the probe reflects this module compiling directory-watcher as compileOnly'
        !isPresent('io.methvin.watchservice.MacOSXListeningWatchService')

        when:
        AbstractDirectoryWatcher delegate = delegateOf(new DirectoryWatcher())

        then:
        !(delegate instanceof MacOsWatchServiceDirectoryWatcher)
    }

    void 'a WatchService based watcher is selected rather than falling back to polling'() {
        when:
        AbstractDirectoryWatcher delegate = delegateOf(new DirectoryWatcher())

        then: 'polling is a last resort and must not be reached on a JDK that has a WatchService'
        !(delegate instanceof PollingDirectoryWatcher)
        delegate instanceof WatchServiceDirectoryWatcher
    }

    void 'the presence of JNA alone does not select the native macOS watcher'() {
        given: 'JNA on the classpath, but not the watcher library that actually backs the native watcher'
        assert isPresent('com.sun.jna.Pointer')

        when:
        AbstractDirectoryWatcher delegate = delegateOf(new DirectoryWatcher())

        then: 'JNA is not a valid signal for native watcher availability'
        delegate instanceof WatchServiceDirectoryWatcher
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className)
            true
        }
        catch (Throwable ignored) {
            false
        }
    }
}

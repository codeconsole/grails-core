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

import java.lang.reflect.Method
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir
import spock.util.concurrent.PollingConditions
import spock.util.environment.RestoreSystemProperties

/**
 * Tests which watcher implementation {@link DirectoryWatcher} selects, and that the selected one
 * reports changes.
 *
 * <p>The native macOS watcher needs {@code io.methvin:directory-watcher}, which this module declares
 * {@code compileOnly}. The test runtime therefore does not carry it — matching an application that
 * has not opted in — while {@code net.java.dev.jna:jna} is present, reproducing the common case of
 * JNA arriving transitively (Testcontainers, docker-java, ...) on its own. The cases that need the
 * optional library are covered by loading the watcher classes in an isolated class loader built from
 * the {@code optionalWatcher} configuration.</p>
 */
@RestoreSystemProperties
class DirectoryWatcherSpec extends Specification {

    private static final String MAC_OS = 'Mac OS X'
    private static final String NATIVE_WATCH_SERVICE = 'io.methvin.watchservice.MacOSXListeningWatchService'

    @TempDir
    Path watchedDirectory

    void 'JNA alone does not select the native macOS watcher'() {
        given: 'the classpath shape produced by a transitively acquired JNA'
        assert isPresent('com.sun.jna.Pointer')
        assert !isPresent(NATIVE_WATCH_SERVICE)
        System.setProperty('os.name', MAC_OS)

        expect: 'JNA is not a valid signal for native watcher availability'
        new DirectoryWatcher().directoryWatcherDelegate instanceof WatchServiceDirectoryWatcher
    }

    void 'the JDK watcher is selected off macOS'() {
        given:
        System.setProperty('os.name', 'Linux')

        expect:
        new DirectoryWatcher().directoryWatcherDelegate instanceof WatchServiceDirectoryWatcher
    }

    void 'polling is not selected while the JDK can supply a WatchService'() {
        expect: 'polling is a last resort, unreachable on the supported Java baseline'
        !(new DirectoryWatcher().directoryWatcherDelegate instanceof PollingDirectoryWatcher)
    }

    @Requires({ DirectoryWatcherSpec.optionalWatcherJars() })
    void 'the native macOS watcher is selected when the optional library is available'() {
        given:
        System.setProperty('os.name', MAC_OS)

        expect:
        selectedDelegateIn(isolatedLoader(optionalWatcherJars(), true)) == 'MacOsWatchServiceDirectoryWatcher'
    }

    @Requires({ DirectoryWatcherSpec.optionalWatcherJars() })
    void 'the optional library without JNA degrades instead of failing construction'() {
        given: 'directory-watcher present but the JNA it links against absent, which raises a LinkageError'
        System.setProperty('os.name', MAC_OS)

        expect: 'the linkage failure is treated as the native watcher simply being unavailable'
        selectedDelegateIn(isolatedLoader(optionalWatcherJars(), false)) == 'WatchServiceDirectoryWatcher'
    }

    void 'the selected watcher reports a change to a watched file'() {
        given:
        File directory = watchedDirectory.toFile()
        File watched = new File(directory, 'watched.txt')
        watched.text = 'initial'

        List<File> changed = new CopyOnWriteArrayList<>()
        DirectoryWatcher watcher = new DirectoryWatcher()
        watcher.sleepTime = 100
        watcher.addListener(new DirectoryWatcher.FileChangeListener() {
            void onChange(File file) { changed << file }

            void onNew(File file) { changed << file }
        })
        watcher.addWatchDirectory(directory, 'txt')
        watcher.start()

        when: 'the watched file is modified after the watcher has started'
        // the JDK WatchService falls back to polling on some platforms, so allow a generous window
        Thread.sleep(1000)
        watched.text = 'modified'

        then:
        new PollingConditions(timeout: 60, initialDelay: 1).eventually {
            assert changed*.name.contains('watched.txt')
        }

        cleanup:
        watcher.active = false
    }

    /**
     * Loads the watcher classes in a class loader isolated from the test runtime so the optional
     * library, and JNA, can be present or absent independently of this JVM's own classpath.
     */
    private static ClassLoader isolatedLoader(List<File> optionalWatcher, boolean includeJna) {
        List<File> entries = System.getProperty('java.class.path')
                .split(File.pathSeparator)
                .collect { new File(it) }
        // directory-watcher depends on JNA, so the exclusion has to cover the optional jars too
        entries.addAll(optionalWatcher)
        URL[] urls = entries
                .findAll { includeJna || !(it.name ==~ /jna(-platform)?-\d.*\.jar/) }
                .collect { it.toURI().toURL() }
        // the platform loader as parent keeps this JVM's application classpath out of the picture
        new URLClassLoader(urls, ClassLoader.platformClassLoader)
    }

    private static String selectedDelegateIn(ClassLoader loader) {
        Class<?> watcherClass = loader.loadClass(DirectoryWatcher.name)
        Object watcher = watcherClass.getDeclaredConstructor().newInstance()
        // getDirectoryWatcherDelegate is package-private, and this spec's package is a different
        // runtime package once the class comes from another loader, so access has to be requested
        Method delegate = watcherClass.getDeclaredMethod('getDirectoryWatcherDelegate')
        delegate.accessible = true
        delegate.invoke(watcher).getClass().simpleName
    }

    static List<File> optionalWatcherJars() {
        String path = System.getProperty('grails.test.optionalWatcherClasspath')
        path ? path.split(File.pathSeparator).collect { new File(it) }.findAll { it.exists() } : []
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

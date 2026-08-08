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
package org.grails.gradle.plugin.aot

import java.security.MessageDigest
import java.time.Duration

import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Runs the application once so the JDK can write down what starting it needs.
 *
 * <p>The run is the point. A cache records the classes loaded and linked, and the profiles of the
 * methods that ran, so the next start reads them rather than working them out again -- which means
 * what the next start is <em>fast at</em> is whatever this run did. A run that only refreshes the
 * context leaves every request path to be worked out on the day.</p>
 *
 * <p>So the application is started, asked for the pages an application is asked for, and then asked
 * to stop. It has to stop of its own accord: the cache is written as the JVM exits, and a run that is
 * killed writes nothing.</p>
 *
 * @since 8.0
 */
@CompileStatic
@DisableCachingByDefault(because = 'Runs the application and records what it did, which is not reproducible')
abstract class TrainAotCacheTask extends DefaultTask {

    /**
     * The extracted application: the cache is only usable against the layout it was trained on.
     *
     * <p>Compared by what is in it and where each file sits within it, not by where the directory
     * itself is. Without saying so, Gradle takes the absolute path to be part of the input and
     * refuses to validate the task at all -- and a build that did run would key its result to a
     * path, so the same application checked out somewhere else, or built on CI, would agree about
     * everything and still share nothing.</p>
     */
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getApplicationDirectory()

    @Input
    abstract Property<String> getArchiveFileName()

    @OutputFile
    abstract RegularFileProperty getCacheFile()

    @Input
    abstract Property<String> getJavaExecutable()

    /** The version of the JDK above, which is the JDK the cache will only ever be readable by. */
    @Input
    abstract Property<String> getJavaVersion()

    @Input
    abstract Property<String> getJavaVendor()

    /** Given to the training run, and to be given to every run that reads the cache. */
    @Input
    abstract ListProperty<String> getJvmArguments()

    /** The paths to ask for, so their methods are profiled rather than met for the first time later. */
    @Input
    abstract ListProperty<String> getPaths()

    @Input
    abstract Property<Integer> getPort()

    @Input
    abstract Property<Integer> getStartTimeoutSeconds()

    /** Written beside the cache, so what the cache was made from can be checked before it is used. */
    @OutputFile
    abstract RegularFileProperty getMetadataFile()

    @TaskAction
    void train() {
        refuseWhereTheRunCannotBeAskedToStop()
        File directory = applicationDirectory.get().asFile
        File cache = cacheFile.get().asFile
        cache.delete()

        List<String> command = []
        command << javaExecutable.get()
        command << "-XX:AOTCacheOutput=${cache.absolutePath}".toString()
        command.addAll(jvmArguments.get())
        command << '-jar' << archiveFileName.get()
        command << "--server.port=${port.get()}".toString()

        File output = new File(temporaryDir, 'training.log')
        Process process = new ProcessBuilder(command)
                .directory(directory)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .start()
        try {
            awaitStarted(process, output)
            exercise()
        }
        finally {
            stop(process)
        }
        if (!cache.isFile()) {
            throw new GradleException("The training run wrote no cache. What it printed is in ${output}")
        }
        describe(cache, new File(directory, archiveFileName.get()))
        logger.lifecycle('Trained {} ({} MB) over {} paths',
                cache.name, (cache.length() / (1024 * 1024)) as long, paths.get().size())
    }

    /**
     * Refuses to start a run that could not be ended properly, before it is started.
     *
     * <p>The cache is written as the training JVM exits normally, so the run has to be asked to
     * stop rather than killed. {@link Process#destroy()} asks on POSIX and kills on Windows, where
     * it is {@code TerminateProcess} and no shutdown runs -- so on Windows the run would be
     * exercised in full, killed, and leave no cache, and the build would fail at the end saying the
     * cache was not written rather than saying why it could not be.</p>
     */
    private static void refuseWhereTheRunCannotBeAskedToStop() {
        String os = System.getProperty('os.name', '')
        if (os.toLowerCase(Locale.ROOT).contains('win')) {
            throw new GradleException('Training an AOT cache needs the training run to be asked to ' +
                    'stop, and on Windows a child process can only be killed -- which writes no ' +
                    'cache. Train on Linux or macOS, or set grails.aotCache.enabled to false.')
        }
    }

    /**
     * Writes down what the cache was made from.
     *
     * <p>A cache is read only by the JDK build that wrote it, against the archive it was trained on,
     * with the arguments it was trained with. A JVM given one made from anything else declines it and
     * starts as it would have anyway -- so what is lost is the speed, silently, and this is what
     * tells a deployment which of those it has.</p>
     */
    private void describe(File cache, File archive) {
        Properties properties = new Properties()
        properties.setProperty('cache.file', cache.name)
        properties.setProperty('cache.bytes', String.valueOf(cache.length()))
        properties.setProperty('application.archive', archive.name)
        properties.setProperty('application.sha256', sha256(archive))
        properties.setProperty('training.arguments', jvmArguments.get().join(' '))
        properties.setProperty('training.paths', paths.get().join(' '))
        properties.setProperty('java.vendor', javaVendor.get())
        properties.setProperty('java.runtime.version', javaVersion.get())
        properties.setProperty('os.name', System.getProperty('os.name', ''))
        properties.setProperty('os.arch', System.getProperty('os.arch', ''))
        metadataFile.get().asFile.withOutputStream { OutputStream out ->
            properties.store(out, 'What this AOT cache was trained from')
        }
    }

    private static String sha256(File file) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        file.withInputStream { InputStream input ->
            byte[] buffer = new byte[8192]
            int read
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().encodeHex().toString()
    }

    /**
     * Waits for the application to start serving, and stops waiting if the run ends first --
     * otherwise a run that fails immediately is waited on for the whole timeout.
     *
     * <p>Either the run says it started or its port answers. The message is Spring Boot's and is
     * the earlier and clearer of the two, but it is an INFO log an application is free to turn off
     * or reword; a port that accepts a connection is the application's own doing and cannot be
     * configured away while it is still an application worth training.</p>
     */
    private void awaitStarted(Process process, File output) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(startTimeoutSeconds.get()).toMillis()
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new GradleException('The training run ended before it started serving. ' +
                        "What it printed is in ${output}")
            }
            if (output.isFile() && output.text.contains('Started ')) {
                return
            }
            if (serving()) {
                return
            }
            Thread.sleep(250L)
        }
        throw new GradleException("The training run did not start within ${startTimeoutSeconds.get()}s. " +
                "What it printed is in ${output}")
    }

    /** Whether anything is accepting connections on the port the run was told to listen on. */
    private boolean serving() {
        try {
            new Socket().withCloseable { Socket socket ->
                socket.connect(new InetSocketAddress('localhost', port.get()), 500)
                true
            }
        }
        catch (IOException ignored) {
            false
        }
    }

    /**
     * Asks for each path. A path that answers with an error still profiled the code that produced
     * the error, which is code an application runs too, so nothing here fails the build: the run is
     * a recording, not a test.
     *
     * <p>That covers a path that is not a URI as much as one that answers with a 500. A path
     * written without its leading slash makes a URI with no valid authority, and rejecting it here
     * would fail a build over a typo in a list whose whole purpose is to make the next start
     * quicker.</p>
     */
    private void exercise() {
        for (String path : paths.get()) {
            try {
                URI uri = URI.create("http://localhost:${port.get()}${path}")
                HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection()
                connection.requestMethod = 'GET'
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                connection.setRequestProperty('Accept', 'text/html,*/*')
                int status = connection.responseCode
                InputStream body = status >= 400 ? connection.errorStream : connection.inputStream
                body?.withCloseable { it.bytes }
                logger.info('Trained {} -> {}', path, status)
            }
            catch (IOException | RuntimeException e) {
                logger.info('Could not reach {} while training: {}', path, e.message)
            }
        }
    }

    /**
     * Asks the run to stop and waits for it. The cache is written as the JVM exits, so this waits
     * for the exit rather than for the port to close.
     */
    private void stop(Process process) {
        if (!process.isAlive()) {
            return
        }
        process.destroy()
        if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw new GradleException('The training run did not stop, so no cache was written')
        }
    }
}

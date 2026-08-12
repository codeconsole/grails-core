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

import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern

import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Runs the application under GraalVM's tracing agent and writes down the reflection it did.
 *
 * <p>{@code GenerateNativeMetadataTask} records an application's own artefacts by reading the build
 * output, which needs no run and misses nothing of the application's. What it cannot know is the
 * framework's own reflection along a request path -- a controller method reached through Groovy's
 * dispatch, a conversion asked for while binding a form -- because none of that is in the
 * application's classes. An image built without it starts, serves its home page, and fails on the
 * request that first takes such a path.</p>
 *
 * <p>The agent records exactly that, and records only what ran. So the paths are declared rather
 * than discovered, and what is not listed is not covered:</p>
 *
 * <pre>
 * grails {
 *     nativeMetadata {
 *         paths = ['/', '/login', '/book', '/book/create']
 *         forms = ['/book/create']
 *     }
 * }
 * </pre>
 *
 * <p>A form is asked for, read, and submitted with the fields it declares, because posting fewer
 * than it declares records less than the application does. A checkbox is a string on the wire and a
 * boolean on the domain class, so a form submitted without one never asks the conversion service
 * anything -- and an image built from that trace fails the first time someone ticks a box.</p>
 *
 * <p>Written to the application's sources rather than to the build directory: what an image was
 * built from should be reviewable, and a trace is only as good as the paths someone thought of.</p>
 *
 * @since 8.0
 */
@CompileStatic
@DisableCachingByDefault(because = 'Records what a run of the application did, which is not an output of its inputs')
abstract class TraceNativeMetadataTask extends DefaultTask {

    /** How the agent is asked for, and the only way its output can be merged with what is there. */
    private static final String AGENT = 'native-image-agent'

    /** The names the agent library goes by, one of which is beside a GraalVM's java. */
    private static final List<String> AGENT_LIBRARIES = [
            'libnative-image-agent.dylib', 'libnative-image-agent.so', 'native-image-agent.dll'
    ]

    private static final Pattern FORM = Pattern.compile(/(?is)<form\b[^>]*>.*?<\/form>/)
    private static final Pattern ACTION = Pattern.compile(/(?i)\baction\s*=\s*"([^"]*)"/)
    private static final Pattern FIELD = Pattern.compile(/(?is)<(?:input|select|textarea)\b[^>]*>/)
    private static final Pattern NAME = Pattern.compile(/(?i)\bname\s*=\s*"([^"]+)"/)
    private static final Pattern VALUE = Pattern.compile(/(?i)\bvalue\s*=\s*"([^"]*)"/)
    private static final Pattern TYPE = Pattern.compile(/(?i)\btype\s*=\s*"([^"]+)"/)

    /** The archive to run, which has to be the one the image will be built from. */
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getArchiveFile()

    /**
     * A java from a GraalVM. The agent ships with GraalVM rather than with a JDK, and an application
     * built for an image is compiled for GraalVM's Java -- so the JDK that runs the build is usually
     * neither, and running the trace on it fails at load or refuses the class file.
     */
    @Input
    abstract Property<String> getJavaExecutable()

    @Input
    abstract ListProperty<String> getJvmArguments()

    /** Paths to ask for. */
    @Input
    abstract ListProperty<String> getPaths()

    /** Pages whose form is to be filled in and submitted. */
    @Input
    abstract ListProperty<String> getForms()

    @Input
    abstract Property<Integer> getPort()

    @Input
    abstract Property<Integer> getStartTimeoutSeconds()

    /**
     * Where the agent merges what it recorded. Not declared as an output: it is in the application's
     * sources, and a directory Gradle believes it owns is a directory Gradle will delete.
     */
    @Internal
    abstract DirectoryProperty getOutputDirectory()

    @TaskAction
    void trace() {
        File java = new File(javaExecutable.get())
        File metadata = outputDirectory.get().asFile
        metadata.mkdirs()
        refuseWithoutAgent(java)

        List<String> command = []
        command << java.absolutePath
        command << "-agentlib:${AGENT}=config-merge-dir=${metadata.absolutePath}".toString()
        command.addAll(jvmArguments.get())
        command << '-jar' << archiveFile.get().asFile.absolutePath
        command << "--server.port=${port.get()}".toString()

        File output = new File(temporaryDir, 'trace.log')
        Process process = new ProcessBuilder(command)
                .directory(archiveFile.get().asFile.parentFile)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .start()

        List<String> answered = []
        // What carries a session from one request to the next, which is how a form that
        // authenticates is worth submitting at all. It is the JVM's, and this JVM is a daemon that
        // outlives the build -- so it is put back, whether the trace finished or threw. Left set, it
        // would be handed to every task that ran afterwards, holding one application's cookies.
        CookieHandler inherited = CookieHandler.default
        try {
            awaitStarted(process, output)
            CookieHandler.default = new CookieManager()
            paths.get().each { String path -> answered << ask(path) }
            forms.get().each { String path -> answered << submit(path) }
        }
        finally {
            CookieHandler.default = inherited
            stop(process)
        }

        answered.each { String line -> logger.lifecycle('  {}', line) }
        List<String> failed = answered.findAll { String line -> line.contains(' -> 5') }
        if (failed) {
            throw new GradleException('The application answered with an error while being traced, so ' +
                    "what was recorded is the error rather than the page:\n  ${failed.join('\n  ')}\n" +
                    "What it printed is in ${output}")
        }
        logger.lifecycle('Traced {} paths and {} forms into {}',
                paths.get().size(), forms.get().size(), metadata)
    }

    /**
     * Refuses before starting rather than after. Without the agent the JVM stops at load with a
     * message about a library path, which reads as a broken machine rather than as the wrong JDK.
     */
    private static void refuseWithoutAgent(File java) {
        File home = java.parentFile?.parentFile
        boolean present = home != null && AGENT_LIBRARIES.any { String library ->
            new File(home, "lib/${library}").isFile()
        }
        if (!present) {
            throw new GradleException("${java} has no ${AGENT}, which ships with GraalVM rather than " +
                    'with a JDK. Point the project toolchain at a GraalVM, or set ' +
                    'grails.nativeMetadata.javaExecutable at one.')
        }
    }

    /** Asks for a path, reading the body so that rendering it is part of what was recorded. */
    private String ask(String path) {
        HttpURLConnection connection = open(path, 'GET')
        int status = read(connection)
        "GET  ${path} -> ${status}".toString()
    }

    /**
     * Fills in the form on a page and submits it to wherever the form says.
     *
     * <p>The fields come from the page rather than from a list here, because a list here is a list
     * to keep up to date, and the one thing a trace must not do is submit less than the form does.</p>
     */
    private String submit(String declared) {
        // A form may say what to put in it: /login?username=admin&password=... Most do not need to,
        // and a generated value is better than one to keep up to date -- but a form that authenticates
        // is only worth submitting with credentials that work, and what it does on success is the
        // half worth recording.
        int query = declared.indexOf('?')
        String path = query < 0 ? declared : declared.substring(0, query)
        Map<String, String> given = [:]
        if (query >= 0) {
            for (String pair : declared.substring(query + 1).split('&')) {
                if (!pair) {
                    continue
                }
                int equals = pair.indexOf('=')
                String name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), 'UTF-8')
                given[name] = equals < 0 ? '' : URLDecoder.decode(pair.substring(equals + 1), 'UTF-8')
            }
        }

        HttpURLConnection page = open(path, 'GET')
        String html = body(page)
        Matcher form = FORM.matcher(html ?: '')
        if (!form.find()) {
            return "FORM ${path} -> no form on the page".toString()
        }
        String markup = form.group()
        Matcher action = ACTION.matcher(markup)
        String target = action.find() && action.group(1) ? action.group(1) : path

        Map<String, String> fields = fieldsOf(markup)
        if (!fields) {
            return "FORM ${path} -> the form declares no fields".toString()
        }
        // What was asked for wins, but only for a field the form has: a value for a field that is
        // not there was meant for a form that has changed, and silently posting it says nothing.
        given.each { String name, String value ->
            if (fields.containsKey(name)) {
                fields[name] = value
            }
            else {
                logger.warn('{} has no field named {}, so that value was not sent', path, name)
            }
        }

        String encoded = fields.collect { String name, String value ->
            "${URLEncoder.encode(name, 'UTF-8')}=${URLEncoder.encode(value, 'UTF-8')}"
        }.join('&')

        HttpURLConnection post = open(target, 'POST')
        post.doOutput = true
        post.setRequestProperty('Content-Type', 'application/x-www-form-urlencoded')
        post.outputStream.withCloseable { OutputStream out -> out.write(encoded.bytes) }
        int status = read(post)
        "POST ${target} -> ${status}  (${fields.size()} fields, from ${path})".toString()
    }

    /**
     * What the form would send. A field that carries a value sends it, which is how the token a
     * security filter demands is carried; a checkbox sends what a ticked one sends; anything else
     * sends something recognisable, so a row in a database says where it came from.
     */
    private static Map<String, String> fieldsOf(String markup) {
        Map<String, String> fields = [:]
        Matcher field = FIELD.matcher(markup)
        while (field.find()) {
            String tag = field.group()
            Matcher name = NAME.matcher(tag)
            if (!name.find()) {
                continue
            }
            Matcher value = VALUE.matcher(tag)
            Matcher type = TYPE.matcher(tag)
            String kind = type.find() ? type.group(1).toLowerCase(Locale.ROOT) : 'text'
            if (kind in ['submit', 'button', 'reset', 'image', 'file']) {
                continue
            }
            fields[name.group(1)] = value.find() ? value.group(1)
                    : kind == 'checkbox' || kind == 'radio' ? 'on'
                    : kind == 'password' ? 'traced-secret'
                    : "traced-${System.nanoTime()}".toString()
        }
        fields
    }

    private HttpURLConnection open(String path, String method) {
        URI uri = URI.create("http://localhost:${port.get()}${path.startsWith('/') ? path : '/' + path}")
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection()
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        connection.setRequestProperty('Accept', 'text/html,*/*')
        connection
    }

    private static int read(HttpURLConnection connection) {
        int status = connection.responseCode
        InputStream stream = status >= 400 ? connection.errorStream : connection.inputStream
        stream?.withCloseable { InputStream it -> it.bytes }
        status
    }

    private static String body(HttpURLConnection connection) {
        int status = connection.responseCode
        InputStream stream = status >= 400 ? connection.errorStream : connection.inputStream
        stream?.withCloseable { InputStream it -> new String(it.bytes, 'UTF-8') }
    }

    private void awaitStarted(Process process, File output) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(startTimeoutSeconds.get()).toMillis()
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new GradleException('The application ended before it started serving. ' +
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
        throw new GradleException("The application did not start within ${startTimeoutSeconds.get()}s. " +
                "What it printed is in ${output}")
    }

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
     * Asks the run to stop and waits for it. The agent writes what it recorded as the JVM exits, so
     * a run that is killed leaves the metadata as it found it.
     */
    private static void stop(Process process) {
        if (!process.isAlive()) {
            return
        }
        process.destroy()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw new GradleException('The application did not stop, so the agent wrote nothing')
        }
    }
}

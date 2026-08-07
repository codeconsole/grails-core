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

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Covers the run that writes down what starting the application needs.
 *
 * <p>The cache is written as the training JVM exits, so a run that never started, or one that was
 * killed, leaves nothing behind -- and a build that carried on regardless would ship an application
 * with no cache and no indication that the speed it was built for is absent.</p>
 */
class TrainAotCacheTaskSpec extends Specification {

    @TempDir
    File temporaryFolder

    Project project = ProjectBuilder.builder().build()

    /** Where the fixture writes the paths it was asked for, so the spec can read them back. */
    private File requestedFile

    List<String> getRequested() {
        requestedFile?.isFile() ? requestedFile.readLines().findAll { it } : []
    }

    private static Properties describedBy(TrainAotCacheTask task) {
        Properties properties = new Properties()
        task.metadataFile.get().asFile.withInputStream { properties.load(it) }
        properties
    }

    private static String sha256Of(File file) {
        java.security.MessageDigest.getInstance('SHA-256').digest(file.bytes).encodeHex().toString()
    }

    /**
     * A task whose training run really starts, serves and stops.
     *
     * <p>Launched through a script standing in for the JVM: the run is given
     * {@code -XX:AOTCacheOutput}, which a JDK before 25 refuses outright, and a test of this task
     * should not also be a test of which JDK the build is running on.</p>
     */
    private TrainAotCacheTask servingTask(List<String> paths, boolean announcesItself = true) {
        File application = new File(temporaryFolder, 'serving')
        application.mkdirs()
        new File(application, 'served.jar').text = 'stands in for the archive, and is what is digested'
        requestedFile = new File(temporaryFolder, 'requested.txt')
        requestedFile.text = ''

        File script = new File(temporaryFolder, 'fake-jvm.sh')
        script.text = """#!/bin/sh
cache=""
port=""
for arg in "\$@"; do
  case "\$arg" in
    -XX:AOTCacheOutput=*) cache="\${arg#-XX:AOTCacheOutput=}" ;;
    --server.port=*) port="\${arg#--server.port=}" ;;
  esac
done
exec '${new File(System.getProperty('java.home'), 'bin/java').absolutePath}' \\
  -cp '${System.getProperty('java.class.path')}' \\
  ${TrainingRunFixture.name} "\$cache" "\$port" '${requestedFile.absolutePath}' ${announcesItself ? 'loud' : 'quiet'}
"""
        script.setExecutable(true)

        TrainAotCacheTask task = project.tasks.create('trainServing', TrainAotCacheTask)
        task.applicationDirectory.set(application)
        task.archiveFileName.set('served.jar')
        task.cacheFile.set(new File(application, 'demo.aot'))
        task.metadataFile.set(new File(application, 'aot-cache.properties'))
        task.javaExecutable.set(script.absolutePath)
        task.javaVersion.set('25.0.1+9')
        task.javaVendor.set('A Vendor')
        task.jvmArguments.set(['-Dspring.aot.enabled=true'])
        task.paths.set(paths)
        task.port.set(18098)
        task.startTimeoutSeconds.set(60)
        task
    }

    private TrainAotCacheTask task(String archiveName, List<String> arguments) {
        File application = new File(temporaryFolder, 'application')
        application.mkdirs()
        new File(application, archiveName).text = 'not a real archive'
        TrainAotCacheTask task = project.tasks.create('trainAotCache', TrainAotCacheTask)
        task.applicationDirectory.set(application)
        task.archiveFileName.set(archiveName)
        task.cacheFile.set(new File(application, 'demo.aot'))
        task.metadataFile.set(new File(application, 'aot-cache.properties'))
        task.javaExecutable.set(new File(System.getProperty('java.home'), 'bin/java').absolutePath)
        task.javaVersion.set('25.0.1+9')
        task.javaVendor.set('A Vendor')
        task.jvmArguments.set(arguments)
        task.paths.set([])
        task.port.set(18099)
        task.startTimeoutSeconds.set(5)
        task
    }

    void 'a run that never starts serving fails the build'() {
        given: 'an archive that is not one, so the run ends immediately'
            TrainAotCacheTask task = task('not-an-archive.jar', [])

        when:
            task.train()

        then: 'rather than leaving a build to carry on and ship an application with no cache'
            GradleException e = thrown()
            e.message.contains('ended before it started serving')
    }

    void 'what the run printed is where the failure says it is'() {
        given:
            TrainAotCacheTask task = task('not-an-archive.jar', [])

        when:
            task.train()

        then:
            GradleException e = thrown()
            new File(e.message.replaceAll(/(?s).*is in /, '').trim()).isFile()
    }

    void 'a run that starts is exercised and described'() {
        given: 'a run that says it started, serves what it is asked for, and stops when asked'
            TrainAotCacheTask task = servingTask(['/', '/login', '/missing'])

        when:
            task.train()

        then: 'the cache the run left behind is described by what it was made from'
            Properties described = describedBy(task)
            described.'application.archive' == 'served.jar'
            described.'application.sha256' == sha256Of(new File(task.applicationDirectory.get().asFile, 'served.jar'))
            described.'training.paths' == '/ /login /missing'
            described.'training.arguments' == '-Dspring.aot.enabled=true'
            described.'java.runtime.version' == '25.0.1+9'
            described.'java.vendor' == 'A Vendor'

        and: 'every path was asked for, including the one that answered with an error'
            requested == ['/', '/login', '/missing']
    }

    void 'a path that is not a URI is skipped rather than failing the build'() {
        given: 'a leading slash left off, which makes a URI with no valid authority'
            TrainAotCacheTask task = servingTask(['login', '/'])

        when:
            task.train()

        then: 'a typo in a list that exists to make the next start quicker does not fail a build'
            noExceptionThrown()

        and: 'the paths that are URIs were still asked for'
            requested == ['/']
    }

    void 'a run that says nothing is found by its port'() {
        given: 'an application that has turned off the log line Spring Boot starts with'
            TrainAotCacheTask task = servingTask(['/'], false)

        when:
            task.train()

        then: 'a message an application may reword or silence is not the only way to know it is up'
            noExceptionThrown()
            requested == ['/']
    }

    void 'a run is refused where it could not be asked to stop'() {
        given: 'Windows, where a child process can only be killed, and a killed run writes no cache'
            String os = System.getProperty('os.name')
            System.setProperty('os.name', 'Windows 11')
            TrainAotCacheTask task = task('not-an-archive.jar', [])

        when:
            task.train()

        then: 'said before the run rather than after it, as "no cache was written"'
            GradleException e = thrown()
            e.message.contains('can only be killed')

        cleanup:
            System.setProperty('os.name', os)
    }
}

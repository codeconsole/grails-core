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

    void 'the arguments the run is trained with are the ones it is given'() {
        given:
            TrainAotCacheTask task = task('not-an-archive.jar', ['-Dspring.aot.enabled=true'])

        expect: 'a run configured differently from the training run reads a cache of another application'
            task.jvmArguments.get() == ['-Dspring.aot.enabled=true']
    }
}

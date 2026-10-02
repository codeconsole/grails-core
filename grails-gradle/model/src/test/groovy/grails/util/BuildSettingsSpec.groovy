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
package grails.util

import spock.lang.Specification
import spock.lang.TempDir

class BuildSettingsSpec extends Specification {

    @TempDir
    File tmp

    void 'a resources directory is #description'() {
        given:
        File baseDir = new File('/app')
        File targetDir = new File(baseDir, 'build')

        expect:
        BuildSettings.resourcesDir(fromSystem, baseDir, targetDir) == expected

        where:
        description                                                 | fromSystem                                     | expected
        'joined to the application when relative'                   | 'build-parent/build-8070/resources/main'       | new File('/app/build-parent/build-8070/resources/main')
        'kept as it is when absolute'                               | new File('/elsewhere/res').absolutePath        | new File('/elsewhere/res').absoluteFile
        'the target directory\'s resources/main without a property'  | null                                           | new File('/app/build/resources/main')
    }

    void 'a relative resources directory is the application\'s, whatever the working directory: #fromSystem'() {
        given: 'an application, and a working directory next to it, as a build that sets workingDir has'
        File app = new File(tmp, 'app')
        new File(app, 'grails-app').mkdirs()
        new File(app, 'build-parent/build-8070/resources/main').mkdirs()
        new File(app, 'build/resources/main').mkdirs()
        File elsewhere = new File(tmp, 'elsewhere')
        elsewhere.mkdirs()

        when:
        Map<String, String> printed = printBuildSettings(elsewhere, "-Dbase.dir=${app.absolutePath}", fromSystem)

        then:
        printed.RESOURCES_DIR == new File(app, expected).canonicalPath

        where:
        fromSystem                                                                   | expected
        '-Dgrails.project.resource.dir=build-parent/build-8070/resources/main'       | 'build-parent/build-8070/resources/main'
        null                                                                         | 'build/resources/main'
    }

    private static Map<String, String> printBuildSettings(File workingDir, String... properties) {
        String java = new File(System.getProperty('java.home'), 'bin/java').absolutePath
        List<String> command = [java, '-cp', System.getProperty('java.class.path')]
        command.addAll(properties.findAll())
        command << BuildSettingsPrinter.name
        Process process = new ProcessBuilder(command).directory(workingDir).redirectErrorStream(true).start()
        String output = process.inputStream.text
        assert process.waitFor() == 0: output
        output.readLines().findAll { it.contains('=') }.collectEntries { String line ->
            int split = line.indexOf('=')
            [(line.substring(0, split)): line.substring(split + 1)]
        }
    }
}

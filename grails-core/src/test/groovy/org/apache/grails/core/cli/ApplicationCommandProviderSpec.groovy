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
package org.apache.grails.core.cli

import java.nio.file.Path

import spock.lang.Specification
import spock.lang.TempDir

class ApplicationCommandProviderSpec extends Specification {

    @TempDir
    Path tempDir

    private ClassLoader originalContextClassLoader
    private URLClassLoader factoryClassLoader

    def cleanup() {
        Thread.currentThread().contextClassLoader = originalContextClassLoader
        factoryClassLoader?.close()
    }

    def "continues loading providers when one provider constructor fails"() {
        given:
        File metaInf = new File(tempDir.toFile(), 'META-INF')
        assert metaInf.mkdirs()
        new File(metaInf, 'grails-cli.factories').text = "${ApplicationCommandProvider.name}=${ThrowingCommandProvider.name},${TestCommandProvider.name}"
        originalContextClassLoader = Thread.currentThread().contextClassLoader
        factoryClassLoader = new URLClassLoader([tempDir.toUri().toURL()] as URL[], getClass().classLoader)
        Thread.currentThread().contextClassLoader = factoryClassLoader

        when:
        ApplicationCommand command = new ApplicationContextCommandRegistry().findCommand('provider-test')

        then:
        command instanceof ProviderTestCommand
    }
}

class ThrowingCommandProvider implements ApplicationCommandProvider {

    ThrowingCommandProvider() {
        throw new RuntimeException('boom')
    }

    @Override
    void contributeCommands(ClassLoader registryClassLoader, ClassLoader contextClassLoader, ApplicationCommandRegistrar registrar) {
    }
}

class TestCommandProvider implements ApplicationCommandProvider {

    @Override
    void contributeCommands(ClassLoader registryClassLoader, ClassLoader contextClassLoader, ApplicationCommandRegistrar registrar) {
        registrar.register(new ProviderTestCommand())
    }
}

class ProviderTestCommand implements ApplicationCommand {

    @Override
    String getName() {
        'provider-test'
    }

    @Override
    boolean handle(ExecutionContext executionContext) {
        true
    }
}

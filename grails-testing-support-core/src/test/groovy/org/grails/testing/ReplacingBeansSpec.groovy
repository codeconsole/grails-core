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
package org.grails.testing

import org.springframework.beans.factory.BeanRegistrar
import org.springframework.beans.factory.BeanRegistry
import org.springframework.context.support.StaticMessageSource
import org.springframework.core.env.Environment

import spock.lang.Specification
import testing.included.RegisteredGreeting

class TestMessageSource extends StaticMessageSource {
}

class ReplacingHarnessDefaultsSpec extends Specification implements GrailsUnitTest {

    def beans = {
        bean('messageSource', TestMessageSource)
    }

    void "a beans block replaces a bean the harness provides, as an application's replaces a framework default"() {
        expect:
        applicationContext.getBean('messageSource') instanceof TestMessageSource
    }
}

class IncludedPluginBeanOverBeansBlockSpec extends Specification implements GrailsUnitTest {

    Set<String> getIncludePlugins() {
        GrailsApplicationBuilder.DEFAULT_INCLUDED_PLUGINS + ['includedBeans'] as Set<String>
    }

    def beans = {
        bean('registeredGreeting', RegisteredGreeting) {
            new RegisteredGreeting(text: 'from the test')
        }
    }

    void "an included plugin's bean wins over a beans block bean of the same name, as it does over an application's at boot"() {
        expect:
        applicationContext.getBean('registeredGreeting', RegisteredGreeting).text == 'from the plugin'
    }
}

class BeanRegistrarOverIncludedPluginBeanSpec extends Specification implements GrailsUnitTest {

    Set<String> getIncludePlugins() {
        GrailsApplicationBuilder.DEFAULT_INCLUDED_PLUGINS + ['includedBeans'] as Set<String>
    }

    BeanRegistrar beanRegistrar() {
        return { BeanRegistry registry, Environment environment ->
            registry.registerBean('registeredGreeting', RegisteredGreeting) { BeanRegistry.Spec<RegisteredGreeting> spec ->
                spec.supplier { new RegisteredGreeting(text: 'from the test') }
            }
        } as BeanRegistrar
    }

    void "the test's beanRegistrar() replaces an included plugin's bean"() {
        expect:
        applicationContext.getBean('registeredGreeting', RegisteredGreeting).text == 'from the test'
    }
}

class DoWithSpringOverIncludedPluginBeanSpec extends Specification implements GrailsUnitTest {

    Set<String> getIncludePlugins() {
        GrailsApplicationBuilder.DEFAULT_INCLUDED_PLUGINS + ['includedBeans'] as Set<String>
    }

    Closure doWithSpring() {
        { ->
            registeredGreeting(RegisteredGreeting) {
                text = 'from the test'
            }
        }
    }

    void "the test's doWithSpring() replaces an included plugin's bean, as an application's does"() {
        expect:
        applicationContext.getBean('registeredGreeting', RegisteredGreeting).text == 'from the test'
    }
}

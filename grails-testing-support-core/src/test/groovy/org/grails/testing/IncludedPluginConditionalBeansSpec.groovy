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

import spock.lang.Specification
import testing.included.RegisteredGreeting

/**
 * Where a unit test differs from an application. The test registers an included plugin's
 * {@code doWithSpring} and {@code beanRegistrar} beans after its configuration classes are read,
 * where an application's early phase registers them first, so a {@code @ConditionalOnMissingBean}
 * bean does not back off from them.
 */
class IncludedPluginConditionalBeansSpec extends Specification implements GrailsUnitTest {

    Set<String> getIncludePlugins() {
        GrailsApplicationBuilder.DEFAULT_INCLUDED_PLUGINS + ['includedBeans'] as Set<String>
    }

    void "a plugin's conditional bean does not back off from the plugin's own registered bean, as it would in an application"() {
        expect: 'an application would have registeredGreeting alone'
        applicationContext.getBeansOfType(RegisteredGreeting).keySet() == ['registeredGreeting', 'fallbackGreeting'] as Set
    }
}

class TestConditionalBeanOverIncludedPluginSpec extends Specification implements GrailsUnitTest {

    Set<String> getIncludePlugins() {
        GrailsApplicationBuilder.DEFAULT_INCLUDED_PLUGINS + ['includedBeans'] as Set<String>
    }

    def beans = {
        bean('testGreeting', RegisteredGreeting).conditionalOnMissingBean() {
            new RegisteredGreeting(text: 'from the test')
        }
    }

    void "a test's conditional bean does not back off from an included plugin's registered bean, as it would in an application"() {
        expect: "an application would have registeredGreeting alone; the plugin's fallback backs off from the test's bean, as there"
        applicationContext.getBeansOfType(RegisteredGreeting).keySet() == ['registeredGreeting', 'testGreeting'] as Set
    }
}

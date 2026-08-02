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
package grails.web.databinding

import grails.config.Config
import grails.core.GrailsApplication
import grails.util.Holders
import org.grails.config.NavigableMap
import org.grails.config.PropertySourcesConfig
import org.grails.web.databinding.DefaultASTDatabindingHelper
import spock.lang.Specification
import spock.lang.Unroll

class LegacyBindableDefaultConfigSpec extends Specification {

    private Config originalConfig
    private GrailsApplication originalApplication

    void setup() {
        originalConfig = Holders.config
        originalApplication = Holders.findApplication()
        Holders.grailsApplication = null
        Holders.setConfig(null)
    }

    void cleanup() {
        Holders.setConfig(originalConfig)
        Holders.grailsApplication = originalApplication
    }

    void 'an absent legacy bindable default in a PropertySourcesConfig remains permissive'() {
        given:
        Holders.setConfig(new PropertySourcesConfig([:]))

        when:
        Object value = Holders.flatConfig.get(DefaultASTDatabindingHelper.LEGACY_BINDABLE_DEFAULT)
        boolean legacyDefaultEnabled = DataBindingUtils.isLegacyBindableDefaultEnabled()

        then:
        !Holders.flatConfig.containsKey(DefaultASTDatabindingHelper.LEGACY_BINDABLE_DEFAULT)
        value instanceof NavigableMap.NullSafeNavigator
        legacyDefaultEnabled
    }

    @Unroll
    void 'an explicit #configuredValue legacy bindable default enables secure mode'() {
        given:
        Holders.setConfig(new PropertySourcesConfig([(DefaultASTDatabindingHelper.LEGACY_BINDABLE_DEFAULT): configuredValue]))

        when:
        boolean legacyDefaultEnabled = DataBindingUtils.isLegacyBindableDefaultEnabled()

        then:
        !legacyDefaultEnabled

        where:
        configuredValue << [false, 'false']
    }

    @Unroll
    void 'an explicit #configuredValue legacy bindable default remains permissive'() {
        given:
        Holders.setConfig(new PropertySourcesConfig([(DefaultASTDatabindingHelper.LEGACY_BINDABLE_DEFAULT): configuredValue]))

        when:
        boolean legacyDefaultEnabled = DataBindingUtils.isLegacyBindableDefaultEnabled()

        then:
        legacyDefaultEnabled

        where:
        configuredValue << [true, 'true']
    }

    void 'an unset Holders config remains permissive'() {
        given:
        Holders.setConfig(null)

        when:
        boolean legacyDefaultEnabled = DataBindingUtils.isLegacyBindableDefaultEnabled()

        then:
        legacyDefaultEnabled
    }

    @Unroll
    void 'an unrecognised #configuredValue legacy bindable default fails closed'() {
        given:
        Holders.setConfig(new PropertySourcesConfig([(DefaultASTDatabindingHelper.LEGACY_BINDABLE_DEFAULT): configuredValue]))

        when:
        boolean legacyDefaultEnabled = DataBindingUtils.isLegacyBindableDefaultEnabled()

        then:
        !legacyDefaultEnabled

        where:
        configuredValue << ['flase', '', 'off', 'no', 0, 1]
    }

    @Unroll
    void 'an application-supplied #configuredValue legacy bindable default enables secure mode'() {
        given:
        applicationWithLegacyBindableDefault(configuredValue)

        when:
        boolean legacyDefaultEnabled = DataBindingUtils.isLegacyBindableDefaultEnabled()

        then:
        !legacyDefaultEnabled

        where:
        configuredValue << [false, 'false', 'FALSE', ' false ']
    }

    @Unroll
    void 'an application-supplied #configuredValue legacy bindable default remains permissive'() {
        given:
        applicationWithLegacyBindableDefault(configuredValue)

        when:
        boolean legacyDefaultEnabled = DataBindingUtils.isLegacyBindableDefaultEnabled()

        then:
        legacyDefaultEnabled

        where:
        configuredValue << [true, 'true']
    }

    void 'an application without the legacy bindable default remains permissive'() {
        given:
        applicationWithConfig(new PropertySourcesConfig([:]))

        when:
        boolean legacyDefaultEnabled = DataBindingUtils.isLegacyBindableDefaultEnabled()

        then:
        legacyDefaultEnabled
    }

    private void applicationWithConfig(Config config) {
        GrailsApplication application = Stub(GrailsApplication) {
            getConfig() >> config
        }
        Holders.grailsApplication = application
    }

    private void applicationWithLegacyBindableDefault(Object configuredValue) {
        applicationWithConfig(new PropertySourcesConfig([(DefaultASTDatabindingHelper.LEGACY_BINDABLE_DEFAULT): configuredValue]))
    }
}

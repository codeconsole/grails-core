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
package org.grails.web.servlet

import grails.artefact.Artefact
import grails.databinding.SimpleMapDataBindingSource
import grails.persistence.Entity
import grails.testing.web.controllers.ControllerUnitTest
import grails.web.databinding.DataBindingUtils
import org.grails.web.databinding.DefaultASTDatabindingHelper
import spock.lang.Specification

/**
 * Tests for the bindData method
 *
 */
class BindDataMethodTests extends Specification implements ControllerUnitTest<BindingController> {

    void 'Test bindData with Map'() {
        when:
        def model = controller.bindWithMap()
        def target = model.target

        then:
        target.name == 'Marc Palmer'
    }

    void 'Test bindData With Excludes'() {
        when:
        def model = controller.bindWithExcludes()
        def target = model.target

        then:
        target.name == 'Marc Palmer'
        target.email == null
    }

    void 'Test bindData With Includes'() {
        when:
        def model = controller.bindWithIncludes()
        def target = model.target

        then:
        target.name == 'Marc Palmer'
        target.email == null
    }

    void 'Test bindData With Empty Includes/Excludes Map Uses Legacy Default Allowlist'() {
        when:
        def model = controller.bindWithEmptyIncludesExcludesMap()
        def target = model.target

        then:
        target.name == 'Marc Palmer'
        target.email == 'dowantthis'
    }

    void 'Test bindData Overriding Included With Excluded'() {
        when:
        def model = controller.bindWithIncludeOverriddenByExclude()
        def target = model.target

        then:
        target.name == 'Marc Palmer'
        target.email == null
    }

    void 'Test bindData With Prefix Filter'() {
        when:
        def model = controller.bindWithPrefixFilter()
        def target = model.target

        then:
        target.name == 'Lee Butts'
        target.email == 'lee@mail.com'
    }

    void 'Test bindData With Disallowed And GrailsParameterMap'() {
        when:
        params.name = 'Marc Palmer'
        params.email = 'dontwantthis'
        params.'address.country' = 'gbr'
        def model = controller.bindWithParamsAndDisallowed()
        def target = model.target

        then:
        target.name == 'Marc Palmer'
        target.address.country == 'gbr'
        target.email == null
    }

    void 'Test bindData With Prefix Filter And Disallowed'() {
        when:
        def model = controller.bindWithPrefixFilterAndDisallowed()
        def target = model.target

        then:
        target.name == 'Lee Butts'
        target.email == null
    }

    void 'Test bindData Converts Single String In Map To List'() {
        when:
        def model = controller.bindWithStringConvertedToList()
        def target = model.target

        then:
        target.name == 'Lee Butts'
        target.email == null
    }

    void 'Test bindData uses legacy allowlist by default and preserves bindable false'() {
        when:
        def model = controller.bindWithDefaultAllowlist()
        def target = model.target

        then:
        target.displayName == 'Grace Hopper'
        target.username == 'ghopper'
        target.admin
        target.role == null
    }

    void 'Test bindData uses secure allowlist when deny by default is enabled'() {
        given:
        grailsApplication.config.setAt(DefaultASTDatabindingHelper.DENY_BY_DEFAULT, true)

        when:
        def model = controller.bindWithDefaultAllowlist()
        def target = model.target

        then:
        target.displayName == 'Grace Hopper'
        target.username == null
        !target.admin
        target.role == null

        cleanup:
        grailsApplication.config.setAt(DefaultASTDatabindingHelper.DENY_BY_DEFAULT, false)
    }

    void 'Test direct web data binder preserves bindable false without generated allowlist'() {
        given:
        def binder = grailsApplication.mainContext.getBean(DataBindingUtils.DATA_BINDER_BEAN_NAME)
        def target = new RuntimeConstrainedCommandObject()

        when:
        binder.bind(target, new SimpleMapDataBindingSource([username: 'ghopper', role: 'admin']))

        then:
        target.username == 'ghopper'
        target.role == null
    }

    void 'Test bindData secure mode preserves nested runtime bindable true without generated allowlist'() {
        given:
        grailsApplication.config.setAt(DefaultASTDatabindingHelper.DENY_BY_DEFAULT, true)
        params.username = 'ghopper'
        params.'address.country' = 'USA'

        when:
        def model = controller.bindRuntimeConstrainedWithParams()
        def target = model.target

        then:
        target.username == null
        target.address.country == 'USA'

        cleanup:
        grailsApplication.config.setAt(DefaultASTDatabindingHelper.DENY_BY_DEFAULT, false)
    }

    void 'Test bindData secure mode uses inherited generated allowlist for proxy subclass'() {
        given:
        grailsApplication.config.setAt(DefaultASTDatabindingHelper.DENY_BY_DEFAULT, true)
        params.parentDisplayName = 'Parent'
        params.childDisplayName = 'Child'

        when:
        def model = controller.bindRuntimeConstrainedSubclassWithParams()
        def target = model.target

        then:
        target.parentDisplayName == 'Parent'
        target.childDisplayName == null

        cleanup:
        grailsApplication.config.setAt(DefaultASTDatabindingHelper.DENY_BY_DEFAULT, false)
    }

    void 'Test bindData with empty include list binds no properties'() {
        when:
        def model = controller.bindWithEmptyIncludeList()
        def target = model.target

        then:
        target.name == null
        target.email == null
    }

    void 'Test direct DataBindingUtils binding with empty include list binds no properties'() {
        given:
        def target = new CommandObject()

        when:
        DataBindingUtils.bindObjectToInstance(target, [name: 'Marc Palmer', email: 'dontwantthis'], [], [], null)

        then:
        target.name == null
        target.email == null
    }
}

@Artefact('Controller')
class BindingController {

    def bindWithMap() {
        def target = new CommandObject()
        bindData target, [ name : 'Marc Palmer' ], [include: ['name']]
        [target: target]
    }

    def bindWithExcludes() {
        def target = new CommandObject()
        bindData target, [name: 'Marc Palmer', email: 'dontwantthis'], [include: ['name', 'email'], exclude: ['email']]
        [target: target]
    }

    def bindWithIncludes() {
        def target = new CommandObject()
        bindData target, [ name : 'Marc Palmer', email : 'dontwantthis' ], [include:['name']]
        [target: target]
    }

    def bindWithEmptyIncludesExcludesMap() {
        def target = new CommandObject()
        bindData target, [ name : 'Marc Palmer', email : 'dowantthis' ], [:]
        [target: target]
    }

    def bindWithIncludeOverriddenByExclude() {
        def target = new CommandObject()
        bindData target, [ name : 'Marc Palmer', email : 'dontwantthis' ], [include: ['name', 'email'], exclude: ['email']]
        [target: target]
    }

    def bindWithPrefixFilter() {
        def target = new CommandObject()
        def filter = "lee"
        bindData target, [ 'mark.name' : 'Marc Palmer', 'mark.email' : 'dontwantthis', 'lee.name': 'Lee Butts', 'lee.email': 'lee@mail.com'], [include: ['name', 'email']], filter
        [target: target]
    }

    def bindWithParamsAndDisallowed() {
        def target = new CommandObject()
        bindData target, params, [include: ['name', 'address.*'], exclude:['email']]
        [target: target]
    }

    def bindWithPrefixFilterAndDisallowed() {
        def target = new CommandObject()
        def filter = "lee"
        def disallowed = [include: ['name', 'email'], exclude:["email"]]
        bindData target, [ 'mark.name' : 'Marc Palmer', 'mark.email' : 'dontwantthis', 'lee.name': 'Lee Butts', 'lee.email': 'lee@mail.com'], disallowed, filter
        [target: target]
    }

    def bindWithStringConvertedToList() {
        def target = new CommandObject()
        def filter = "lee"
        def disallowed = [include: ['name', 'email'], exclude:"email"]
        bindData target, [ 'mark.name' : 'Marc Palmer', 'mark.email' : 'dontwantthis', 'lee.name': 'Lee Butts', 'lee.email': 'lee@mail.com'], disallowed, filter
        [target: target]
    }

    def bindWithDefaultAllowlist() {
        def target = new SecureCommandObject()
        bindData target, [username: 'ghopper', displayName: 'Grace Hopper', admin: true, role: 'admin']
        [target: target]
    }

    def bindWithEmptyIncludeList() {
        def target = new CommandObject()
        bindData target, [name: 'Marc Palmer', email: 'dontwantthis'], [include: []]
        [target: target]
    }

    def bindRuntimeConstrainedWithParams() {
        def target = new RuntimeConstrainedCommandObject()
        bindData target, params
        [target: target]
    }

    def bindRuntimeConstrainedSubclassWithParams() {
        def target = new ChildRuntimeConstrainedCommandObject()
        bindData target, params
        [target: target]
    }

}

class CommandObject {
    String name
    String email
    Address address = new Address()

    static constraints = {
        name bindable: true
        email bindable: true
        address bindable: true
    }
}

class Address {
    String country
}

@Entity
class SecureCommandObject {
    String username
    String displayName
    boolean admin
    String role

    static constraints = {
        displayName bindable: true
        role bindable: false
    }
}

class RuntimeConstrainedCommandObject {
    String username
    String role
    Address address = new Address()

    static constraints = {
        address bindable: true
        role bindable: false
    }
}

class ParentRuntimeConstrainedCommandObject {
    public static final List $defaultDatabindingWhiteList = ['parentDisplayName']
    public static final List $legacyDatabindingWhiteList = ['parentDisplayName']

    String parentDisplayName
}

class ChildRuntimeConstrainedCommandObject extends ParentRuntimeConstrainedCommandObject {
    String childDisplayName

    static constraints = {
        childDisplayName bindable: true
    }
}

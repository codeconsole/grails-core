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
import grails.testing.web.controllers.ControllerUnitTest
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

    void 'Test bindData With Empty Includes/Excludes Map'() {
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

    void 'Test bindData With Null Missing Clears Omitted Included Field'() {
        when:
        def model = controller.bindWithNullMissing()
        def target = model.target

        then:
        target.name == 'Updated Name'
        target.email == null
    }

    void 'Test bindData Without Null Missing Leaves Omitted Included Field'() {
        when:
        def model = controller.bindWithoutNullMissing()
        def target = model.target

        then:
        target.name == 'Updated Name'
        target.email == 'existing@example.com'
    }

    void 'Test bindData With Null Missing Without Include Leaves Omitted Field'() {
        when:
        def model = controller.bindWithNullMissingAndNoInclude()
        def target = model.target

        then:
        target.name == 'Updated Name'
        target.email == 'existing@example.com'
    }

    void 'Test bindData With Null Missing Leaves Excluded Omitted Field'() {
        when:
        def model = controller.bindWithNullMissingAndExclude()
        def target = model.target

        then:
        target.name == 'Updated Name'
        target.email == 'existing@example.com'
    }

    void 'Test bindData With Null Missing Leaves Root Excluded Nested Field'() {
        when:
        def model = controller.bindWithNullMissingAndRootExclude()
        def target = model.target

        then:
        target.address.country == 'Existing Country'
    }

    void 'Test bindData With Null Missing Accepts Blank Included Field'() {
        when:
        def model = controller.bindWithNullMissingAndBlankValue()
        def target = model.target

        then:
        target.name == 'Updated Name'
        target.email == null
    }

    void 'Test bindData With Null Missing Handles Nested Indexed Paths'() {
        when:
        def model = controller.bindWithNullMissingAndIndexedPath()
        def target = model.target

        then:
        target.children[0].name == null
        target.children[1].name == 'Second Child'
    }

    void 'Test bindData With Null Missing Handles Prefixed Nested Indexed Paths'() {
        when:
        def model = controller.bindWithNullMissingAndPrefixedIndexedPath()
        def target = model.target

        then:
        target.children[0].name == null
        target.children[1].name == 'Second Child'
    }

    void 'Test bindData With Null Missing Handles Map Indexed Paths'() {
        when:
        def model = controller.bindWithNullMissingAndMapPath()
        def target = model.target

        then:
        target.contacts.home.value == null
    }

    void 'Test bindData With Null Missing Honors Bindable False'() {
        when:
        def model = controller.bindWithNullMissingAndBindableFalse()
        def target = model.target

        then:
        target.visible == 'updated'
        target.protectedValue == 'keep-me'
    }

    void 'Test bindData With Null Missing Honors Nested Bindable False'() {
        when:
        def model = controller.bindWithNullMissingAndNestedBindableFalse()
        def target = model.target

        then:
        target.protectedAddress.country == null
        target.protectedAddress.secret == 'keep-me'
    }

    void 'Test bindData With Null Missing Honors Collection Element Bindable False'() {
        when:
        def model = controller.bindWithNullMissingAndCollectionElementBindableFalse()
        def target = model.target

        then:
        target.protectedChildren[0].name == 'Existing Child'
        target.protectedChildren[0].secret == 'keep-me'
    }
}

@Artefact('Controller')
class BindingController {

    def bindWithMap() {
        def target = new CommandObject()
        bindData target, [ name : 'Marc Palmer' ]
        [target: target]
    }

    def bindWithExcludes() {
        def target = new CommandObject()
        bindData target, [name: 'Marc Palmer', email: 'dontwantthis'], [exclude: ['email']]
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
        bindData target, [ 'mark.name' : 'Marc Palmer', 'mark.email' : 'dontwantthis', 'lee.name': 'Lee Butts', 'lee.email': 'lee@mail.com'], filter
        [target: target]
    }

    def bindWithParamsAndDisallowed() {
        def target = new CommandObject()
        bindData target, params, [exclude:['email']]
        [target: target]
    }

    def bindWithPrefixFilterAndDisallowed() {
        def target = new CommandObject()
        def filter = "lee"
        def disallowed = [exclude:["email"]]
        bindData target, [ 'mark.name' : 'Marc Palmer', 'mark.email' : 'dontwantthis', 'lee.name': 'Lee Butts', 'lee.email': 'lee@mail.com'], disallowed, filter
        [target: target]
    }

    def bindWithStringConvertedToList() {
        def target = new CommandObject()
        def filter = "lee"
        def disallowed = [exclude:"email"]
        bindData target, [ 'mark.name' : 'Marc Palmer', 'mark.email' : 'dontwantthis', 'lee.name': 'Lee Butts', 'lee.email': 'lee@mail.com'], disallowed, filter
        [target: target]
    }

    def bindWithNullMissing() {
        def target = new CommandObject(name: 'Existing Name', email: 'existing@example.com')
        bindData target, [name: 'Updated Name'], [include: ['name', 'email'], nullMissing: true]
        [target: target]
    }

    def bindWithoutNullMissing() {
        def target = new CommandObject(name: 'Existing Name', email: 'existing@example.com')
        bindData target, [name: 'Updated Name'], [include: ['name', 'email']]
        [target: target]
    }

    def bindWithNullMissingAndNoInclude() {
        def target = new CommandObject(name: 'Existing Name', email: 'existing@example.com')
        bindData target, [name: 'Updated Name'], [nullMissing: true]
        [target: target]
    }

    def bindWithNullMissingAndExclude() {
        def target = new CommandObject(name: 'Existing Name', email: 'existing@example.com')
        bindData target, [name: 'Updated Name'], [include: ['name', 'email'], exclude: ['email'], nullMissing: true]
        [target: target]
    }

    def bindWithNullMissingAndRootExclude() {
        def target = new CommandObject(address: new Address(country: 'Existing Country'))
        bindData target, [name: 'Updated Name'], [include: ['address.country'], exclude: ['address'], nullMissing: true]
        [target: target]
    }

    def bindWithNullMissingAndBlankValue() {
        def target = new CommandObject(name: 'Existing Name', email: 'existing@example.com')
        bindData target, [name: 'Updated Name', email: ''], [include: ['name', 'email'], nullMissing: true]
        [target: target]
    }

    def bindWithNullMissingAndIndexedPath() {
        def target = new CommandObject(children: [new Child(name: 'First Child'), new Child(name: 'Second Child')])
        bindData target, ['children[0].age': '7'], [include: ['children.name'], nullMissing: true]
        [target: target]
    }

    def bindWithNullMissingAndPrefixedIndexedPath() {
        def target = new CommandObject(children: [new Child(name: 'First Child'), new Child(name: 'Second Child')])
        bindData target, ['lee.children[0].age': '7'], [include: ['children.name'], nullMissing: true], 'lee'
        [target: target]
    }

    def bindWithNullMissingAndMapPath() {
        def target = new CommandObject(contacts: [home: new Contact(value: '555-1234')])
        bindData target, ['contacts[home].type': 'phone'], [include: ['contacts[home].value'], nullMissing: true]
        [target: target]
    }

    def bindWithNullMissingAndBindableFalse() {
        def target = new ProtectedCommandObject(visible: 'old', protectedValue: 'keep-me')
        bindData target, [visible: 'updated'], [include: ['visible', 'protectedValue'], nullMissing: true]
        [target: target]
    }

    def bindWithNullMissingAndNestedBindableFalse() {
        def target = new CommandObject(protectedAddress: new ProtectedAddress(country: 'Existing Country', secret: 'keep-me'))
        bindData target, [name: 'Updated Name'], [include: ['protectedAddress.country', 'protectedAddress.secret'], nullMissing: true]
        [target: target]
    }

    def bindWithNullMissingAndCollectionElementBindableFalse() {
        def target = new CommandObject(protectedChildren: [new ProtectedChild(name: 'Existing Child', secret: 'keep-me')])
        bindData target, ['protectedChildren[0].name': 'Updated Child'], [include: ['protectedChildren.name', 'protectedChildren.secret'], nullMissing: true]
        [target: target]
    }
}

class CommandObject {
    String name
    String email
    Address address = new Address()
    List<Child> children = []
    Map<String, Contact> contacts = [:]
    ProtectedAddress protectedAddress = new ProtectedAddress()
    List<ProtectedChild> protectedChildren = []
}

class Address {
    String country
}

class Child {
    String name
    Integer age
}

class Contact {
    String type
    String value
}

class ProtectedCommandObject {
    public static final List<String> $defaultDatabindingWhiteList = ['visible']

    String visible
    String protectedValue
}

class ProtectedAddress {
    public static final List<String> $defaultDatabindingWhiteList = ['country']

    String country
    String secret
}

class ProtectedChild {
    public static final List<String> $defaultDatabindingWhiteList = ['name']

    String name
    String secret
}

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
import grails.core.GrailsApplication
import grails.databinding.SimpleMapDataBindingSource
import grails.databinding.events.DataBindingListenerAdapter
import grails.persistence.Entity
import grails.testing.web.controllers.ControllerUnitTest
import grails.validation.Validateable
import grails.web.databinding.DataBindingUtils
import grails.web.databinding.GrailsWebDataBinder
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

    void 'Test bindData With Empty Includes/Excludes Map Uses Secure Default Allowlist'() {
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

    void 'Test bindData uses secure allowlist by default and preserves bindable false'() {
        when:
        def model = controller.bindWithDefaultAllowlist()
        def target = model.target

        then:
        target.displayName == 'Grace Hopper'
        target.username == null
        !target.admin
        target.role == null
    }

    void 'Test an existing bindable true allowlist works unchanged without configuration'() {
        given:
        def binder = new RecordingGrailsWebDataBinder(grailsApplication)
        def target = new ExistingAllowlistCommandObject()

        when:
        binder.bind(target, new SimpleMapDataBindingSource([displayName: 'Grace Hopper', admin: true]))

        then:
        target.displayName == 'Grace Hopper'
        !target.admin
    }

    void 'Test deny by default rejects an unlisted property and emits one actionable warning'() {
        given:
        def binder = new RecordingGrailsWebDataBinder(grailsApplication)
        def target = new NoAllowlistCommandObject()

        when:
        binder.bind(target, new SimpleMapDataBindingSource([username: 'ghopper']))
        binder.bind(target, new SimpleMapDataBindingSource([username: 'second-attempt']))

        then:
        target.username == null
        binder.warnings == [
            'Ignored request parameter [username] while binding to [org.grails.web.servlet.NoAllowlistCommandObject]: it is not in the binding allowlist. ' +
                    'Grails 8 binds only allowlisted properties by default to prevent mass assignment (CWE-915). ' +
                    'To bind [username], declare it bindable - `static constraints = { username bindable: true }` on the class, ' +
                    'add it to the binding `include:` list, or annotate the controller action parameter with `@BindAllowed([\'username\'])`. ' +
                    'To restore the previous permissive binding for the whole application, set `grails.databinding.legacyBindableDefault=true`.'
        ]
    }

    void 'Test legacy bindable default restores permissive binding for a class with no allowlist'() {
        given:
        grailsApplication.config.setAt(DefaultASTDatabindingHelper.LEGACY_BINDABLE_DEFAULT, true)
        def binder = new RecordingGrailsWebDataBinder(grailsApplication)
        def target = new NoAllowlistCommandObject()

        when:
        binder.bind(target, new SimpleMapDataBindingSource([username: 'ghopper']))

        then:
        target.username == 'ghopper'
        binder.warnings.empty

        cleanup:
        grailsApplication.config.setAt(DefaultASTDatabindingHelper.LEGACY_BINDABLE_DEFAULT, false)
    }

    void 'Test bindData uses legacy allowlist when legacy bindable default is enabled'() {
        given:
        grailsApplication.config.setAt(DefaultASTDatabindingHelper.LEGACY_BINDABLE_DEFAULT, true)

        when:
        def model = controller.bindWithDefaultAllowlist()
        def target = model.target

        then:
        target.displayName == 'Grace Hopper'
        target.username == 'ghopper'
        target.admin
        target.role == null

        cleanup:
        grailsApplication.config.setAt(DefaultASTDatabindingHelper.LEGACY_BINDABLE_DEFAULT, false)
    }

    void 'Test direct web data binder denies unlisted properties without generated allowlist'() {
        given:
        def binder = grailsApplication.mainContext.getBean(DataBindingUtils.DATA_BINDER_BEAN_NAME)
        def target = new RuntimeConstrainedCommandObject()

        when:
        binder.bind(target, new SimpleMapDataBindingSource([username: 'ghopper', role: 'admin']))

        then:
        target.username == null
        target.role == null
    }

    void 'Test bindData secure default applies the nested target allowlist'() {
        given:
        params.username = 'ghopper'
        params.'address.country' = 'USA'
        params.'address.admin' = true

        when:
        def model = controller.bindRuntimeConstrainedWithParams()
        def target = model.target

        then:
        target.username == null
        target.address.country == 'USA'
        !target.address.admin
    }

    void 'Test explicit wildcard include binds all nested properties'() {
        given:
        def target = new RuntimeConstrainedCommandObject()

        when:
        DataBindingUtils.bindObjectToInstance(target,
                [address: [country: 'USA', admin: true]], ['address.*'], [], null)

        then:
        target.address.country == 'USA'
        target.address.admin
    }

    void 'Test generated parent allowlist does not widen to nested target allowlist'() {
        given:
        def target = new RuntimeConstrainedCommandObject()

        when:
        DataBindingUtils.bindObjectToInstance(target, [address: [country: 'USA', admin: true]])

        then:
        target.address.country == 'USA'
        !target.address.admin
    }

    void 'Test generated bare nested property does not bind child allowlisted properties'() {
        given:
        def target = new GeneratedBareAddressCommandObject()

        when:
        DataBindingUtils.bindObjectToInstance(target, [address: [admin: true]])

        then:
        !target.address.admin
    }

    void 'Test bindData secure default augments inherited generated allowlist with runtime bindable properties'() {
        given:
        params.parentDisplayName = 'Parent'
        params.childDisplayName = 'Child'

        when:
        def model = controller.bindRuntimeConstrainedSubclassWithParams()
        def target = model.target

        then:
        target.parentDisplayName == 'Parent'
        target.childDisplayName == 'Child'
    }

    void 'Test indexed binding applies explicit nested allowlist to collection elements'() {
        when:
        def model = controller.bindMembersWithNestedInclude()
        def target = model.target

        then:
        target.members.size() == 1
        target.members[0].name == 'Grace Hopper'
        !target.members[0].admin
    }

    void 'Test indexed array binding applies explicit nested allowlist to every element'() {
        given:
        def target = new TeamCommandObject()

        when:
        DataBindingUtils.bindObjectToInstance(target,
                ['memberArray[0]': [name: 'Grace Hopper', admin: true]], ['memberArray.name'], [], null)

        then:
        target.memberArray*.name == ['Grace Hopper']
        !target.memberArray[0].admin
    }

    void 'Test JSON list array binding applies explicit nested allowlist to every element'() {
        given:
        def target = new TeamCommandObject()

        when:
        DataBindingUtils.bindObjectToInstance(target,
                [memberArray: [[name: 'Grace Hopper', admin: true]]], ['memberArray.name'], [], null)

        then:
        target.memberArray*.name == ['Grace Hopper']
        !target.memberArray[0].admin
    }

    void 'Test JSON list binding applies explicit nested allowlist to collection elements'() {
        given:
        def target = new TeamCommandObject()

        when:
        DataBindingUtils.bindObjectToInstance(target,
                [members: [[name: 'Grace Hopper', admin: true]]], ['members.name'], [], null)

        then:
        target.members.size() == 1
        target.members[0].name == 'Grace Hopper'
        !target.members[0].admin
    }

    void 'Test indexed typed map binding applies explicit nested allowlist to map values'() {
        given:
        def target = new MapTeamCommandObject()

        when:
        DataBindingUtils.bindObjectToInstance(target,
                ['members[zero]': [name: 'Grace Hopper', admin: true]], ['members.name'], [], null)

        then:
        target.members.zero.name == 'Grace Hopper'
        !target.members.zero.admin
    }

    void 'Test nested typed map binding applies explicit nested allowlist to map values'() {
        given:
        def target = new MapTeamCommandObject()

        when:
        DataBindingUtils.bindObjectToInstance(target,
                [members: [zero: [name: 'Grace Hopper', admin: true]]], ['members.name'], [], null)

        then:
        target.members.zero.name == 'Grace Hopper'
        !target.members.zero.admin
    }

    void 'Test generated parent allowlist applies child allowlist to collection elements'() {
        given:
        def indexedTarget = new GeneratedNestedContainerCommandObject()
        def nestedTarget = new GeneratedNestedContainerCommandObject()

        when:
        DataBindingUtils.bindObjectToInstance(indexedTarget,
                ['children[0]': [name: 'Indexed Child', admin: true]])
        DataBindingUtils.bindObjectToInstance(nestedTarget,
                [children: [[name: 'JSON Child', admin: true]]])

        then:
        indexedTarget.children*.name == ['Indexed Child']
        !indexedTarget.children[0].admin
        nestedTarget.children*.name == ['JSON Child']
        !nestedTarget.children[0].admin
    }

    void 'Test generated parent allowlist applies child allowlist to array elements'() {
        given:
        def target = new GeneratedNestedContainerCommandObject()

        when:
        DataBindingUtils.bindObjectToInstance(target,
                ['childArray[0]': [name: 'Array Child', admin: true]])

        then:
        target.childArray*.name == ['Array Child']
        !target.childArray[0].admin
    }

    void 'Test generated parent allowlist applies child allowlist to JSON list array elements'() {
        given:
        def target = new GeneratedNestedContainerCommandObject()

        when:
        DataBindingUtils.bindObjectToInstance(target,
                [childArray: [[name: 'Array Child', admin: true]]])

        then:
        target.childArray*.name == ['Array Child']
        !target.childArray[0].admin
    }

    void 'Test listener fallback preserves the explicit nested allowlist'() {
        given:
        def target = new GeneratedNestedContainerCommandObject()
        def binder = grailsApplication.mainContext.getBean(DataBindingUtils.DATA_BINDER_BEAN_NAME) as GrailsWebDataBinder
        def listener = new DataBindingListenerAdapter() {
            @Override
            Boolean beforeBinding(Object object, String propertyName, Object value, Object errors) {
                propertyName == 'child' ? false : true
            }
        }

        when:
        binder.bind(target, new SimpleMapDataBindingSource([child: [name: 'Listener Child', admin: true]]),
                null, ['child.name'], [], listener)

        then:
        target.child.name == 'Listener Child'
        !target.child.admin
    }

    void 'Test runtime imported bindable true augments the generated allowlist'() {
        given:
        def target = new RuntimeImportedConstraintCommandObject()
        List generatedIncludeList = (List) target.class
                .getField(DefaultASTDatabindingHelper.DEFAULT_DATABINDING_WHITELIST).get(null)

        expect:
        !generatedIncludeList.contains('importedName')

        when:
        DataBindingUtils.bindObjectToInstance(target, [importedName: 'Runtime', admin: true])

        then:
        target.importedName == 'Runtime'
        !target.admin
    }

    void 'Test generated parent allowlist applies child allowlist to typed map values'() {
        given:
        def indexedTarget = new GeneratedNestedContainerCommandObject()
        def nestedTarget = new GeneratedNestedContainerCommandObject()

        when:
        DataBindingUtils.bindObjectToInstance(indexedTarget,
                ['childrenByKey[indexed]': [name: 'Indexed Child', admin: true]])
        DataBindingUtils.bindObjectToInstance(nestedTarget,
                [childrenByKey: [nested: [name: 'Nested Child', admin: true]]])

        then:
        indexedTarget.childrenByKey.indexed.name == 'Indexed Child'
        !indexedTarget.childrenByKey.indexed.admin
        nestedTarget.childrenByKey.nested.name == 'Nested Child'
        !nestedTarget.childrenByKey.nested.admin
    }

    void 'Test legacy bindable default permits all child properties through generated parent allowlist'() {
        given:
        grailsApplication.config.setAt(DefaultASTDatabindingHelper.LEGACY_BINDABLE_DEFAULT, true)
        def target = new GeneratedNestedContainerCommandObject()

        when:
        DataBindingUtils.bindObjectToInstance(target, [
                child: [name: 'One', admin: true],
                children: [[name: 'Two', admin: true]],
                childrenByKey: [three: [name: 'Three', admin: true]]
        ])

        then:
        target.child.name == 'One'
        target.child.admin
        target.children[0].name == 'Two'
        target.children[0].admin
        target.childrenByKey.three.name == 'Three'
        target.childrenByKey.three.admin

        cleanup:
        grailsApplication.config.setAt(DefaultASTDatabindingHelper.LEGACY_BINDABLE_DEFAULT, false)
    }

    void 'Test secure default ignores an old broad default allowlist field'() {
        when:
        def model = controller.bindPrecompiledLegacyTarget()
        def target = model.target

        then:
        target.legacyProperty == null
        target.secureProperty == 'secure'
        target.admin == null
    }

    void 'Test legacy mode falls back to an old default allowlist field'() {
        given:
        grailsApplication.config.setAt(DefaultASTDatabindingHelper.LEGACY_BINDABLE_DEFAULT, true)

        when:
        def model = controller.bindPrecompiledLegacyTarget()
        def target = model.target

        then:
        target.legacyProperty == 'legacy'
        target.secureProperty == null
        target.admin == null

        cleanup:
        grailsApplication.config.setAt(DefaultASTDatabindingHelper.LEGACY_BINDABLE_DEFAULT, false)
    }

    void 'Test secure default ignores an old subclass broad allowlist paired with an inherited legacy allowlist'() {
        given:
        params.name = 'trusted'
        params.admin = true

        when:
        def model = controller.bindMixedGenerationTarget()
        def target = model.target

        then:
        target.name == 'trusted'
        !target.admin
        PrecompiledMixedGenerationCommandObject.declaredFields*.name.contains(
                DefaultASTDatabindingHelper.DEFAULT_DATABINDING_WHITELIST)
        !PrecompiledMixedGenerationCommandObject.declaredFields*.name.contains(
                DefaultASTDatabindingHelper.LEGACY_DATABINDING_WHITELIST)
    }

    void 'Test secure default ignores an old nested subclass broad allowlist paired with an inherited legacy allowlist'() {
        given:
        params.'child.name' = 'trusted nested'
        params.'child.admin' = true

        when:
        def model = controller.bindMixedGenerationNestedTarget()
        def target = model.target

        then:
        target.child.name == 'trusted nested'
        !target.child.admin
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

    void 'Test direct domain binding with empty include list binds no properties'() {
        given:
        def target = new CommandObject()

        when:
        DataBindingUtils.bindObjectToDomainInstance(null, target, [name: 'Marc Palmer', email: 'dontwantthis'], [], [], null)

        then:
        target.name == null
        target.email == null
    }

    void 'Test direct domain binding with null include uses secure default allowlist'() {
        given:
        def target = new SecureCommandObject()

        when:
        DataBindingUtils.bindObjectToDomainInstance(null, target,
                [displayName: 'Grace Hopper', admin: true], null, [], null)

        then:
        target.displayName == 'Grace Hopper'
        !target.admin
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

    def bindMembersWithNestedInclude() {
        def target = new TeamCommandObject()
        bindData target, ['members[0]': [name: 'Grace Hopper', admin: true]], [include: ['members.name']]
        [target: target]
    }

    def bindPrecompiledLegacyTarget() {
        def target = new PrecompiledLegacyCommandObject()
        bindData target, [legacyProperty: 'legacy', secureProperty: 'secure', admin: 'admin']
        [target: target]
    }

    def bindMixedGenerationTarget() {
        def target = new PrecompiledMixedGenerationCommandObject()
        bindData target, params
        [target: target]
    }

    def bindMixedGenerationNestedTarget() {
        def target = new MixedGenerationContainerCommandObject()
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
    boolean admin

    static constraints = {
        country bindable: true
    }
}

class TeamCommandObject {
    List<MemberCommandObject> members = []
    MemberCommandObject[] memberArray = []
}

class GeneratedBareAddressCommandObject {
    public static final List $defaultDatabindingWhiteList = ['address']
    public static final List $legacyDatabindingWhiteList = ['address']

    BindableAdminAddress address = new BindableAdminAddress()
}

class BindableAdminAddress {
    boolean admin

    static constraints = {
        admin bindable: true
    }
}

class MapTeamCommandObject {
    Map<String, MemberCommandObject> members = [:]
}

class MemberCommandObject {
    String name
    boolean admin
}

class GeneratedNestedContainerCommandObject {
    GeneratedNestedChildCommandObject child = new GeneratedNestedChildCommandObject()
    List<GeneratedNestedChildCommandObject> children = []
    GeneratedNestedChildCommandObject[] childArray = []
    Map<String, GeneratedNestedChildCommandObject> childrenByKey = [:]

    static constraints = {
        child bindable: true
        children bindable: true
        childArray bindable: true
        childrenByKey bindable: true
    }
}

class GeneratedNestedChildCommandObject {
    String name
    boolean admin

    static constraints = {
        name bindable: true
    }
}

class PrecompiledLegacyCommandObject {
    public static final List $defaultDatabindingWhiteList = ['legacyProperty']

    String legacyProperty
    String secureProperty
    String admin

    static constraints = {
        secureProperty bindable: true
    }
}

class NewGenerationParentCommandObject {
    public static final List $defaultDatabindingWhiteList = ['name']
    public static final List $legacyDatabindingWhiteList = ['name', 'admin']

    String name
    boolean admin
}

class PrecompiledMixedGenerationCommandObject extends NewGenerationParentCommandObject {
    public static final List $defaultDatabindingWhiteList = ['name', 'admin']
}

class MixedGenerationContainerCommandObject {
    public static final List $defaultDatabindingWhiteList = ['child', 'child_*', 'child.*']
    public static final List $legacyDatabindingWhiteList = ['child', 'child_*', 'child.*']

    PrecompiledMixedGenerationCommandObject child
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

class ImportedBindableConstraintCommandObject implements Validateable {
    String importedName

    static constraints = {
        importedName bindable: true
    }
}

class RuntimeImportedConstraintCommandObject implements Validateable {
    public static final List $defaultDatabindingWhiteList = []
    public static final List $legacyDatabindingWhiteList = []

    String importedName
    boolean admin

    static constraints = {
        importFrom ImportedBindableConstraintCommandObject
    }
}

class ExistingAllowlistCommandObject {
    String displayName
    boolean admin

    static constraints = {
        displayName bindable: true
    }
}

class NoAllowlistCommandObject {
    String username
}

class RecordingGrailsWebDataBinder extends GrailsWebDataBinder {
    final List<String> warnings = []

    RecordingGrailsWebDataBinder(GrailsApplication grailsApplication) {
        super(grailsApplication)
    }

    @Override
    protected boolean isBindingWarningEnabled() {
        true
    }

    @Override
    protected void logBindingWarning(String message) {
        warnings << message
    }
}

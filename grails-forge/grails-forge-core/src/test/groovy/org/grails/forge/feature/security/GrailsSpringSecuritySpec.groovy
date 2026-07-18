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

package org.grails.forge.feature.security

import org.grails.forge.ApplicationContextSpec
import org.grails.forge.application.ApplicationType
import org.grails.forge.feature.Category
import org.grails.forge.fixture.CommandOutputFixture
import org.grails.forge.options.DevelopmentReloading
import org.grails.forge.options.Options
import spock.lang.Unroll

class GrailsSpringSecuritySpec extends ApplicationContextSpec implements CommandOutputFixture {

    void 'the feature secures the app with the plugin, reusing the shared user artifacts'() {
        when:
        def output = generate(ApplicationType.WEB, new Options(DevelopmentReloading.DEVTOOLS), ['grails-spring-security'])

        then: 'the plugin dependency is added, not the plain starter'
        output['build.gradle'].contains('implementation "org.apache.grails:grails-spring-security"')
        !output['build.gradle'].contains('spring-boot-starter-security')

        and: 'the shared user artifacts are generated'
        output['grails-app/domain/example/grails/User.groovy'].contains('class User implements UserDetails')
        output['grails-app/controllers/example/grails/UserController.groovy'].contains('@Scaffold(RestfulServiceController<User>)')
        output['grails-app/services/example/grails/UserService.groovy'].contains('class UserService implements UserDetailsService')
        output['src/test/groovy/example/grails/UserSpec.groovy'].contains('DomainUnitTest<User>')

        and: 'the UserService is aliased to the plugin userDetailsService bean'
        output['grails-app/conf/spring/resources.groovy'].contains("springConfig.addAlias 'userDetailsService', 'userService'")

        and: 'static rules open the public pages and guard the user admin'
        def config = output['grails-app/conf/application.groovy']
        config.contains("grails.plugin.springsecurity.userLookup.userDomainClassName = 'example.grails.User'")
        config.contains("[pattern: '/',               access: ['permitAll']]")
        config.contains("[pattern: '/assets/**',      access: ['permitAll']]")
        config.contains("[pattern: '/user/**',        access: ['ROLE_ADMIN']]")

        and: 'BootStrap seeds the admin, matching the plugin default delegating password encoder'
        output['grails-app/init/example/grails/BootStrap.groovy'].contains('Generated admin credentials: admin')

        and: 'no plain-security artifacts leak in'
        !output.containsKey('src/main/groovy/example/grails/SecurityConfig.groovy')
        !output['grails-app/init/example/grails/Application.groovy'].contains('@Import(SecurityConfig)')
    }

    void 'the feature shares the Spring Security category and advertises the plugin'() {
        given:
        def feature = beanContext.getBean(GrailsSpringSecurity)

        expect:
        feature.name == 'grails-spring-security'
        feature.title == 'Grails Spring Security Plugin'
        feature.category == Category.SPRING_SECURITY
        feature.description.contains('Grails Spring Security Core plugin')
    }

    void 'the two security features are mutually exclusive'() {
        when:
        generate(ApplicationType.WEB, new Options(DevelopmentReloading.DEVTOOLS),
                ['grails-spring-security', 'spring-boot-starter-security'])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.startsWith('There can only be one of the following features selected:')
        e.message.contains('grails-spring-security')
        e.message.contains('spring-boot-starter-security')
    }

    @Unroll
    void 'the feature is not supported for #applicationType'(ApplicationType applicationType) {
        when:
        generate(applicationType, new Options(DevelopmentReloading.DEVTOOLS), ['grails-spring-security'])

        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'The requested feature does not exist: grails-spring-security'

        where:
        applicationType << [ApplicationType.PLUGIN, ApplicationType.REST_API, ApplicationType.WEB_PLUGIN]
    }
}

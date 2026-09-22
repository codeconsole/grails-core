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
package com.example

import grails.boot.GrailsApp
import grails.boot.config.GrailsAutoConfiguration

import groovy.transform.CompileStatic
import org.grails.datastore.gorm.timestamp.AuditorAware
import org.springframework.context.annotation.Bean
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

@CompileStatic
class Application extends GrailsAutoConfiguration {
    static void main(String[] args) {
        GrailsApp.run(Application, args)
    }

    @Bean
    AuditorAware<String> auditorAware() {
        return new SpringSecurityAuditorAware()
    }

    /**
     * Spring Boot's default chain, with the static resources the browser fetches on its own
     * (the favicon, the assets) permitted. Left to the default, a browser's background
     * favicon fetch is bounced through the login page, which starts a session of its own
     * whenever the cookie it carries is stale (a login just changed the session id, a logout
     * just invalidated it) and so overwrites the session the user has just signed in to.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http.authorizeHttpRequests { requests ->
            requests.requestMatchers('/favicon.ico', '/assets/**').permitAll()
                    .anyRequest().authenticated()
        }
        http.formLogin(Customizer.withDefaults())
        http.httpBasic(Customizer.withDefaults())
        return http.build()
    }
}

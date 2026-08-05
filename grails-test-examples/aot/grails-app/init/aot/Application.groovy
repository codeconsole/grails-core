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
package aot

import grails.boot.GrailsApp
import grails.boot.config.GrailsAutoConfiguration

import org.springframework.context.ConfigurableApplicationContext

class Application extends GrailsAutoConfiguration {

    /**
     * With {@code --aot-startup-check} the application starts, asserts the beans an AOT-processed
     * context must still contain, and exits. The build runs it that way against the packaged jar
     * with {@code spring.aot.enabled=true}, so a context that generates cleanly but cannot boot
     * fails the build rather than passing unnoticed.
     */
    static void main(String[] args) {
        if (!args.contains('--aot-startup-check')) {
            GrailsApp.run(Application, args)
            return
        }

        ConfigurableApplicationContext context = (ConfigurableApplicationContext) GrailsApp.run(Application, args)
        try {
            assertBeanPresent(context, 'grailsApplication')
            assertBeanPresent(context, 'filteringCodecsByContentTypeSettings')
            assertBeanPresent(context, 'groovyPagesServlet')
            assertBeanPresent(context, 'jspViewResolver')
            assertBeanPresent(context, 'grailsUrlMappingsHolder')
        }
        finally {
            context.close()
        }
        System.exit(0)
    }

    private static void assertBeanPresent(ConfigurableApplicationContext context, String name) {
        if (!context.containsBean(name)) {
            throw new IllegalStateException("AOT-processed context is missing the '${name}' bean")
        }
    }
}

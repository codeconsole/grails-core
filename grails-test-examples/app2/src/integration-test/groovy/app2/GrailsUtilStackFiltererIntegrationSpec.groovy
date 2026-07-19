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

package app2

import grails.plugin.geb.ContainerGebSpec
import grails.testing.mixin.integration.Integration
import org.springframework.test.context.TestPropertySource

/**
 * Boots the full application with {@code grails.logging.stackTraceFiltererClass} set, then drives a
 * non-resolver code path ({@link grails.util.GrailsUtil#deepSanitize}) to prove the bootstrap wiring in
 * {@code org.apache.grails.core.GrailsBootstrapRegistryInitializer} actually installs the configured
 * filterer in a real running app -- the piece {@code GrailsUtilStackFiltererSpec} and
 * {@code GrailsBootstrapRegistryInitializerSpec} can't prove on their own, since they exercise the
 * classes directly rather than through a real Spring Boot bootstrap.
 *
 * <p>The {@link TestPropertySource} on this class gives it a merged context configuration distinct from
 * {@link ErrorsControllerSpec} and {@link NotFoundHandlerSpec}, so Spring boots a separate application
 * context for it and the other specs in this module are unaffected by the custom filterer class.
 */
@Integration(applicationClass = Application)
@TestPropertySource(properties = ['grails.logging.stackTraceFiltererClass=app2.RecordingStackTraceFilterer'])
class GrailsUtilStackFiltererIntegrationSpec extends ContainerGebSpec {

    void "GrailsUtil.deepSanitize uses the StackTraceFilterer configured via grails.logging.stackTraceFiltererClass"() {
        when: 'a non-resolver code path calls GrailsUtil.deepSanitize directly'
        go('/test/sanitizeViaGrailsUtil')

        then: 'the bootstrap-configured filterer -- not the default -- handled the call'
        pageSource.contains('sanitized-with-configured-filterer')
    }
}

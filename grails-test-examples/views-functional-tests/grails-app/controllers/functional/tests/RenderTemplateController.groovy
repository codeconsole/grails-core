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

package functional.tests

/**
 * Controller whose index action uses {@code render(template:..., model:...)} so that the
 * model is stored in {@code GrailsApplicationAttributes.TEMPLATE_MODEL} rather than in a
 * {@code ModelAndView}.  This exercises the fallback branch of
 * {@link grails.artefact.Interceptor#getModel()} that was not covered by the existing
 * modelAndView / respond / return controller tests.
 */
class RenderTemplateController {

    def index() {
        render(template: 'snippet', model: [title: 'x'])
    }
}

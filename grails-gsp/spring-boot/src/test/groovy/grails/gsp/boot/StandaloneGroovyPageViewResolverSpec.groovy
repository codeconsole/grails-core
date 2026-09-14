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
package grails.gsp.boot

import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.FileSystemResourceLoader
import org.springframework.mock.web.MockServletContext
import org.springframework.web.context.support.GenericWebApplicationContext
import org.springframework.web.servlet.View
import org.springframework.web.servlet.view.AbstractUrlBasedView

import org.grails.gsp.GroovyPagesTemplateEngine

import spock.lang.Specification
import spock.lang.TempDir

/**
 * Which views the GSP view resolver of a Spring Boot application answers for when no template has the name.
 *
 * <p>Spring Boot renders its error page through the view named {@code error}. Answering for that name with a
 * JSP that does not exist forwards the error back to the URL it is being handled at, and the container refuses
 * the loop; the gsp-spring-boot example's {@code ErrorPageTest} covers that end to end.
 */
class StandaloneGroovyPageViewResolverSpec extends Specification {

    @TempDir
    File documentRoot

    private GenericWebApplicationContext context

    void cleanup() {
        context?.close()
    }

    void 'a view with no template resolves to the JSP of its name where that JSP exists'() {
        given: 'a JSP in the servlet context, as src/main/webapp puts one there'
        new File(documentRoot, 'form.jsp').text = '<p>rendered by JSP</p>'

        when:
        View view = resolver().resolveViewName('form.jsp', Locale.ROOT)

        then:
        view instanceof AbstractUrlBasedView
        ((AbstractUrlBasedView) view).url == 'form.jsp'
    }

    void 'a view with neither a template nor a JSP is left to the view resolvers after this one'() {
        expect: 'nothing is answered for the name Boot renders its error page through, so that page is reached'
        resolver().resolveViewName('error', Locale.ROOT) == null
    }

    private StandaloneGroovyPageViewResolver resolver() {
        GspAutoConfiguration.GspTemplateEngineAutoConfiguration configuration =
                new GspAutoConfiguration.GspTemplateEngineAutoConfiguration()
        configuration.templateRoots = ['classpath:/templates'] as String[]

        MockServletContext servletContext =
                new MockServletContext("file:${documentRoot.absolutePath}", new FileSystemResourceLoader())
        context = new GenericWebApplicationContext(servletContext)
        context.refresh()

        new StandaloneGroovyPageViewResolver(new GroovyPagesTemplateEngine(),
                configuration.groovyPageLocator(new DefaultResourceLoader())).tap {
            it.resolveJspView = true
            it.allowGrailsViewCaching = false
            it.applicationContext = context
        }
    }

}

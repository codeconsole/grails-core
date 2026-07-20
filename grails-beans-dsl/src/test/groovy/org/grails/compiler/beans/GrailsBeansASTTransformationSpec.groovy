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
package org.grails.compiler.beans

import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration
import org.springframework.context.annotation.Bean
import spock.lang.Specification
import spock.lang.Unroll

import grails.plugins.Plugin

class GrailsBeansASTTransformationSpec extends Specification {

    private static final String FIXTURE = '''
        import grails.compiler.beans.GrailsBeans
        import org.springframework.boot.autoconfigure.AutoConfiguration

        @GrailsBeans
        @AutoConfiguration
        class FixtureBeans {
            def beans = {
                bean(String, 'greeting') {
                    'hello'
                }

                bean(Integer, 'answer').conditionalOnMissingBean(Integer) {
                    42
                }

                bean(String, 'shout') { String input ->
                    input.toUpperCase()
                }
            }
        }
    '''

    private Class<?> compile() {
        compile(FIXTURE)
    }

    private Class<?> compile(String source) {
        new GroovyClassLoader(getClass().classLoader).parseClass(source)
    }

    def "compiles a plain bean() call into a public @Bean factory method"() {
        given:
        Class<?> fixtureBeans = compile()

        when:
        def method = fixtureBeans.getDeclaredMethod('greeting')

        then:
        method.returnType == String
        method.isAnnotationPresent(Bean)
        method.getAnnotation(Bean).value() == ['greeting'] as String[]

        and: "the closure body became the real method body"
        fixtureBeans.getDeclaredConstructor().newInstance().greeting() == 'hello'
    }

    def "compiles conditionalOnMissingBean(...) into a @ConditionalOnMissingBean annotation"() {
        given:
        Class<?> fixtureBeans = compile()

        when:
        def method = fixtureBeans.getDeclaredMethod('answer')

        then:
        method.returnType == Integer
        method.isAnnotationPresent(Bean)
        method.isAnnotationPresent(ConditionalOnMissingBean)
        method.getAnnotation(ConditionalOnMissingBean).value() as List == [Integer]

        and:
        fixtureBeans.getDeclaredConstructor().newInstance().answer() == 42
    }

    def "closure parameters become method parameters for constructor-style injection"() {
        given:
        Class<?> fixtureBeans = compile()

        when:
        def method = fixtureBeans.getDeclaredMethod('shout', String)

        then:
        method.returnType == String

        and:
        fixtureBeans.getDeclaredConstructor().newInstance().shout('hi') == 'HI'
    }

    def "the beans closure property does not survive compilation"() {
        given:
        Class<?> fixtureBeans = compile()

        expect:
        fixtureBeans.declaredFields*.name == fixtureBeans.declaredFields*.name.findAll { it != 'beans' }
        fixtureBeans.declaredMethods*.name.every { !(it in ['getBeans', 'setBeans']) }
    }

    def "requires a beans property at all"() {
        given:
        String source = '''
            import grails.compiler.beans.GrailsBeans
            import org.springframework.boot.autoconfigure.AutoConfiguration

            @GrailsBeans
            @AutoConfiguration
            class NoBeansProperty {
            }
        '''

        when:
        compile(source)

        then:
        MultipleCompilationErrorsException e = thrown(MultipleCompilationErrorsException)
        e.message.contains("requires a 'beans' property initialised to a closure")
    }

    def "the beans property must be initialised to a closure"() {
        given:
        String source = '''
            import grails.compiler.beans.GrailsBeans
            import org.springframework.boot.autoconfigure.AutoConfiguration

            @GrailsBeans
            @AutoConfiguration
            class BeansNotAClosure {
                def beans = 'not a closure'
            }
        '''

        when:
        compile(source)

        then:
        MultipleCompilationErrorsException e = thrown(MultipleCompilationErrorsException)
        e.message.contains("'beans' must be initialised to a closure")
    }

    @Unroll
    def "rejects a malformed beans statement: #description"() {
        given:
        String source = """
            import grails.compiler.beans.GrailsBeans
            import org.springframework.boot.autoconfigure.AutoConfiguration

            @GrailsBeans
            @AutoConfiguration
            class MalformedBeans {
                def beans = {
                    $statement
                }
            }
        """

        when:
        compile(source)

        then:
        MultipleCompilationErrorsException e = thrown(MultipleCompilationErrorsException)
        e.message.contains(expectedMessage)

        where:
        description                                  | statement                                                          | expectedMessage
        'a statement that is not a method call'       | 'def notACall = 1'                                                 | "Each 'beans' statement must be a bean(...) call"
        'a method call that is not bean(...)'         | "someOtherMethod(String) { 'x' }"                                  | 'Expected bean(Type[, "name"]) { ... }'
        'bean(...) with a non-type first argument'    | "bean('NotAType') { 'x' }"                                         | 'bean(...) requires a bean type as its first argument'
        'bean(...) with no trailing factory closure'  | "bean(String, 'x')"                                                | 'bean(...) must end with a factory closure'
        'conditionalOnMissingBean(...) with a non-type argument' |
                "bean(String, 'x').conditionalOnMissingBean('not a type') { 'x' }" |
                'conditionalOnMissingBean(...) arguments must be types'
    }

    private static final String FIXTURE_PLUGIN = '''
        import grails.compiler.beans.GrailsBeans
        import grails.plugins.Plugin
        import org.springframework.boot.autoconfigure.AutoConfiguration
        import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration

        @GrailsBeans
        @AutoConfiguration(before = [MessageSourceAutoConfiguration])
        class FixturePlugin extends Plugin {

            String version = '1.0'

            def beans = {
                bean(String, 'greeting') {
                    'hello from plugin'
                }
            }

            String stillHere() {
                'plugin lifecycle members are untouched'
            }
        }
    '''

    def "applying @GrailsBeans to a Plugin subclass generates a sibling AutoConfiguration class"() {
        given:
        GroovyClassLoader loader = new GroovyClassLoader(getClass().classLoader)
        loader.parseClass(FIXTURE_PLUGIN)

        when:
        Class<?> pluginClass = loader.loadClass('FixturePlugin')
        Class<?> autoConfigClass = loader.loadClass('FixturePluginAutoConfiguration')

        then: "the plugin class keeps its own identity and members, minus the DSL"
        Plugin.isAssignableFrom(pluginClass)
        pluginClass.getDeclaredMethod('stillHere').invoke(pluginClass.getDeclaredConstructor().newInstance()) ==
                'plugin lifecycle members are untouched'
        pluginClass.declaredFields*.name.every { it != 'beans' }
        !pluginClass.isAnnotationPresent(AutoConfiguration)

        and: "the generated sibling carries the compiled bean and the moved @AutoConfiguration annotation"
        autoConfigClass.isAnnotationPresent(AutoConfiguration)
        autoConfigClass.getAnnotation(AutoConfiguration).before().toList() == [MessageSourceAutoConfiguration]
        autoConfigClass.getDeclaredMethod('greeting').isAnnotationPresent(Bean)
        autoConfigClass.getDeclaredConstructor().newInstance().greeting() == 'hello from plugin'
    }

    def "a Plugin subclass using @GrailsBeans without @AutoConfiguration fails to compile"() {
        given:
        String source = '''
            import grails.compiler.beans.GrailsBeans
            import grails.plugins.Plugin

            @GrailsBeans
            class PluginWithoutAutoConfiguration extends Plugin {
                def beans = {
                    bean(String, 'greeting') {
                        'hello'
                    }
                }
            }
        '''

        when:
        compile(source)

        then:
        MultipleCompilationErrorsException e = thrown(MultipleCompilationErrorsException)
        e.message.contains('must also be annotated @AutoConfiguration')
    }

}

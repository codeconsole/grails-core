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

import java.lang.annotation.Annotation
import java.lang.reflect.Modifier

import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.codehaus.groovy.control.Phases
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.autoconfigure.condition.SearchStrategy
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Scope
import org.springframework.core.annotation.Order
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

import grails.plugins.Plugin

class GrailsBeansASTTransformationSpec extends Specification {

    @TempDir
    File tempDir

    private static final String FIXTURE = '''
        import grails.compiler.beans.GrailsBeans
        import org.springframework.beans.factory.annotation.Value
        import org.springframework.boot.autoconfigure.AutoConfiguration
        import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
        import org.springframework.core.annotation.Order

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

                bean(String, 'primaryGreeting').primary() {
                    'primary hello'
                }

                bean(String, 'lazyGreeting').lazy() {
                    'lazy hello'
                }

                bean(String, 'scopedGreeting').scope('prototype') {
                    'scoped hello'
                }

                bean(String, 'combinedGreeting').primary().lazy().scope('prototype').conditionalOnMissingBean(String) {
                    'combined hello'
                }

                bean(String, 'orderedGreeting').annotate(Order, value: 1) {
                    'ordered hello'
                }

                bean(String, 'webOnlyGreeting').annotate(ConditionalOnWebApplication) {
                    'web hello'
                }

                bean(String, 'multiAnnotatedGreeting').primary().annotate(Order, value: 2).annotate(ConditionalOnWebApplication) {
                    'multi hello'
                }

                field(String, 'suffix').annotate(Value, value: '${greeting.suffix:!!!}')

                method(String, 'yell') { String input ->
                    input.toUpperCase() + (suffix ?: '')
                }

                bean(String, 'yelledGreeting') {
                    yell('hello')
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

    def "compiles primary() into a @Primary annotation"() {
        given:
        Class<?> fixtureBeans = compile()

        when:
        def method = fixtureBeans.getDeclaredMethod('primaryGreeting')

        then:
        method.isAnnotationPresent(Bean)
        method.isAnnotationPresent(Primary)

        and:
        fixtureBeans.getDeclaredConstructor().newInstance().primaryGreeting() == 'primary hello'
    }

    def "compiles lazy() into a @Lazy annotation"() {
        given:
        Class<?> fixtureBeans = compile()

        when:
        def method = fixtureBeans.getDeclaredMethod('lazyGreeting')

        then:
        method.isAnnotationPresent(Bean)
        method.isAnnotationPresent(Lazy)
        method.getAnnotation(Lazy).value()

        and:
        fixtureBeans.getDeclaredConstructor().newInstance().lazyGreeting() == 'lazy hello'
    }

    def "compiles scope(...) into a @Scope annotation"() {
        given:
        Class<?> fixtureBeans = compile()

        when:
        def method = fixtureBeans.getDeclaredMethod('scopedGreeting')

        then:
        method.isAnnotationPresent(Bean)
        method.isAnnotationPresent(Scope)
        method.getAnnotation(Scope).value() == 'prototype'

        and:
        fixtureBeans.getDeclaredConstructor().newInstance().scopedGreeting() == 'scoped hello'
    }

    def "chains primary(), lazy(), scope(...), and conditionalOnMissingBean(...) together on one bean"() {
        given:
        Class<?> fixtureBeans = compile()

        when:
        def method = fixtureBeans.getDeclaredMethod('combinedGreeting')

        then:
        method.isAnnotationPresent(Bean)
        method.isAnnotationPresent(Primary)
        method.isAnnotationPresent(Lazy)
        method.isAnnotationPresent(Scope)
        method.getAnnotation(Scope).value() == 'prototype'
        method.isAnnotationPresent(ConditionalOnMissingBean)
        method.getAnnotation(ConditionalOnMissingBean).value() as List == [String]

        and:
        fixtureBeans.getDeclaredConstructor().newInstance().combinedGreeting() == 'combined hello'
    }

    def "compiles annotate(Type, attr: value) into that annotation with its members set"() {
        given:
        Class<?> fixtureBeans = compile()

        when:
        def method = fixtureBeans.getDeclaredMethod('orderedGreeting')

        then:
        method.isAnnotationPresent(Bean)
        method.isAnnotationPresent(Order)
        method.getAnnotation(Order).value() == 1

        and:
        fixtureBeans.getDeclaredConstructor().newInstance().orderedGreeting() == 'ordered hello'
    }

    def "compiles annotate(Type) with no members into a bare annotation"() {
        given:
        Class<?> fixtureBeans = compile()

        when:
        def method = fixtureBeans.getDeclaredMethod('webOnlyGreeting')

        then:
        method.isAnnotationPresent(Bean)
        method.isAnnotationPresent(ConditionalOnWebApplication)

        and:
        fixtureBeans.getDeclaredConstructor().newInstance().webOnlyGreeting() == 'web hello'
    }

    def "chains multiple different annotate(...) calls alongside a named qualifier"() {
        given:
        Class<?> fixtureBeans = compile()

        when:
        def method = fixtureBeans.getDeclaredMethod('multiAnnotatedGreeting')

        then:
        method.isAnnotationPresent(Bean)
        method.isAnnotationPresent(Primary)
        method.isAnnotationPresent(Order)
        method.getAnnotation(Order).value() == 2
        method.isAnnotationPresent(ConditionalOnWebApplication)

        and:
        fixtureBeans.getDeclaredConstructor().newInstance().multiAnnotatedGreeting() == 'multi hello'
    }

    def "field(Type, name).annotate(...) declares a private annotated field"() {
        given:
        Class<?> fixtureBeans = compile()

        when:
        def field = fixtureBeans.getDeclaredField('suffix')

        then:
        field.type == String
        Modifier.isPrivate(field.modifiers)
        field.isAnnotationPresent(Value)
        field.getAnnotation(Value).value() == '${greeting.suffix:!!!}'
    }

    def "method(Type, name) declares a private helper method usable from bean(...) and field(...)"() {
        given:
        Class<?> fixtureBeans = compile()

        when:
        def helperMethod = fixtureBeans.getDeclaredMethod('yell', String)

        then: "it is a plain private member, not itself a @Bean"
        helperMethod.returnType == String
        Modifier.isPrivate(helperMethod.modifiers)
        !helperMethod.isAnnotationPresent(Bean)

        when: "a bean() closure calls it, and it reads a sibling field(...)"
        def instance = fixtureBeans.getDeclaredConstructor().newInstance()
        def suffixField = fixtureBeans.getDeclaredField('suffix')
        suffixField.accessible = true
        suffixField.set(instance, '!!!')

        then:
        instance.yelledGreeting() == 'HELLO!!!'
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

    def "a @Qualifier on a closure parameter carries through to the generated method's parameter"() {
        given: "Groovy captures annotations on closure parameters at parse time, and the DSL " +
                "reuses the closure's own Parameter AST nodes directly on the generated method, " +
                "so no dedicated DSL syntax is needed for this - it already works"
        String source = '''
            import grails.compiler.beans.GrailsBeans
            import org.springframework.beans.factory.annotation.Qualifier
            import org.springframework.boot.autoconfigure.AutoConfiguration

            @GrailsBeans
            @AutoConfiguration
            class QualifiedParamFixture {
                def beans = {
                    bean(String, 'special') {
                        'special value'
                    }

                    bean(String, 'shout') { @Qualifier('special') String input ->
                        input.toUpperCase()
                    }
                }
            }
        '''

        when:
        Class<?> fixture = compile(source)
        def method = fixture.getDeclaredMethod('shout', String)
        Annotation[] paramAnnotations = method.parameterAnnotations[0]

        then:
        paramAnnotations.any { it instanceof Qualifier && it.value() == 'special' }
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
            import org.springframework.context.annotation.Primary
            import org.springframework.core.annotation.Order

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
        'a statement that is not a method call'       | 'def notACall = 1'                                                 | "Each 'beans' statement must be a bean(...), field(...), or method(...) call"
        'a method call that is not bean(...)'         | "someOtherMethod(String) { 'x' }"                                  | 'Expected bean(Type[, "name"]) { ... }'
        'bean(...) with a non-type first argument'    | "bean('NotAType') { 'x' }"                                         | 'bean(...) requires a type as its first argument'
        'bean(...) with no trailing factory closure'  | "bean(String, 'x')"                                                | 'bean(...) must end with a factory closure'
        'conditionalOnMissingBean(...) with a non-type argument' |
                "bean(String, 'x').conditionalOnMissingBean('not a type') { 'x' }" |
                'conditionalOnMissingBean(...) arguments must be types'
        'bean(...) with a non-constant name argument'  | "bean(String, someVariable) { 'x' }"                              | 'requires name to be a String literal'
        'bean(...) with a non-String constant name'    | 'bean(String, 42) { \'x\' }'                                       | 'requires name to be a String literal'
        'bean(...) with an unexpected third argument'  | "bean(String, 'x', 'unexpected') { 'y' }"                          | 'at most one name'
        'bean(...) with a name that is not a valid identifier' | "bean(String, '123 not valid!') { 'x' }"                   | 'is not a valid name'
        'an unrecognised qualifier chained after bean(...)' | "bean(String, 'x').unknownQualifier() { 'y' }"                | 'Expected bean(Type[, "name"]) { ... }'
        'the same qualifier chained twice'             | "bean(String, 'x').primary().primary() { 'y' }"                    | 'may only be chained once'
        'primary() given an argument'                  | "bean(String, 'x').primary('oops') { 'y' }"                        | '.primary() takes no arguments'
        'lazy() given an argument'                      | "bean(String, 'x').lazy(true) { 'y' }"                             | '.lazy() takes no arguments'
        'scope(...) with no argument'                   | "bean(String, 'x').scope() { 'y' }"                                | '.scope(...) requires exactly one non-empty String argument'
        'scope(...) with a non-String argument'         | "bean(String, 'x').scope(42) { 'y' }"                              | '.scope(...) requires exactly one non-empty String argument'
        'annotate(...) with no arguments'                | "bean(String, 'x').annotate() { 'y' }"                            | 'requires an annotation type'
        'annotate(...) with a non-type argument'         | "bean(String, 'x').annotate('NotAType') { 'y' }"                  | 'requires an annotation type'
        'annotate(...) with a non-annotation type'       | "bean(String, 'x').annotate(String) { 'y' }"                      | 'is not an annotation type'
        'the same annotation attached twice via annotate(...)' |
                "bean(String, 'x').annotate(Order, value: 1).annotate(Order, value: 2) { 'y' }" |
                'already attached'
        'annotate(...) colliding with a named qualifier' | "bean(String, 'x').primary().annotate(Primary) { 'y' }"          | 'already attached'
        'field(...) with a non-type first argument'      | "field('NotAType')"                                               | 'field(...) requires a type as its first argument'
        'field(...) chained with a bean-only qualifier'   | "field(String, 'x').primary()"                                    | 'cannot be chained onto field(...)'
        'method(...) without a body closure'              | "method(String, 'x')"                                             | 'method(...) must end with a body closure'
        'method(...) chained with a bean-only qualifier'  | "method(String, 'x').conditionalOnMissingBean(String) { 'y' }"    | 'cannot be chained onto method(...)'
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

    def "compiles under @CompileStatic"() {
        given:
        String source = '''
            import groovy.transform.CompileStatic
            import grails.compiler.beans.GrailsBeans
            import org.springframework.boot.autoconfigure.AutoConfiguration

            @GrailsBeans
            @CompileStatic
            @AutoConfiguration
            class CompileStaticFixture {
                def beans = {
                    bean(String, 'greeting') {
                        'hello'
                    }
                }
            }
        '''

        when:
        Class<?> fixture = compile(source)

        then:
        fixture.getDeclaredConstructor().newInstance().greeting() == 'hello'
    }

    def "annotate(...)'s named-argument syntax compiles under @CompileStatic"() {
        given: "the beans property is stripped before static type-checking ever runs, so the " +
                "DSL's Map-literal named-args never reach the type checker"
        String source = '''
            import groovy.transform.CompileStatic
            import grails.compiler.beans.GrailsBeans
            import org.springframework.boot.autoconfigure.AutoConfiguration
            import org.springframework.core.annotation.Order

            @GrailsBeans
            @CompileStatic
            @AutoConfiguration
            class AnnotateCompileStaticFixture {
                def beans = {
                    bean(String, 'greeting').annotate(Order, value: 1) {
                        'hello'
                    }
                }
            }
        '''

        when:
        Class<?> fixture = compile(source)

        then:
        fixture.getDeclaredMethod('greeting').getAnnotation(Order).value() == 1
        fixture.getDeclaredConstructor().newInstance().greeting() == 'hello'
    }

    def "compiles under @CompileStatic on a Plugin subclass"() {
        given:
        String source = '''
            import groovy.transform.CompileStatic
            import grails.compiler.beans.GrailsBeans
            import grails.plugins.Plugin
            import org.springframework.boot.autoconfigure.AutoConfiguration

            @GrailsBeans
            @CompileStatic
            @AutoConfiguration
            class CompileStaticPluginFixture extends Plugin {
                String version = '1.0'

                def beans = {
                    bean(String, 'greeting') {
                        'hello'
                    }
                }

                @Override
                void doWithApplicationContext() {
                    String x = 'statically typed local'
                }
            }
        '''

        when:
        Class<?> pluginClass = compile(source)
        Class<?> autoConfigClass = new GroovyClassLoader(pluginClass.classLoader).loadClass('CompileStaticPluginFixtureAutoConfiguration')

        then:
        autoConfigClass.getDeclaredConstructor().newInstance().greeting() == 'hello'
    }

    def "field(...) and method(...) resolve correctly from a bean() closure under @CompileStatic"() {
        given: "a field and a private helper method are relocated from the DSL closure into the " +
                "generated class's own members, and this proves a sibling bean() referencing them " +
                "by simple name still resolves once static type checking examines the final class"
        String source = '''
            import groovy.transform.CompileStatic
            import grails.compiler.beans.GrailsBeans
            import org.springframework.beans.factory.annotation.Value
            import org.springframework.boot.autoconfigure.AutoConfiguration

            @GrailsBeans
            @CompileStatic
            @AutoConfiguration
            class CompileStaticMembersFixture {
                def beans = {
                    field(String, 'suffix').annotate(Value, value: '${greeting.suffix:!!!}')

                    method(String, 'yell') { String input ->
                        input.toUpperCase() + (suffix ?: '')
                    }

                    bean(String, 'yelledGreeting') {
                        yell('hello')
                    }
                }
            }
        '''

        when:
        Class<?> fixture = compile(source)
        def instance = fixture.getDeclaredConstructor().newInstance()
        def suffixField = fixture.getDeclaredField('suffix')
        suffixField.accessible = true
        suffixField.set(instance, '!!!')

        then:
        instance.yelledGreeting() == 'HELLO!!!'
    }

    def "field(...) and method(...) resolve correctly on the Plugin-subclass sibling under @CompileStatic"() {
        given:
        String source = '''
            import groovy.transform.CompileStatic
            import grails.compiler.beans.GrailsBeans
            import grails.plugins.Plugin
            import org.springframework.beans.factory.annotation.Value
            import org.springframework.boot.autoconfigure.AutoConfiguration

            @GrailsBeans
            @CompileStatic
            @AutoConfiguration
            class CompileStaticMembersPluginFixture extends Plugin {
                String version = '1.0'

                def beans = {
                    field(String, 'suffix').annotate(Value, value: '${greeting.suffix:!!!}')

                    method(String, 'yell') { String input ->
                        input.toUpperCase() + (suffix ?: '')
                    }

                    bean(String, 'yelledGreeting') {
                        yell('hello')
                    }
                }
            }
        '''

        when:
        Class<?> pluginClass = compile(source)
        Class<?> autoConfigClass = new GroovyClassLoader(pluginClass.classLoader)
                .loadClass('CompileStaticMembersPluginFixtureAutoConfiguration')
        def instance = autoConfigClass.getDeclaredConstructor().newInstance()
        def suffixField = autoConfigClass.getDeclaredField('suffix')
        suffixField.accessible = true
        suffixField.set(instance, '!!!')

        then:
        instance.yelledGreeting() == 'HELLO!!!'
    }

    def "a realistic i18n-plugin-shaped conversion compiles and behaves correctly"() {
        given: "field(...)/method(...)/bean(...) together, modelled on the real conversion now " +
                "live in org.grails.plugins.i18n.I18nGrailsPlugin - injected config fields feeding a " +
                "strategy-selecting helper method and a multi-field-dependent bean, each bean backing " +
                "off an existing same-named bean the way the real plugin does. LocaleResolver stands " +
                "in for org.springframework.web.servlet.LocaleResolver (spring-webmvc isn't a " +
                "dependency of this module) but the DSL usage is identical either way"
        String source = '''
            import grails.compiler.beans.GrailsBeans
            import grails.plugins.Plugin
            import org.springframework.beans.factory.annotation.Value
            import org.springframework.boot.autoconfigure.AutoConfiguration
            import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
            import org.springframework.boot.autoconfigure.condition.SearchStrategy
            import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration
            import org.springframework.context.support.ReloadableResourceBundleMessageSource

            interface LocaleResolver {
                String describe()
            }

            class CookieLocaleResolver implements LocaleResolver {
                String describe() { 'cookie' }
            }

            class SessionLocaleResolver implements LocaleResolver {
                String describe() { 'session' }
            }

            @GrailsBeans
            @AutoConfiguration(before = MessageSourceAutoConfiguration)
            class I18nStylePlugin extends Plugin {

                String version = "1.0"

                def beans = {
                    field(String, "encoding").annotate(Value, value: \'${grails.gsp.view.encoding:UTF-8}\')
                    field(String, "localeResolverType").annotate(Value, value: \'${grails.i18n.locale.resolver:session}\')

                    method(LocaleResolver, "buildLocaleResolver") {
                        localeResolverType?.toLowerCase() == "cookie" ? new CookieLocaleResolver() : new SessionLocaleResolver()
                    }

                    bean(LocaleResolver, "localeResolver")
                            .annotate(ConditionalOnMissingBean, name: "localeResolver", search: SearchStrategy.CURRENT) {
                        buildLocaleResolver()
                    }

                    method(ReloadableResourceBundleMessageSource, "buildMessageSource") {
                        def source = new ReloadableResourceBundleMessageSource(basename: "WEB-INF/grails-app/i18n/messages")
                        source.defaultEncoding = encoding
                        source
                    }

                    bean(ReloadableResourceBundleMessageSource, "messageSource")
                            .annotate(ConditionalOnMissingBean, name: "messageSource", search: SearchStrategy.CURRENT) {
                        buildMessageSource()
                    }
                }

                @Override
                void doWithApplicationContext() {
                }
            }
        '''

        when:
        Class<?> pluginClass = compile(source)
        Class<?> autoConfigClass = new GroovyClassLoader(pluginClass.classLoader).loadClass('I18nStylePluginAutoConfiguration')
        def instance = autoConfigClass.getDeclaredConstructor().newInstance()
        def localeResolverTypeField = autoConfigClass.getDeclaredField('localeResolverType')
        localeResolverTypeField.accessible = true
        localeResolverTypeField.set(instance, 'cookie')
        def encodingField = autoConfigClass.getDeclaredField('encoding')
        encodingField.accessible = true
        encodingField.set(instance, 'ISO-8859-1')

        then: "the strategy-selecting helper, driven by an injected field, picks the right resolver"
        instance.localeResolver().describe() == 'cookie'

        and: "the second bean, built from a different helper reading a different field, also works"
        instance.messageSource().defaultEncoding == 'ISO-8859-1'

        and: "every bean backs off an existing same-named bean, matching the real plugin's semantics"
        autoConfigClass.getDeclaredMethod('localeResolver')
                .getAnnotation(ConditionalOnMissingBean).search() == SearchStrategy.CURRENT
    }

    def "compiles under @GrailsCompileStatic on a Plugin subclass"() {
        given:
        String source = '''
            import grails.compiler.GrailsCompileStatic
            import grails.compiler.beans.GrailsBeans
            import grails.plugins.Plugin
            import org.springframework.boot.autoconfigure.AutoConfiguration

            @GrailsBeans
            @GrailsCompileStatic
            @AutoConfiguration
            class GrailsCompileStaticPluginFixture extends Plugin {
                String version = '1.0'

                def beans = {
                    bean(String, 'greeting') {
                        'hello'
                    }
                }
            }
        '''

        when:
        Class<?> pluginClass = compile(source)
        Class<?> autoConfigClass = new GroovyClassLoader(pluginClass.classLoader).loadClass('GrailsCompileStaticPluginFixtureAutoConfiguration')

        then:
        autoConfigClass.getDeclaredConstructor().newInstance().greeting() == 'hello'
    }

    private static final String PLUGIN_FIXTURE_TEMPLATE = '''
        import grails.compiler.beans.GrailsBeans
        import grails.plugins.Plugin
        import org.springframework.boot.autoconfigure.AutoConfiguration
        %s

        @GrailsBeans
        %s
        @AutoConfiguration
        class DispatchFixturePlugin extends Plugin {
            String version = '1.0'

            def beans = {
                bean(String, 'greeting') {
                    'hello'.toUpperCase()
                }
            }
        }
    '''

    private static final String STANDALONE_FIXTURE_TEMPLATE = '''
        import grails.compiler.beans.GrailsBeans
        import org.springframework.boot.autoconfigure.AutoConfiguration
        %s

        @GrailsBeans
        %s
        @AutoConfiguration
        class DispatchFixtureStandalone {
            def beans = {
                bean(String, 'greeting') {
                    'hello'.toUpperCase()
                }
            }
        }
    '''

    private File compileToRealClassFiles(String template, String staticAnnotation, String fileName, String subDir) {
        String annotationImport = staticAnnotation == 'GrailsCompileStatic' ?
                'import grails.compiler.GrailsCompileStatic' : 'import groovy.transform.CompileStatic'
        String source = String.format(template,
                staticAnnotation ? annotationImport : '',
                staticAnnotation ? "@${staticAnnotation}" : '')
        File destDir = new File(tempDir, subDir)
        CompilerConfiguration config = new CompilerConfiguration()
        config.targetDirectory = destDir
        CompilationUnit unit = new CompilationUnit(config, null, new GroovyClassLoader(getClass().classLoader))
        unit.addSource(fileName, source)
        unit.compile(Phases.OUTPUT)
        destDir
    }

    private boolean usesInvokeDynamic(File classFile) {
        String javap = "${System.getProperty('java.home')}/bin/javap"
        Process process = [javap, '-c', '-p', classFile.absolutePath].execute()
        process.waitFor()
        process.text.contains('invokedynamic')
    }

    def "the standalone form's @Bean methods are statically dispatched when @CompileStatic is present"() {
        given: "the same DSL compiled with and without @CompileStatic on the annotated class"
        File staticDir = compileToRealClassFiles(STANDALONE_FIXTURE_TEMPLATE, 'CompileStatic', 'Static.groovy', 'standalone-static')
        File dynamicDir = compileToRealClassFiles(STANDALONE_FIXTURE_TEMPLATE, null, 'Dynamic.groovy', 'standalone-dynamic')

        expect: "the generated greeting() method is compiled the same way as everything else on the class"
        !usesInvokeDynamic(new File(staticDir, 'DispatchFixtureStandalone.class'))
        usesInvokeDynamic(new File(dynamicDir, 'DispatchFixtureStandalone.class'))
    }

    def "the Plugin-subclass sibling's @Bean methods are statically dispatched when @CompileStatic is present"() {
        given: "the same DSL compiled with and without @CompileStatic on the Plugin subclass"
        File staticDir = compileToRealClassFiles(PLUGIN_FIXTURE_TEMPLATE, 'CompileStatic', 'StaticPlugin.groovy', 'plugin-static')
        File dynamicDir = compileToRealClassFiles(PLUGIN_FIXTURE_TEMPLATE, null, 'DynamicPlugin.groovy', 'plugin-dynamic')

        expect: "the transform explicitly applies static compilation after generating the sibling"
        !usesInvokeDynamic(new File(staticDir, 'DispatchFixturePluginAutoConfiguration.class'))
        usesInvokeDynamic(new File(dynamicDir, 'DispatchFixturePluginAutoConfiguration.class'))
    }

    def "the Plugin-subclass sibling's @Bean methods are statically dispatched under @GrailsCompileStatic"() {
        given:
        File staticDir = compileToRealClassFiles(
                PLUGIN_FIXTURE_TEMPLATE, 'GrailsCompileStatic', 'GrailsStaticPlugin.groovy', 'plugin-grails-static')

        expect:
        !usesInvokeDynamic(new File(staticDir, 'DispatchFixturePluginAutoConfiguration.class'))
    }

}

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
package grails.boot.config

import org.springframework.beans.factory.BeanRegistrar
import org.springframework.beans.factory.BeanRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

import grails.core.GrailsApplication
import grails.plugins.GrailsPluginManager
import grails.plugins.Plugin
import grails.util.Environment
import grails.util.Holders
import org.apache.grails.core.plugins.DefaultPluginDiscovery
import org.apache.grails.core.plugins.PluginDiscovery
import spock.lang.Specification

/**
 * Proves the architectural linchpin of {@link GrailsEarlyPluginRegistrationPostProcessor}: a plugin
 * bean registered via {@code doWithSpring} lands in the registry <em>before</em>
 * {@code ConfigurationClassPostProcessor} evaluates {@code @ConditionalOnMissingBean}, so the
 * matching Boot auto-config bean backs off — instead of the plugin having to override or remove
 * it afterwards.
 */
class EarlyPluginRegistrationOrderingSpec extends Specification {

    void 'a plugin doWithSpring bean registered early makes a @ConditionalOnMissingBean auto-config bean defer'() {
        given: 'a context whose configuration would, by itself, register a conditional myResolver'
            def ctx = new AnnotationConfigApplicationContext()
            ctx.register(EarlyOrderingAutoConfigLikeConfig)

        and: 'a plugin discovery promoted to the context, exactly as the bootstrap registry does'
            def discovery = new DefaultPluginDiscovery([earlyOrderingPluginClass] as Class<?>[])
            discovery.loadPluginsFromClasspath = false
            discovery.init(ctx.environment)
            ctx.beanFactory.registerSingleton(PluginDiscovery.BEAN_NAME, discovery)

        and: 'the early registration phase installed through its real entry point'
            new GrailsPluginLifecycleInitializer().initialize(ctx)

        when: 'the context refreshes'
            ctx.refresh()

        then: 'the early (plugin) bean is the one named myResolver...'
            ctx.getBean('myResolver') instanceof EarlyOrderingPluginResolver

        and: '...and the conditional default was never created'
            ctx.getBeansOfType(EarlyOrderingBootDefaultResolver).isEmpty()

        and: 'the one true grailsApplication and pluginManager singletons were promoted'
            ctx.getBean(GrailsApplication.APPLICATION_ID) instanceof GrailsApplication
            ctx.getBean(GrailsPluginManager.BEAN_NAME) instanceof GrailsPluginManager
            ctx.getBean(GrailsPluginManager.BEAN_NAME, GrailsPluginManager).getGrailsPlugin('earlyOrdering') != null

        and: 'the environment initializing flag was reset on refresh'
            !Environment.isInitializing()

        cleanup:
            ctx.close()
            Holders.clear()
            Environment.setInitializing(false)
    }

    void 'without a promoted plugin discovery the early phase is a no-op and the conditional bean is created (control)'() {
        given:
            def ctx = new AnnotationConfigApplicationContext()
            ctx.register(EarlyOrderingAutoConfigLikeConfig)
            new GrailsPluginLifecycleInitializer().initialize(ctx)

        when:
            ctx.refresh()

        then: 'the conditional default wins when nothing registered the bean first'
            ctx.getBean('myResolver') instanceof EarlyOrderingBootDefaultResolver

        and: 'no Grails singletons were promoted'
            !ctx.beanFactory.containsSingleton(GrailsApplication.APPLICATION_ID)
            !ctx.beanFactory.containsSingleton(GrailsPluginManager.BEAN_NAME)

        cleanup:
            ctx.close()
    }

    void 'a plugin beanRegistrar bean registered early makes a @ConditionalOnMissingBean auto-config bean defer'() {
        given: 'a context whose configuration would, by itself, register a conditional myResolver'
            def ctx = new AnnotationConfigApplicationContext()
            ctx.register(EarlyOrderingAutoConfigLikeConfig)

        and: 'a plugin exposing a BeanRegistrar promoted through plugin discovery'
            registerDiscovery(ctx, EarlyOrderingRegistrarGrailsPlugin)
            new GrailsPluginLifecycleInitializer().initialize(ctx)

        when: 'the context refreshes'
            ctx.refresh()

        then: 'the registrar (plugin) bean is the one named myResolver and the conditional default backed off'
            ctx.getBean('myResolver') instanceof EarlyOrderingPluginResolver
            ctx.getBeansOfType(EarlyOrderingBootDefaultResolver).isEmpty()

        cleanup:
            ctx.close()
            Holders.clear()
            Environment.setInitializing(false)
    }

    void 'plugin beanRegistrar and doWithSpring can coexist on one plugin'() {
        given:
            def ctx = new AnnotationConfigApplicationContext()
            registerDiscovery(ctx, EarlyOrderingDualGrailsPlugin)
            new GrailsPluginLifecycleInitializer().initialize(ctx)

        when:
            ctx.refresh()

        then: 'both the DSL bean and the registrar bean are present'
            ctx.getBean('dualDslBean') instanceof EarlyOrderingPluginResolver
            ctx.getBean('dualRegistrarBean') instanceof EarlyOrderingPluginResolver

        cleanup:
            ctx.close()
            Holders.clear()
            Environment.setInitializing(false)
    }

    private static void registerDiscovery(AnnotationConfigApplicationContext ctx, Class<?> pluginClass) {
        def discovery = new DefaultPluginDiscovery([pluginClass] as Class<?>[])
        discovery.loadPluginsFromClasspath = false
        discovery.init(ctx.environment)
        ctx.beanFactory.registerSingleton(PluginDiscovery.BEAN_NAME, discovery)
    }

    private static Class<?> getEarlyOrderingPluginClass() {
        new GroovyClassLoader(EarlyPluginRegistrationOrderingSpec.classLoader).parseClass('''
class EarlyOrderingGrailsPlugin {
    def version = '1.0'
    def doWithSpring = {
        myResolver(grails.boot.config.EarlyOrderingPluginResolver)
    }
}
''')
    }

    /** Stands in for a Spring Boot auto-configuration: a name-guarded conditional bean. */
    @Configuration
    static class EarlyOrderingAutoConfigLikeConfig {

        @Bean
        @ConditionalOnMissingBean(name = 'myResolver')
        EarlyOrderingBootDefaultResolver myResolver() {
            new EarlyOrderingBootDefaultResolver()
        }
    }
}

class EarlyOrderingBootDefaultResolver {
}

class EarlyOrderingPluginResolver {
}

class EarlyOrderingRegistrarGrailsPlugin extends Plugin {

    def version = '1.0'

    @Override
    BeanRegistrar beanRegistrar() {
        new EarlyOrderingResolverRegistrar()
    }
}

class EarlyOrderingResolverRegistrar implements BeanRegistrar {

    @Override
    void register(BeanRegistry registry, org.springframework.core.env.Environment environment) {
        registry.registerBean('myResolver', EarlyOrderingPluginResolver)
    }
}

class EarlyOrderingDualGrailsPlugin extends Plugin {

    def version = '1.0'

    @Override
    Closure doWithSpring() {
        { ->
            dualDslBean(EarlyOrderingPluginResolver)
        }
    }

    @Override
    BeanRegistrar beanRegistrar() {
        new EarlyOrderingDualRegistrar()
    }
}

class EarlyOrderingDualRegistrar implements BeanRegistrar {

    @Override
    void register(BeanRegistry registry, org.springframework.core.env.Environment environment) {
        registry.registerBean('dualRegistrarBean', EarlyOrderingPluginResolver)
    }
}

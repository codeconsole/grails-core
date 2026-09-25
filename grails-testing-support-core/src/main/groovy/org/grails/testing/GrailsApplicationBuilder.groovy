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

package org.grails.testing

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic

import jakarta.servlet.ServletContext

import org.springframework.beans.BeansException
import org.springframework.beans.MutablePropertyValues
import org.springframework.beans.factory.BeanRegistrar
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.beans.factory.config.ConstructorArgumentValues
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanRegistryAdapter
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.AnnotationConfigRegistry
import org.springframework.context.annotation.AnnotationConfigUtils
import org.springframework.context.support.ConversionServiceFactoryBean
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer
import org.springframework.context.support.StaticMessageSource
import org.springframework.core.Ordered
import org.springframework.util.ClassUtils
import org.springframework.web.context.ConfigurableWebApplicationContext

import grails.boot.config.GrailsApplicationPostProcessor
import grails.config.Settings
import grails.core.GrailsApplication
import grails.core.GrailsApplicationLifeCycle
import grails.core.support.proxy.DefaultProxyHandler
import grails.plugins.GrailsPluginManager
import grails.spring.BeanBuilder
import grails.util.Holders
import org.grails.core.support.GrailsApplicationDiscoveryStrategy
import org.apache.grails.core.plugins.DefaultPluginDiscovery
import org.apache.grails.core.plugins.PluginInfo
import org.apache.grails.core.plugins.filters.IncludingPluginFilter
import org.apache.grails.core.plugins.PluginDiscovery
import org.grails.spring.context.support.GrailsPlaceholderConfigurer
import org.grails.spring.context.support.MapBasedSmartPropertyOverrideConfigurer
import org.grails.transaction.TransactionManagerPostProcessor

/**
 * Created by jameskleeh on 5/31/17.
 */
@CompileStatic
class GrailsApplicationBuilder {

    public static final boolean isServletApiPresent = ClassUtils.isPresent(
            'jakarta.servlet.ServletContext',
            GrailsApplicationBuilder.classLoader
    )

    static final Set DEFAULT_INCLUDED_PLUGINS = ['core', 'eventBus'] as Set

    static final String GRAILS_PLUGIN_SUFFIX = 'GrailsPlugin'
    static final String AUTO_CONFIGURATION_SUFFIX = 'AutoConfiguration'

    Closure doWithSpring
    BeanRegistrar beanRegistrar
    Collection<Class<?>> configurationClasses
    Closure doWithConfig
    Set<String> includePlugins
    boolean loadExternalBeans
    boolean localOverride = false

    GrailsApplication grailsApplication
    Object servletContext

    GrailsApplicationBuilder build() {

        servletContext = createServletContext()
        def mainContext = createMainContext(servletContext)

        if (isServletApiPresent) {
            // NOTE: The following dynamic class loading hack is temporary so the
            // compile time dependency on the servlet api can be removed from this
            // sub project.  This whole GrailsApplicationTestPlugin class will soon
            // be removed so rather than implement a real solution, this hack will
            // do for now to keep the build healthy.
            try {
                def appDiscoveryStrategyClass = Class.forName(
                        'org.grails.web.context.ServletEnvironmentGrailsApplicationDiscoveryStrategy'
                )
                def appDiscoveryStrategy = appDiscoveryStrategyClass
                        .getDeclaredConstructor(ServletContext)
                        .newInstance(servletContext)
                Holders.addApplicationDiscoveryStrategy(
                        (GrailsApplicationDiscoveryStrategy) appDiscoveryStrategy
                )
            }
            catch (Throwable ignored) {}

            try {
                def gcu = Class.forName('org.grails.web.servlet.context.GrailsConfigUtils')
                def method = gcu.methods.find { it.name == 'configureServletContextAttributes' }
                method?.invoke(
                        null,
                        servletContext,
                        grailsApplication,
                        mainContext.getBean(GrailsPluginManager.BEAN_NAME, GrailsPluginManager),
                        mainContext
                )
            }
            catch (Throwable ignored) {}
        }

        grailsApplication = mainContext.getBean('grailsApplication') as GrailsApplication

        if (!grailsApplication.initialised) {
            grailsApplication.initialise()
        }

        return this
    }

    protected Object createServletContext() {

        def context = null

        if (isServletApiPresent) {
            context = ClassUtils.forName('org.springframework.mock.web.MockServletContext').getDeclaredConstructor().newInstance()
            Holders.servletContext = context
        }

        return context
    }

    protected ConfigurableApplicationContext createMainContext(Object servletContext) {
        ConfigurableApplicationContext context
        if (isServletApiPresent && servletContext != null) {
            // Spring Boot 4.0: AnnotationConfigServletWebApplicationContext relocated from
            // org.springframework.boot.web.servlet.context to org.springframework.boot.web.context.servlet
            context = (ConfigurableApplicationContext) ClassUtils
                    .forName('org.springframework.boot.web.context.servlet.AnnotationConfigServletWebApplicationContext')
                    .getDeclaredConstructor()
                    .newInstance()
            ((ConfigurableWebApplicationContext) context).setServletContext((ServletContext) servletContext)
        } else {
            context = (ConfigurableApplicationContext) ClassUtils
                    .forName('org.springframework.context.annotation.AnnotationConfigApplicationContext')
                    .getDeclaredConstructor()
                    .newInstance()
        }

        def beanFactory = context.beanFactory as DefaultListableBeanFactory
        // Initialize the environment before any bean definitions are registered so that the spring.main.*
        // bean definition overriding and circular reference settings govern the whole context lifecycle
        // rather than being applied after beans have already been defined. Both default to true (the
        // historical Grails behavior) and can be turned off via the standard spring.main.* properties.
        new ConfigDataApplicationContextInitializer().initialize(context)
        def environment = context.environment
        beanFactory.allowBeanDefinitionOverriding = environment.getProperty(Settings.SPRING_MAIN_ALLOW_BEAN_DEFINITION_OVERRIDING, Boolean, Boolean.TRUE)
        beanFactory.allowCircularReferences = environment.getProperty(Settings.SPRING_MAIN_ALLOW_CIRCULAR_REFERENCES, Boolean, Boolean.TRUE)

        def classLoader = this.class.classLoader
        // The test's own configuration first, as an application's comes before auto-configuration:
        // parsed ahead of them, its beans are what an auto-configuration's @ConditionalOnMissingBean sees.
        configurationClasses?.each { Class<?> configurationClass ->
            ((AnnotationConfigRegistry) context).register(configurationClass)
        }

        Set<String> includedPluginAutoConfigurations = generatedAutoConfigurationNames(
                registerPluginDiscoveryBean(context, beanFactory))
        ImportCandidates.load(AutoConfiguration, classLoader).asList().findAll {
            (it.startsWith('org.grails')
                    && !it.contains('UrlMappingsAutoConfiguration')) // this currently is causing an issue with tests
            || includedPluginAutoConfigurations.contains(it)
        }.each {
            ((AnnotationConfigRegistry) context).register(ClassUtils.forName(it, classLoader))
        }

        prepareContext(context, beanFactory)
        context.refresh()
        context.registerShutdownHook()
        return context
    }

    protected void prepareContext(ConfigurableApplicationContext applicationContext, ConfigurableBeanFactory beanFactory) {
        def discovery = registerPluginDiscoveryBean(applicationContext, beanFactory)
        registerGrailsAppPostProcessorBean(beanFactory, discovery)
        AnnotationConfigUtils.registerAnnotationConfigProcessors((BeanDefinitionRegistry) beanFactory)
    }

    protected PluginDiscovery registerPluginDiscoveryBean(ConfigurableApplicationContext applicationContext, ConfigurableBeanFactory beanFactory) {
        // Registered while the auto-configurations are chosen, which is before prepareContext asks
        if (beanFactory.containsSingleton(PluginDiscovery.BEAN_NAME)) {
            return (PluginDiscovery) beanFactory.getSingleton(PluginDiscovery.BEAN_NAME)
        }
        def discovery = new DefaultPluginDiscovery()
        // we must load the classpath since the plugin manager needs to find the default plugins
        discovery.pluginFilter = new IncludingPluginFilter(includePlugins ?: DEFAULT_INCLUDED_PLUGINS)
        discovery.init(applicationContext.getEnvironment())
        beanFactory.registerSingleton(PluginDiscovery.BEAN_NAME, discovery)
        discovery
    }

    /**
     * The class each included plugin's {@code beans} block compiles to, named as {@code @GrailsBeans}
     * names it by default: {@code FooGrailsPlugin} gives {@code FooAutoConfiguration}, and any other
     * name has {@code AutoConfiguration} appended, in the plugin's package. Only classes the build
     * listed as auto-configurations are registered, so a name that merely matches is never picked up.
     * The framework's own are registered regardless, with every {@code org.grails} auto-configuration.
     */
    protected static Set<String> generatedAutoConfigurationNames(PluginDiscovery discovery) {
        Set<String> names = new LinkedHashSet<>()
        for (PluginInfo plugin : discovery.pluginsInLoadOrder) {
            Class<?> pluginClass = plugin.pluginClass
            if (pluginClass == null) {
                continue
            }
            String simpleName = pluginClass.simpleName
            String base = simpleName.endsWith(GRAILS_PLUGIN_SUFFIX) && simpleName.length() > GRAILS_PLUGIN_SUFFIX.length()
                    ? simpleName.substring(0, simpleName.length() - GRAILS_PLUGIN_SUFFIX.length())
                    : simpleName
            String simpleAutoConfigurationName = base + AUTO_CONFIGURATION_SUFFIX
            String packageName = pluginClass.packageName
            names << (packageName ? packageName + '.' + simpleAutoConfigurationName : simpleAutoConfigurationName)
        }
        names
    }

    void executeDoWithSpringCallback(GrailsApplication grailsApplication) {
        if (!doWithSpring) return
        defineBeans(grailsApplication, doWithSpring)
    }

    void defineBeans(Closure callable) {
        defineBeans(grailsApplication, callable)
    }

    void defineBeans(GrailsApplication grailsApplication, Closure callable) {
        def binding = new Binding()
        def bb = new BeanBuilder(null, null, grailsApplication.classLoader)
        binding.setVariable('application', grailsApplication)
        bb.binding = binding
        bb.beans(callable)
        bb.registerBeans((BeanDefinitionRegistry) grailsApplication.mainContext)
    }

    @CompileDynamic
    void registerBeans(GrailsApplication grailsApplication) {
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) grailsApplication.mainContext
        Map<String, BeanDefinition> declaredByTest = beanDefinitionsDeclaredBy(configurationClasses, registry)

        defineBeans(grailsApplication) { ->

            conversionService(ConversionServiceFactoryBean)

            xmlns(context: 'http://www.springframework.org/schema/context')
            // adds AutowiredAnnotationBeanPostProcessor, CommonAnnotationBeanPostProcessor and others
            // see org.springframework.context.annotation.AnnotationConfigUtils.registerAnnotationConfigProcessors method
            context.'annotation-config'()

            proxyHandler(DefaultProxyHandler)
            messageSource(StaticMessageSource)
            transactionManagerAwarePostProcessor(TransactionManagerPostProcessor)
            grailsPlaceholderConfigurer(GrailsPlaceholderConfigurer, '${', grailsApplication.config.toProperties())
            mapBasedSmartPropertyOverrideConfigurer(MapBasedSmartPropertyOverrideConfigurer) {
                setGrailsApplication(grailsApplication)
            }
        }

        // These stand in for framework beans that an application's own configuration replaces (an
        // auto-configuration's @ConditionalOnMissingBean backs off from it), so the test's own
        // configuration keeps what it declared under one of their names.
        declaredByTest.each { String name, BeanDefinition definition ->
            if (!registry.getBeanDefinition(name).is(definition)) {
                registry.registerBeanDefinition(name, definition)
            }
        }
    }

    /**
     * The bean definitions the test's configuration classes declared, a {@code group(...)} nested in
     * one included, read off the {@code @Bean} method each came from.
     */
    protected static Map<String, BeanDefinition> beanDefinitionsDeclaredBy(Collection<Class<?>> configurationClasses,
                                                                         BeanDefinitionRegistry registry) {
        Map<String, BeanDefinition> declared = [:]
        if (!configurationClasses) {
            return declared
        }
        Set<String> owners = configurationClasses*.name as Set<String>
        for (String name : registry.beanDefinitionNames) {
            BeanDefinition definition = registry.getBeanDefinition(name)
            if (!(definition instanceof AnnotatedBeanDefinition)) {
                continue
            }
            String declaringClass = ((AnnotatedBeanDefinition) definition).factoryMethodMetadata?.declaringClassName
            if (declaringClass && owners.any { String owner -> declaringClass == owner || declaringClass.startsWith(owner + '$') }) {
                declared[name] = definition
            }
        }
        declared
    }

    protected void registerGrailsAppPostProcessorBean(ConfigurableBeanFactory beanFactory, PluginDiscovery pluginDiscovery) {

        GrailsApplication grailsApp

        Closure doWithSpringClosure = {
            registerBeans(grailsApp)
            executeDoWithSpringCallback(grailsApp)
        }

        Closure customizeGrailsApplicationClosure = { GrailsApplication grailsApplication ->
            grailsApp = grailsApplication
            if (doWithConfig) {
                doWithConfig.call(grailsApplication.config)
                // reset flatConfig
                grailsApplication.configChanged()
            }
            Holders.config = grailsApplication.config
        }

        def constructorArgumentValues = new ConstructorArgumentValues()
        constructorArgumentValues.addIndexedArgumentValue(0, doWithSpringClosure)
        constructorArgumentValues.addIndexedArgumentValue(1, pluginDiscovery)

        def values = new MutablePropertyValues()
        values.add('localOverride', localOverride)
        values.add('beanRegistrar', beanRegistrar)
        values.add('loadExternalBeans', loadExternalBeans)
        values.add('customizeGrailsApplicationClosure', customizeGrailsApplicationClosure)

        def beanDef = new RootBeanDefinition(TestRuntimeGrailsApplicationPostProcessor, constructorArgumentValues, values)
        beanDef.role = BeanDefinition.ROLE_INFRASTRUCTURE
        (beanFactory as BeanDefinitionRegistry).registerBeanDefinition('grailsApplicationPostProcessor', beanDef)
    }

    static class TestRuntimeGrailsApplicationPostProcessor extends GrailsApplicationPostProcessor {

        Closure customizeGrailsApplicationClosure
        boolean localOverride = false
        BeanRegistrar beanRegistrar

        TestRuntimeGrailsApplicationPostProcessor(Closure doWithSpringClosure, PluginDiscovery pluginDiscovery) {
            super([doWithSpring: { -> doWithSpringClosure }] as GrailsApplicationLifeCycle, null, pluginDiscovery)
            loadExternalBeans = false
            reloadingEnabled = false
        }

        @Override
        protected void customizeGrailsApplication(GrailsApplication grailsApplication) {
            customizeGrailsApplicationClosure?.call(grailsApplication)
        }

        @Override
        void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
            super.postProcessBeanDefinitionRegistry(registry)
            // Where an application's registrar drains: after the DSL, so it wins a name conflict with doWithSpring
            if (beanRegistrar != null) {
                new BeanRegistryAdapter(registry, applicationContext, applicationContext.environment, beanRegistrar.getClass())
                        .register(beanRegistrar)
            }
            PropertySourcesPlaceholderConfigurer propertySourcePlaceholderConfigurer  = (PropertySourcesPlaceholderConfigurer) grailsApplication.mainContext.getBean('grailsPlaceholderConfigurer')
            propertySourcePlaceholderConfigurer.order = Ordered.HIGHEST_PRECEDENCE
            propertySourcePlaceholderConfigurer.localOverride = localOverride
        }
    }
}

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
package grails.boot.config;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanRegistryAdapter;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.convert.support.ConfigurableConversionService;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.Resource;
import org.springframework.util.ClassUtils;

import grails.core.DefaultGrailsApplication;
import grails.core.GrailsApplication;
import grails.core.GrailsApplicationClass;
import grails.plugins.DefaultGrailsPluginManager;
import grails.plugins.GrailsPlugin;
import grails.plugins.GrailsPluginManager;
import grails.util.Environment;
import grails.util.Holders;
import org.apache.grails.core.plugins.PluginDiscovery;
import org.grails.config.NavigableMap;
import org.grails.config.PropertySourcesConfig;
import org.grails.spring.DefaultRuntimeSpringConfiguration;
import org.grails.spring.RuntimeSpringConfiguration;

/**
 * Runs the plugin bean-registration phase of the Grails lifecycle <em>before</em> Spring Boot's
 * auto-configuration is processed, so that beans contributed by plugins via {@code doWithSpring}
 * are already present in the registry when Boot evaluates its {@code @ConditionalOnMissingBean}
 * guards — auto-configured defaults then back off in favour of the plugin beans, without any
 * override or removal afterwards.
 *
 * <p>It is added to the context programmatically (see {@link GrailsPluginLifecycleInitializer}), so its
 * {@code postProcessBeanDefinitionRegistry} runs ahead of Boot's {@code ConfigurationClassPostProcessor}
 * (which expands the {@code @AutoConfiguration} imports). Manually-added
 * {@code BeanDefinitionRegistryPostProcessor}s always run before registry-discovered ones; Spring does
 * not sort manually-added post-processors by {@code getOrder()}, so this class deliberately does not
 * implement {@code PriorityOrdered}.
 *
 * <p>This phase builds the one true {@link GrailsApplication} and {@link GrailsPluginManager}: plugins
 * are discovered via the promoted {@link PluginDiscovery} singleton and instantiated exactly once.
 * Artefact discovery also happens here, mirroring
 * {@code GrailsApplicationPostProcessor.performGrailsInitializationSequence()}, because core plugins
 * (controllers, services, interceptors) iterate {@code grailsApplication} artefacts inside their
 * {@code doWithSpring} closures. Application classes are resolved from the source classes stashed by
 * {@link grails.boot.GrailsApp} (see {@link #APPLICATION_SOURCE_CLASSES_BEAN_NAME}) and scanned with the
 * same logic {@link GrailsAutoConfiguration#classes()} uses; when the application was not started
 * through {@code GrailsApp} the phase proceeds without application classes.
 *
 * <p>Once complete, the {@code grailsApplication} and {@code pluginManager} singletons are promoted to
 * the bean factory together with the {@link #EARLY_REGISTRATION_COMPLETE_BEAN_NAME} marker, so
 * {@link GrailsApplicationPostProcessor} reuses them instead of rebuilding and skips the already-drained
 * plugin runtime configuration.
 *
 * @since 8.0
 */
public class GrailsEarlyPluginRegistrationPostProcessor
        implements BeanDefinitionRegistryPostProcessor, ApplicationListener<ContextRefreshedEvent> {

    /**
     * Name of the {@code Class[]} singleton under which {@link grails.boot.GrailsApp} stashes the
     * application source classes so this phase can perform early artefact discovery.
     */
    public static final String APPLICATION_SOURCE_CLASSES_BEAN_NAME = "grailsApplicationSourceClasses";

    /**
     * Name of the marker singleton registered once this phase has completed, checked by
     * {@link GrailsApplicationPostProcessor} to reuse the promoted singletons and skip the
     * already-performed lifecycle steps. Always checked on the local bean factory only.
     */
    public static final String EARLY_REGISTRATION_COMPLETE_BEAN_NAME = "grailsEarlyPluginRegistrationComplete";

    private static final Logger LOG = LoggerFactory.getLogger(GrailsEarlyPluginRegistrationPostProcessor.class);

    private final ConfigurableApplicationContext applicationContext;

    public GrailsEarlyPluginRegistrationPostProcessor(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        // Check the LOCAL singleton only — a parent context's discovery must not cause us to re-run
        // the early phase in a child context (containsBean/getBean would delegate to the parent).
        Object discovery = applicationContext.getBeanFactory().getSingleton(PluginDiscovery.BEAN_NAME);
        if (!(discovery instanceof PluginDiscovery pluginDiscovery)) {
            // No plugin discovery promoted to this context (e.g. unit-test slice) — nothing to do.
            return;
        }

        Environment.setInitializing(true);

        DefaultGrailsApplication grailsApplication = new DefaultGrailsApplication();
        grailsApplication.setConfig(buildConfig());
        grailsApplication.setApplicationContext(applicationContext);
        grailsApplication.setMainContext(applicationContext);

        DefaultGrailsPluginManager pluginManager = new DefaultGrailsPluginManager(grailsApplication, pluginDiscovery);
        pluginManager.loadPlugins();
        pluginManager.setApplicationContext(applicationContext);

        pluginManager.doArtefactConfiguration();
        grailsApplication.initialise();
        // register plugin provided classes first, this gives the opportunity
        // for application classes to override those provided by a plugin
        pluginManager.registerProvidedArtefacts(grailsApplication);
        registerApplicationArtefacts(grailsApplication, registry);

        RuntimeSpringConfiguration springConfig = new DefaultRuntimeSpringConfiguration();
        pluginManager.doRuntimeConfiguration(springConfig);
        springConfig.registerBeansWithRegistry(registry);
        applyBeanRegistrars(pluginManager, registry);

        ConfigurableListableBeanFactory beanFactory = applicationContext.getBeanFactory();
        beanFactory.registerSingleton(GrailsApplication.APPLICATION_ID, grailsApplication);
        beanFactory.registerSingleton(GrailsPluginManager.BEAN_NAME, pluginManager);
        beanFactory.registerSingleton(EARLY_REGISTRATION_COMPLETE_BEAN_NAME, Boolean.TRUE);
        Holders.setGrailsApplication(grailsApplication);

        // GrailsApplicationPostProcessor resets the initializing flag on refresh, but it is not
        // present in every context that runs this phase — reset here as well so the flag (a system
        // property) does not leak once the context is up.
        applicationContext.addApplicationListener(this);
    }

    /**
     * Applies the {@link BeanRegistrar} exposed by each enabled plugin through
     * {@link grails.core.GrailsApplicationLifeCycle#beanRegistrar()}, in plugin order, using the
     * same adapter Spring uses for {@code GenericApplicationContext.register(BeanRegistrar...)}.
     * Runs after the {@code doWithSpring} drain so registrar beans win any name conflicts with the
     * deprecated DSL.
     */
    private void applyBeanRegistrars(DefaultGrailsPluginManager pluginManager, BeanDefinitionRegistry registry) {
        String[] activeProfiles = applicationContext.getEnvironment().getActiveProfiles();
        for (GrailsPlugin plugin : pluginManager.getAllPlugins()) {
            if (!plugin.supportsCurrentScopeAndEnvironment() || !plugin.isEnabled(activeProfiles)) {
                continue;
            }
            BeanRegistrar registrar = plugin.getBeanRegistrar();
            if (registrar != null) {
                new BeanRegistryAdapter(registry, applicationContext.getBeanFactory(),
                        applicationContext.getEnvironment(), registrar.getClass()).register(registrar);
            }
        }
    }

    private void registerApplicationArtefacts(DefaultGrailsApplication grailsApplication, BeanDefinitionRegistry registry) {
        Class<?>[] sources = resolveApplicationSourceClasses(registry);
        if (sources.length == 0) {
            LOG.debug("No application source classes available — proceeding without early application artefact discovery");
            return;
        }
        for (Class<?> source : sources) {
            for (Class<?> applicationClass : ApplicationClassScanner.scanApplicationClasses(source)) {
                grailsApplication.addArtefact(applicationClass);
            }
        }
    }

    /**
     * Resolves the application source classes to scan for artefacts, preferring the singleton
     * stashed by {@link grails.boot.GrailsApp}. When the application was started through a plain
     * {@code SpringApplication} no stash exists, but the primary sources are already registered as
     * bean definitions by the time this phase runs, so any {@link GrailsApplicationClass} among
     * them is recovered from the registry instead.
     */
    private Class<?>[] resolveApplicationSourceClasses(BeanDefinitionRegistry registry) {
        Object stashedSources = applicationContext.getBeanFactory().getSingleton(APPLICATION_SOURCE_CLASSES_BEAN_NAME);
        if (stashedSources instanceof Class<?>[] sources) {
            return sources;
        }
        List<Class<?>> sources = new ArrayList<>();
        for (String beanDefinitionName : registry.getBeanDefinitionNames()) {
            String beanClassName = registry.getBeanDefinition(beanDefinitionName).getBeanClassName();
            if (beanClassName == null) {
                continue;
            }
            try {
                Class<?> beanClass = ClassUtils.forName(beanClassName, applicationContext.getClassLoader());
                if (GrailsApplicationClass.class.isAssignableFrom(beanClass)) {
                    sources.add(beanClass);
                }
            } catch (ClassNotFoundException | LinkageError ignored) {
                // not resolvable here — cannot be an application class
            }
        }
        return sources.toArray(new Class<?>[0]);
    }

    /**
     * Builds the {@link PropertySourcesConfig} that backs {@code grailsApplication.config} in this
     * phase, registering the same conversion-service converters that
     * {@code GrailsApplicationPostProcessor.loadApplicationConfig} registers for the main lifecycle.
     * This gives {@code doWithSpring} closures parity when reading config — null-safe navigation of
     * missing paths and {@code String -> Resource} coercion — not just scalar
     * {@code getProperty(...)} access.
     */
    private PropertySourcesConfig buildConfig() {
        ConfigurableEnvironment environment = applicationContext.getEnvironment();
        ConfigurableConversionService conversionService = null;
        if (environment instanceof AbstractEnvironment) {
            conversionService = ((AbstractEnvironment) environment).getConversionService();
            conversionService.addConverter(String.class, Resource.class, applicationContext::getResource);
            conversionService.addConverter(NavigableMap.NullSafeNavigator.class, String.class, source -> null);
            conversionService.addConverter(NavigableMap.NullSafeNavigator.class, Object.class, source -> null);
        }
        PropertySourcesConfig config = new PropertySourcesConfig(environment.getPropertySources());
        if (conversionService != null) {
            config.setConversionService(conversionService);
        }
        return config;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (event.getApplicationContext() == applicationContext) {
            Environment.setInitializing(false);
        }
    }
}

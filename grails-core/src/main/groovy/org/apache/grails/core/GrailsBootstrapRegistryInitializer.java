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
package org.apache.grails.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.boot.bootstrap.BootstrapRegistry;
import org.springframework.boot.bootstrap.BootstrapRegistryInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import grails.config.Settings;
import grails.util.GrailsUtil;
import org.apache.grails.core.plugins.DefaultPluginDiscovery;
import org.apache.grails.core.plugins.PluginDiscovery;
import org.grails.exceptions.reporting.DefaultStackTraceFilterer;
import org.grails.exceptions.reporting.StackTraceFilterer;

/**
 * Registers the {@link PluginDiscovery} in the Spring Boot Bootstrap context so it can be accessed during
 * the early lifecycle of the application & later promoted to actual bean.
 *
 * <p>This ensures that both the early-lifecycle
 * {@link grails.boot.config.GrailsEnvironmentPostProcessor} and the
 * later-lifecycle {@link grails.plugins.DefaultGrailsPluginManager} can
 * access the same discovered, filtered, and sorted set of plugins.</p>
 *
 * <p>Also resolves the configured {@link StackTraceFilterer} from the environment and installs it in
 * {@link GrailsUtil} before the {@code ApplicationContext} refreshes, then promotes the same instance
 * as an {@code ApplicationContext} singleton bean. Doing this here — rather than from a bean's setter,
 * such as {@code GrailsExceptionResolver.setGrailsApplication()} — means every app type is covered
 * (not just apps that wire an exception resolver), and a startup failure honours the configured
 * filterer too.</p>
 *
 * <p>This class is registered via {@code META-INF/spring.factories} under the
 * {@code org.springframework.boot.bootstrap.BootstrapRegistryInitializer} key.</p>
 *
 * @since 7.1
 */
public class GrailsBootstrapRegistryInitializer implements BootstrapRegistryInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(GrailsBootstrapRegistryInitializer.class);

    @Override
    public void initialize(BootstrapRegistry registry) {
        LOG.debug("Registering GrailsPluginDiscovery in BootstrapRegistry");
        registry.register(PluginDiscovery.class, context -> new DefaultPluginDiscovery());

        // Promote the GrailsPluginDiscovery singleton to the ApplicationContext
        // so that later-lifecycle components (e.g., DefaultGrailsPluginManager)
        // can access it. This fires after the context is prepared but before
        // refresh(), so the bean is available during the full Spring lifecycle.
        registry.addCloseListener(event -> {
            PluginDiscovery discovery = event.getBootstrapContext()
                    .get(PluginDiscovery.class);
            event.getApplicationContext()
                    .getBeanFactory()
                    .registerSingleton(PluginDiscovery.BEAN_NAME, discovery);
            LOG.debug("Promoted GrailsPluginDiscovery to ApplicationContext as '{}'", PluginDiscovery.BEAN_NAME);
        });

        // Resolve the configured StackTraceFilterer from the environment (same two keys
        // GrailsExceptionResolver honours) and install + promote it the same way, before refresh().
        registry.addCloseListener(event -> {
            ConfigurableApplicationContext applicationContext = event.getApplicationContext();
            StackTraceFilterer filterer = resolveConfiguredStackTraceFilterer(applicationContext.getEnvironment());
            GrailsUtil.initializeStackFilterer(filterer);
            applicationContext.getBeanFactory()
                    .registerSingleton(StackTraceFilterer.BEAN_NAME, filterer);
            LOG.debug("Promoted StackTraceFilterer to ApplicationContext as '{}'", StackTraceFilterer.BEAN_NAME);
        });
    }

    /**
     * Resolves a {@link StackTraceFilterer} from the given environment, honouring
     * {@link Settings#SETTING_LOGGING_STACKTRACE_FILTER_CLASS} and
     * {@link Settings#SETTING_LOG_FULL_STACKTRACE_ON_FILTER}.
     *
     * <p>Resolves the class name manually via {@link ClassUtils#forName} rather than
     * {@code environment.getProperty(key, Class.class)} — neither Spring's default conversion
     * service nor Spring Boot's {@code ApplicationConversionService} register a String-to-Class
     * converter, so that call would throw {@code ConverterNotFoundException} for a real class name.
     *
     * <p>Defensive: any failure reading config or instantiating the configured class falls back to
     * a plain {@link DefaultStackTraceFilterer}, matching the resolver's own fallback behaviour.
     */
    private StackTraceFilterer resolveConfiguredStackTraceFilterer(Environment environment) {
        Class<? extends StackTraceFilterer> filtererClass = DefaultStackTraceFilterer.class;
        String configuredClassName = environment.getProperty(Settings.SETTING_LOGGING_STACKTRACE_FILTER_CLASS);
        if (StringUtils.hasText(configuredClassName)) {
            try {
                filtererClass = ClassUtils.forName(configuredClassName, getClass().getClassLoader())
                        .asSubclass(StackTraceFilterer.class);
            }
            catch (Throwable t) {
                LOG.warn("Problem loading configured StackTraceFilterer class [{}], falling back to default: {}",
                        configuredClassName, t.getMessage());
            }
        }
        boolean logFullStackTraceOnFilter = environment.getProperty(
                Settings.SETTING_LOG_FULL_STACKTRACE_ON_FILTER, Boolean.class, Boolean.TRUE);

        StackTraceFilterer filterer;
        try {
            filterer = BeanUtils.instantiateClass(filtererClass, StackTraceFilterer.class);
        }
        catch (Throwable t) {
            LOG.warn("Problem instantiating configured StackTraceFilterer [{}], falling back to default: {}",
                    filtererClass.getName(), t.getMessage());
            filterer = new DefaultStackTraceFilterer();
        }
        if (filterer instanceof DefaultStackTraceFilterer) {
            ((DefaultStackTraceFilterer) filterer).setLogFullStackTraceOnFilter(logFullStackTraceOnFilter);
        }
        return filterer;
    }
}

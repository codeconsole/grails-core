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

import groovy.transform.CompileStatic

import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.annotation.Bean
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

import grails.boot.config.tools.ClassPathScanner
import grails.config.Config
import grails.core.GrailsApplication
import grails.core.GrailsApplicationClass
import org.apache.grails.core.plugins.PluginDiscovery
import org.grails.spring.aop.autoproxy.GroovyAwareAutoProxyCreators

/**
 * A base class for configurations that bootstrap a Grails application
 *
 * @since 3.0
 * @author Graeme Rocher
 *
 */
@CompileStatic
// WARNING: Never add logging to the source of this class, early initialization causes problems
class GrailsAutoConfiguration implements GrailsApplicationClass, ApplicationContextAware {

    static {
        // CoreGrailsPlugin does this again when it registers the creator, which is what covers a
        // Spring Boot application that uses Grails modules without extending this class
        GroovyAwareAutoProxyCreators.registerWithAopConfigUtils()
    }

    ApplicationContext applicationContext

    /**
     * @return A post processor that uses the {@link grails.plugins.GrailsPluginManager} to configure the {@link org.springframework.context.ApplicationContext}
     */
    @Bean
    GrailsApplicationPostProcessor grailsApplicationPostProcessor(PluginDiscovery pluginDiscovery) {
        return new GrailsApplicationPostProcessor(this, applicationContext, pluginDiscovery, classes() as Class[])
    }

    /**
     * @return The classes that constitute the Grails application
     */
    Collection<Class> classes() {
        if (limitScanningToApplication()) {
            return ApplicationArtefactScanner.scanApplicationClasses(getClass(), packageNames())
        }

        Collection<Class> classes = new HashSet()
        classes.addAll(new ClassPathScanner().scan(new PathMatchingResourcePatternResolver(applicationContext), packageNames()))
        classes.addAll(ApplicationArtefactScanner.loadTransformedClasses(getClass().classLoader))
        return classes
    }

    /**
     * Whether classpath scanning should be limited to the application and not dependent JAR files. Users can override this method to enable more broad scanning
     * at the cost of startup time.
     *
     * @return True if scanning should be limited to the application and should not include dependant JAR files
     */
    protected boolean limitScanningToApplication() {
        return true
    }

    /**
     * @return The packages to scan
     */
    Collection<Package> packages() {
        def thisPackage = getClass().package
        thisPackage ? [ thisPackage ] : new ArrayList<Package>()
    }

    /**
     * @return The package names to scan. Delegates to {@link #packages()} by default
     */
    Collection<String> packageNames() {
        packages().collect { Package p -> p.name }
    }

    @Override
    Closure doWithSpring() { null }

    @Override
    void doWithDynamicMethods() {
        // no-op
    }

    @Override
    void doWithApplicationContext() {
        // no-op
    }

    @Override
    void onConfigChange(Map<String, Object> event) {
        // no-op
    }

    @Override
    void onStartup(Map<String, Object> event) {
        // no-op
    }

    @Override
    void onShutdown(Map<String, Object> event) {
        // no-op
    }

    GrailsApplication getGrailsApplication() {
        applicationContext.getBean(GrailsApplication)
    }

    Config getConfig() {
        grailsApplication.config
    }

}

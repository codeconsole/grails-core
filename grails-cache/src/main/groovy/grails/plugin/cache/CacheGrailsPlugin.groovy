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

package grails.plugin.cache

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.cache.Cache

import grails.plugins.Plugin
import org.grails.plugin.cache.GrailsCacheManager

/**
 * Configures the cache plugin.
 *
 * <p>Every bean is contributed as auto-configuration so that one supplied by the application or
 * another plugin — for example a cache-provider plugin's {@code grailsCacheManager} — makes the
 * default back off cleanly instead of triggering a bean-definition override. The whole set is gated
 * on {@code grails.cache.enabled}.</p>
 */
@Slf4j
@CompileStatic
@AutoConfiguration
@ConditionalOnBooleanProperty(name = 'grails.cache.enabled', matchIfMissing = true)
class CacheGrailsPlugin extends Plugin {

    def grailsVersion = '8.0.0-SNAPSHOT > *'
    def observe = ['controllers', 'services']
    def loadAfter = ['controllers', 'services']
    def authorEmail = 'brownj@objectcomputing.com'
    def description = 'Grails Cache Plugin'

    def pluginExcludes = [
            '**/com/demo/**',
            'grails-app/views/**',
            '**/*.gsp'
    ]

    private boolean isCachingEnabled() {
        config.getProperty('grails.cache.enabled', Boolean, true)
    }

    def beans = {
        field('cacheManagerType', String).value('grails.cache.cacheManager', '')

        bean(GrailsCacheAdminService)

        // CachePluginConfiguration derives cachePluginConfiguration, so the contractual name is stated.
        bean('grailsCacheConfiguration', CachePluginConfiguration)

        bean(CustomCacheKeyGenerator).conditionalOnMissingBeanName()

        bean(GrailsCacheManager).conditionalOnMissingBeanName() { CachePluginConfiguration grailsCacheConfiguration ->
            if (cacheManagerType == 'GrailsConcurrentLinkedMapCacheManager') {
                return new GrailsConcurrentLinkedMapCacheManager(configuration: grailsCacheConfiguration)
            }
            new GrailsConcurrentMapCacheManager(configuration: grailsCacheConfiguration)
        }
    }

    @CompileStatic
    void doWithApplicationContext() {
        if (cachingEnabled) {
            CachePluginConfiguration pluginConfiguration = applicationContext.getBean('grailsCacheConfiguration', CachePluginConfiguration)
            GrailsCacheManager grailsCacheManager = applicationContext.getBean('grailsCacheManager', GrailsCacheManager)

            if (pluginConfiguration.clearAtStartup) {
                for (String cacheName in grailsCacheManager.cacheNames) {
                    log.info('Clearing cache {}', cacheName)
                    Cache cache = grailsCacheManager.getCache(cacheName)
                    cache.clear()
                }
            }

            List<String> defaultCaches = ['grailsBlocksCache', 'grailsTemplatesCache']
            for (name in defaultCaches) {
                if (!grailsCacheManager.cacheExists(name)) {
                    grailsCacheManager.getCache(name)
                }
            }
        }
    }
}

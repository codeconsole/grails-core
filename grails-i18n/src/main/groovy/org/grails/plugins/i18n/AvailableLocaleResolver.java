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
package org.grails.plugins.i18n;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Discovers the locales an application is actually translated into by scanning the
 * classpath for {@code messages_*.properties} resource bundles.
 *
 * <p>Unlike {@link java.util.Locale#getAvailableLocales()} (which returns every locale
 * the JVM knows about), this returns only the locales that have a matching message
 * bundle plus the configured default locale, making it suitable for driving a language
 * selector. The result is sorted by each locale's display name in its own language and
 * cached; {@link #clearCache()} forces a re-scan (used when bundles change in development).
 *
 * @since 8.0.0
 */
public class AvailableLocaleResolver {

    private static final Logger log = LoggerFactory.getLogger(AvailableLocaleResolver.class);

    private static final String MESSAGES_PREFIX = "messages_";

    private static final String PROPERTIES_SUFFIX = ".properties";

    private static final String LOCATION_PATTERN = "classpath*:" + MESSAGES_PREFIX + "*" + PROPERTIES_SUFFIX;

    private final ResourcePatternResolver resourcePatternResolver;

    private final Locale defaultLocale;

    private volatile List<Locale> cachedLocales;

    /**
     * @param classLoader the class loader whose classpath is scanned for message bundles
     * @param defaultLocale the locale of the base {@code messages.properties} bundle,
     * always included in the result (may be {@code null} to include none)
     */
    public AvailableLocaleResolver(ClassLoader classLoader, Locale defaultLocale) {
        this.resourcePatternResolver = new PathMatchingResourcePatternResolver(classLoader);
        this.defaultLocale = defaultLocale;
    }

    /**
     * @return an unmodifiable, display-name-sorted list of the locales the application is
     * translated into. Computed once and cached until {@link #clearCache()} is called.
     */
    public List<Locale> getAvailableLocales() {
        List<Locale> locales = this.cachedLocales;
        if (locales == null) {
            synchronized (this) {
                locales = this.cachedLocales;
                if (locales == null) {
                    locales = computeAvailableLocales();
                    this.cachedLocales = locales;
                }
            }
        }
        return locales;
    }

    /**
     * Discards the cached list so the next {@link #getAvailableLocales()} re-scans the classpath.
     */
    public void clearCache() {
        this.cachedLocales = null;
    }

    private List<Locale> computeAvailableLocales() {
        Set<Locale> locales = new LinkedHashSet<>();
        if (this.defaultLocale != null && !this.defaultLocale.getLanguage().isEmpty()) {
            locales.add(this.defaultLocale);
        }
        try {
            for (Resource resource : this.resourcePatternResolver.getResources(LOCATION_PATTERN)) {
                String filename = resource.getFilename();
                if (filename == null || !filename.startsWith(MESSAGES_PREFIX) || !filename.endsWith(PROPERTIES_SUFFIX)) {
                    continue;
                }
                String code = filename.substring(MESSAGES_PREFIX.length(), filename.length() - PROPERTIES_SUFFIX.length());
                if (code.isEmpty()) {
                    continue;
                }
                Locale locale = Locale.forLanguageTag(code.replace('_', '-'));
                if (!locale.getLanguage().isEmpty()) {
                    locales.add(locale);
                }
            }
        }
        catch (IOException ex) {
            log.warn("Unable to resolve available locales from '{}*{}' message bundles: {}",
                    MESSAGES_PREFIX, PROPERTIES_SUFFIX, ex.getMessage());
        }
        List<Locale> sorted = new ArrayList<>(locales);
        sorted.sort(Comparator.comparing((Locale locale) -> locale.getDisplayName(locale)));
        return Collections.unmodifiableList(sorted);
    }

}

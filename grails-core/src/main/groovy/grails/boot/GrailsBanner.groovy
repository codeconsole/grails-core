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
package grails.boot

import java.lang.management.ManagementFactory

import groovy.transform.CompileStatic
import groovy.transform.MapConstructor
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType

import org.springframework.boot.Banner
import org.springframework.boot.ansi.Ansi8BitColor
import org.springframework.boot.ansi.AnsiColor
import org.springframework.boot.ansi.AnsiElement
import org.springframework.boot.ansi.AnsiOutput
import org.springframework.boot.SpringBootVersion
import org.springframework.aot.AotDetector
import org.springframework.core.NativeDetector
import org.springframework.core.SpringVersion
import org.springframework.core.env.Environment
import org.springframework.core.io.ClassPathResource

import grails.util.BuildSettings

/**
 * The default Grails application banner.
 *
 * @since 7.1
 */
@CompileStatic
@MapConstructor(noArg = true)
class GrailsBanner implements Banner {

    private static final int FALLBACK_BANNER_WIDTH = 0
    private static final String DEFAULT_BANNER_FILE = 'grails-banner.txt'

    private static final String ART_COLOR_PROPERTY = 'grails.banner.art.color'

    private static final String MARK_DISPLAY_PROPERTY = 'grails.banner.mark.display'

    private static final String MARK_TEXT_PROPERTY = 'grails.banner.mark.text'

    private static final String MARK_COLOR_PROPERTY = 'grails.banner.mark.color'

    /** Bright yellow, so the mark reads as its own thing rather than the last line of the art. */
    private static final String DEFAULT_MARK_COLOR = '226'

    /** What an application was started as, strongest first. */
    private static final String NATIVE_MARK = 'NATIVE'
    private static final String CACHE_MARK = 'AOT CACHE'
    private static final String AOT_MARK = 'AOT'

    /** One of the 256 colours a terminal offers: the amber the framework is shown in. */
    private static final String DEFAULT_ART_COLOR = '214'

    private static final String NO_COLOR = 'none'

    String bannerFile = DEFAULT_BANNER_FILE
    int bannerPaddingTop = 1
    int bannerPaddingBottom = 1
    int artPaddingBottom = 0

    /**
     * Prints the banner to the specified PrintStream.
     *
     * @param environment the current environment
     * @param sourceClass the source class
     * @param out the PrintStream to print to
     */
    @Override
    void printBanner(Environment environment, Class<?> sourceClass, PrintStream out) {

        def bannerWidth = FALLBACK_BANNER_WIDTH

        bannerPaddingTop.times { out.println() }
        if (shouldDisplayArt(environment)) {
            def art = createBannerArt(environment)
            // measured before colouring, so the escapes do not count towards the width the
            // versions below are centred on
            bannerWidth = longestLineLength(art) ?: FALLBACK_BANNER_WIDTH
            out.println(colour(art, environment))
            artPaddingBottom.times { out.println() }
        }
        printMark(environment, out, bannerWidth)
        if (shouldDisplayVersions(environment)) {
            createVersionsFormatter().format(createBannerVersions(environment), bannerWidth)
                    .forEach { out.println(it) }
        }
        bannerPaddingBottom.times { out.println() }
    }

    /**
     * Marks how the application was started, centred under the art and above the versions.
     *
     * <p>Only where it is worth saying: an image, a JVM given a cache to read, or one running bean
     * definitions that were generated. An ordinary start says nothing.</p>
     *
     * <p>Written plainly, and deliberately: a banner is printed on the thread that is starting the
     * application, which cannot get on until this returns. Anything drawn over time here is time the
     * application is not starting, and an image that starts in two thirds of a second should not
     * spend a third of it on its own announcement.</p>
     */
    protected void printMark(Environment environment, PrintStream out, int bannerWidth) {
        if (!environment.getProperty(MARK_DISPLAY_PROPERTY, Boolean, true)) {
            return
        }
        String mark = resolveMark(environment)
        if (!mark) {
            return
        }
        String label = spaced(mark)
        String indent = ' ' * Math.max(0, (bannerWidth - label.length()).intdiv(2))
        AnsiElement colour = resolveMarkColour(environment)
        out.println(indent + (colour == null ? label : AnsiOutput.toString(colour, label, AnsiColor.DEFAULT)))
    }

    /**
     * What to say, strongest first, or nothing where an application started the ordinary way.
     *
     * <p>An image has already done all of it. A cache means the JDK was handed what a previous run
     * worked out. Generated bean definitions are the smaller half of the same idea, and worth
     * saying on their own because an application can be run either with them or without.</p>
     */
    protected String resolveMark(Environment environment) {
        String configured = environment.getProperty(MARK_TEXT_PROPERTY, String)
        if (configured != null) {
            return configured.trim() ?: null
        }
        if (isNativeImage()) {
            return NATIVE_MARK
        }
        if (readsAotCache()) {
            return CACHE_MARK
        }
        AotDetector.useGeneratedArtifacts() ? AOT_MARK : null
    }

    /**
     * Whether the JDK was given a cache to read.
     *
     * <p>Asked of the arguments the JVM was started with rather than of the cache: a JVM handed one
     * it cannot use declines it and starts as it would have anyway, and this is the banner rather
     * than a diagnostic.</p>
     */
    protected boolean readsAotCache() {
        ManagementFactory.runtimeMXBean.inputArguments.any { String argument ->
            argument.startsWith('-XX:AOTCache=') || argument.startsWith('-XX:AOTMode=')
        }
    }

    /** Spaced so it reads as a mark rather than a word. */
    private static String spaced(String word) {
        word.toUpperCase().toCharArray().join(' ')
    }

    /** Whether this is running as an image. A seam, so the mark can be covered without being one. */
    protected boolean isNativeImage() {
        NativeDetector.inNativeImage()
    }

    /**
     * The art in the configured colour, or as it stands where none is wanted.
     *
     * <p>{@link AnsiOutput} writes the escapes only where colour has been enabled, so this is the
     * same string on a terminal that cannot colour, in a redirected log, and wherever an application
     * has turned colour off.</p>
     */
    protected String colour(String art, Environment environment) {
        AnsiElement colour = resolveArtColour(environment)
        colour == null ? art : AnsiOutput.toString(colour, art, AnsiColor.DEFAULT)
    }

    /**
     * The colour to show the art in, read from {@code grails.banner.art.color}.
     *
     * <p>Takes a number for one of the 256 colours a terminal offers, a name for one of the eight
     * it has always had ({@code red}, {@code bright_blue}), or {@code none} to leave the art as it
     * stands. A value that is neither falls back to the default rather than failing to start over
     * the colour of a banner.</p>
     */
    protected AnsiElement resolveArtColour(Environment environment) {
        resolveColour(environment, ART_COLOR_PROPERTY, DEFAULT_ART_COLOR)
    }

    /**
     * The colour to show the mark in, read from {@code grails.banner.mark.color}.
     *
     * <p>Its own colour rather than the art's, and brighter, so that what an application was started
     * as is not read as the last line of the drawing above it. Takes the same values as
     * {@code grails.banner.art.color}.</p>
     */
    protected AnsiElement resolveMarkColour(Environment environment) {
        resolveColour(environment, MARK_COLOR_PROPERTY, DEFAULT_MARK_COLOR)
    }

    private AnsiElement resolveColour(Environment environment, String property, String fallback) {
        String configured = environment.getProperty(property, String, fallback)
        if (!configured || configured.equalsIgnoreCase(NO_COLOR)) {
            return null
        }
        if (configured.isInteger()) {
            int code = configured.toInteger()
            // A terminal offers 256 of them, and anything else writes an escape it does not
            // understand -- which shows as the escape itself, printed into the banner.
            return code in 0..255 ? Ansi8BitColor.foreground(code) : defaultColour(fallback)
        }
        try {
            return AnsiColor.valueOf(configured.toUpperCase())
        }
        catch (IllegalArgumentException ignored) {
            return defaultColour(fallback)
        }
    }

    private static AnsiElement defaultColour(String fallback) {
        Ansi8BitColor.foreground(fallback.toInteger())
    }

    /**
     * Creates the banner art to be displayed.
     *
     * @param environment the current environment
     * @return the banner art
     */
    protected String createBannerArt(Environment environment) {
        if (bannerFile != DEFAULT_BANNER_FILE) {
            // Banner file was programmatically set, use it directly
            def customBannerResource = new ClassPathResource(bannerFile)
            if (customBannerResource.exists()) {
                return customBannerResource.inputStream.text
            }
        } else {
            // Use configured banner file or default
            def configBannerFile = environment.getProperty('grails.banner.art.file', String, DEFAULT_BANNER_FILE)
            def bannerResource = new ClassPathResource(configBannerFile)
            if (bannerResource.exists()) {
                return bannerResource.inputStream.text
            }
        }
        return ''
    }

    /**
     * Creates a map of versions to be displayed in the banner.
     *
     * @param env the current env
     * @return a map of version labels to version values
     */
    @SuppressWarnings('GrMethodMayBeStatic')
    protected Map<String,String> createBannerVersions(Environment env) {
        def defaultIncluded = (DefaultVersionOption.values()).collect { it.key }
        def sortOrder = findConfiguredVersions(env, 'grails.banner.versions.order') { it in VersionOption.values()*.key }
        def configExcluded = findConfiguredVersions(env, 'grails.banner.versions.exclude') { it in DefaultVersionOption.values()*.key }
        def configIncluded = findConfiguredVersions(env, 'grails.banner.versions.include') { it in OptionalVersionOption.values()*.key }
        def includedVersions = defaultIncluded
                .tap { removeAll(configExcluded) }
                .tap { addAll(configIncluded) }
                .unique()
        if (sortOrder) {
            includedVersions.sort { a, b ->
                def indexA = sortOrder.indexOf(a)
                def indexB = sortOrder.indexOf(b)
                if (indexA == -1 && indexB == -1) {
                    return 0
                } else if (indexA == -1) {
                    return 1
                } else if (indexB == -1) {
                    return -1
                } else {
                    return indexA <=> indexB
                }
            }
        }
        includedVersions.collectEntries { key ->
            switch (VersionOption.fromString(key)) {
                case VersionOption.APP:
                    [(env.getProperty('info.app.name') ?: 'app'): env.getProperty('info.app.version') ?: 'unknown']
                    break
                case VersionOption.JVM:
                    ['JVM': System.getProperty('java.vendor') + ' ' + System.getProperty('java.version')]
                    break
                case VersionOption.GRAILS:
                    ['Grails': BuildSettings.grailsVersion]
                    break
                case VersionOption.GROOVY:
                    ['Groovy': GroovySystem.version]
                    break
                case VersionOption.SPRING_BOOT:
                    ['Spring Boot': SpringBootVersion.version]
                    break
                case VersionOption.SPRING:
                    ['Spring': SpringVersion.version]
                    break
                case VersionOption.SPRING_SECURITY:
                    ['Spring Security': findVersion('org.springframework.security.core.SpringSecurityCoreVersion')]
                    break
                case VersionOption.TOMCAT:
                    ['Tomcat': findVersion('org.apache.catalina.util.ServerInfo')]
                    break
                case VersionOption.JETTY:
                    ['Jetty': findVersion('org.eclipse.jetty.util.Jetty')]
                    break
                case VersionOption.UNDERTOW:
                    ['Undertow': findVersion('io.undertow.Undertow')]
                    break
                default:
                    null
            }
        } as Map<String, String>
    }

    /**
     * Finds the implementation version of the specified class.
     *
     * @param className the fully qualified class name
     * @return the implementation version, or 'unknown' if not found
     */
    /**
     * The version a library records in the manifest of the jar it ships in.
     *
     * <p>Loaded without being initialised. A version is read <em>about</em> a library rather than
     * <em>from</em> it, and running a static initialiser to find one lets the library do whatever it
     * does on the way -- Spring Security logs a line of its own from there, which arrived in the
     * middle of the banner, between the mark and the very versions it was being read for. The
     * manifest is attached to the package when the class is loaded, and loading is all this
     * needs.</p>
     */
    protected static String findVersion(String className) {
        try {
            Package pkg = Class.forName(className, false, GrailsBanner.classLoader).package
            return pkg?.implementationVersion ?: 'unknown'
        } catch (ClassNotFoundException ignore) {
            return 'unknown'
        }
    }

    /**
     * Finds the configured versions from the environment.
     *
     * @param env the current environment
     * @param propertyName the property name to look for
     * @param filter the filter closure
     * @return a list of configured versions
     */
    private static List<String> findConfiguredVersions(
            Environment env,
            String propertyName,
            @ClosureParams(
                    value = SimpleType,
                    options = ['java.lang.String']
            ) Closure<Boolean> filter) {
        env.getProperty(propertyName, List<String>, [] as List<String>).findAll(filter)
    }

    /**
     * Determines whether to display the banner art.
     *
     * @param env the current environment
     * @return true if the banner art should be displayed, false otherwise
     */
    @SuppressWarnings('GrMethodMayBeStatic')
    protected boolean shouldDisplayArt(Environment env) {
        env.getProperty('grails.banner.art.display', Boolean, true)
    }

    /**
     * Determines whether to display the version information.
     *
     * @param env the current environment
     * @return true if the version information should be displayed, false otherwise
     */
    @SuppressWarnings('GrMethodMayBeStatic')
    protected boolean shouldDisplayVersions(Environment env) {
        env.getProperty('grails.banner.versions.display', Boolean, true)
    }

    /**
     * Creates the versions formatter to format the version information.
     *
     * @return the versions formatter
     */
    protected VersionsFormatter createVersionsFormatter() {
        new DefaultVersionFormatter()
    }

    /**
     * Calculates the length of the longest line in the given text.
     *
     * @param text the text to analyze
     * @return the length of the longest line
     */
    private static int longestLineLength(String text) {
        text.readLines()*.size()?.max() ?: 0
    }

    /**
     * Strategy interface for formatting version information
     * into printable lines.
     */
    @FunctionalInterface
    interface VersionsFormatter {

        /**
         * Formats the version information into a list of banner lines.
         *
         * @param versions An insertion-ordered map (e.g., LinkedHashMap)
         *                 mapping human-readable labels to version values.
         *                 The iteration order defines the order of the
         *                 formatted output.
         * @param bannerWidth Total banner width in characters
         * @return a list of lines to print, without line-termination characters
         */
        List<String> format(Map<String, String> versions, int bannerWidth)
    }

    /**
     * The default implementation of the VersionsFormatter.
     */
    @CompileStatic
    @MapConstructor(noArg = true)
    static class DefaultVersionFormatter implements VersionsFormatter {

        int margin = 4
        int maxItemsPerRow = 0 // 0 or negative = unlimited
        String itemSeparator = ' | '
        String pairSeparator = ': '

        /**
         * Formats the version information into centered lines that fit
         * within the banner width.
         */
        @Override
        List<String> format(Map<String, String> versions, int bannerWidth) {
            def columnWidth = bannerWidth - margin * 2
            List<String> rows = []
            def currentRow = new StringBuilder()
            def countInRow = 0

            versions.each { k, v ->
                String item = "$k${pairSeparator}$v"
                def proposedLength = currentRow.length() + (countInRow > 0 ? itemSeparator.size() : 0) + item.size()
                def wouldOverflow = (countInRow > 0 && proposedLength > columnWidth)
                def hitCountLimit = (maxItemsPerRow > 0 && countInRow >= maxItemsPerRow)

                if (wouldOverflow || hitCountLimit) {
                    rows << currentRow.center(bannerWidth)
                    currentRow.length = 0
                    countInRow = 0
                }

                if (currentRow.size() > 0) {
                    currentRow << itemSeparator
                }
                currentRow << item
                countInRow++
            }

            if (countInRow > 0) {
                rows << currentRow.center(bannerWidth)
            }

            return rows
        }
    }

    /**
     * Enumeration of supported version options.
     */
    @CompileStatic
    enum VersionOption {
        APP,
        JVM,
        GRAILS,
        GROOVY,
        SPRING_BOOT,
        SPRING,
        SPRING_SECURITY,
        TOMCAT,
        JETTY,
        UNDERTOW

        final String key

        VersionOption() {
            this.key = name().toLowerCase().replace('_', '-')
        }

        static VersionOption fromString(String value) {
            try {
                return valueOf(value.toUpperCase().replace('-', '_'))
            } catch (IllegalArgumentException ignore) {
                return null
            }
        }
    }

    /**
     * Enumeration of default version options.
     */
    @CompileStatic
    enum DefaultVersionOption {
        APP,
        JVM,
        GRAILS,
        GROOVY,
        SPRING_BOOT,
        SPRING

        final String key

        DefaultVersionOption() {
            this.key = name().toLowerCase().replace('_', '-')
        }
    }

    /**
     * Enumeration of optional version options.
     */
    @CompileStatic
    enum OptionalVersionOption {
        SPRING_SECURITY,
        TOMCAT,
        JETTY,
        UNDERTOW

        final String key

        OptionalVersionOption() {
            this.key = name().toLowerCase().replace('_', '-')
        }
    }
}

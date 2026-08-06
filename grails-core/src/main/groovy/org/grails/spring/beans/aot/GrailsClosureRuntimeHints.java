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
package org.grails.spring.beans.aot;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

import org.grails.aot.RegistrableTypes;

/**
 * Registers the framework closures Groovy dispatches through.
 *
 * <p>Calling a closure goes through {@code doCall}, and Groovy reads its parameter types
 * reflectively to choose an overload. In an ahead-of-time image those types are stripped unless
 * something asks for them, and the call fails where the closure is used rather than at start-up.
 * The framework ships thousands of closures across its plugins, so naming them individually would
 * be neither complete nor stable.</p>
 *
 * <p>They are found here instead, while the hints are being written. That happens during the build,
 * on an ordinary JVM with the full classpath, so scanning is available -- it is only the image that
 * cannot do it. A closure whose own bytecode does not resolve is skipped: the GSP compiler's Ant
 * task is on the compile classpath but Ant is not on the runtime one, and registering it would make
 * the image analysis parse a class whose supertype is absent, failing the build.</p>
 *
 * @since 8.0
 */
public class GrailsClosureRuntimeHints implements RuntimeHintsRegistrar {

    private static final Log logger = LogFactory.getLog(GrailsClosureRuntimeHints.class);

    /**
     * Where the framework's closures are found. The first two cover its own packages; the third
     * covers plugin descriptors, which a plugin may declare in any package of its choosing -- the
     * asset pipeline names its own {@code asset.pipeline}, and its bean definitions are closures
     * the container calls while the context is built.
     */
    private static final String[] PATTERNS = {
        ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + "grails/**/*_closure*.class",
        ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + "org/grails/**/*_closure*.class",
        ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + "**/*GrailsPlugin$*_closure*.class"
    };

    @Override
    public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
        ClassLoader loader = (classLoader != null) ? classLoader : ClassUtils.getDefaultClassLoader();
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(loader);
        MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory(resolver);
        int registered = 0;
        for (String pattern : PATTERNS) {
            Resource[] resources;
            try {
                resources = resolver.getResources(pattern);
            }
            catch (IOException ex) {
                logger.warn("Unable to scan for Grails closures matching " + pattern, ex);
                continue;
            }
            for (Resource resource : resources) {
                String className = classNameOf(metadataReaderFactory, resource);
                if (className != null && registrable(className, resource, loader)) {
                    hints.reflection().registerTypeIfPresent(loader, className,
                            MemberCategory.INVOKE_DECLARED_METHODS,
                            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
                    registered++;
                }
            }
        }
        logger.debug("Registered " + registered + " Grails closures for reflection");
    }

    /**
     * Whether the closure can be kept. Both questions are asked: the GSP compiler's task extends an
     * Ant type absent at run time and its closures reach it through invokedynamic, so nothing in
     * their own bytecode reveals it, while the JSP closures load cleanly and name an absent class in
     * a method body.
     */
    private boolean registrable(String className, Resource resource, ClassLoader loader) {
        if (!RegistrableTypes.loads(className, loader)) {
            return false;
        }
        try {
            return RegistrableTypes.referencesLoad(resource.getInputStream(), loader);
        }
        catch (IOException ex) {
            return false;
        }
    }

    @Nullable
    private String classNameOf(MetadataReaderFactory factory, Resource resource) {
        try {
            return factory.getMetadataReader(resource).getClassMetadata().getClassName();
        }
        catch (IOException | RuntimeException ex) {
            return null;
        }
    }

}

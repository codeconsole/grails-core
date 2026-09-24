/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.grails.gradle.plugin.scaffolding

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import grails.util.GrailsNameUtils
import groovy.transform.CompileStatic

/**
 * Names and models the pages {@link GenerateScaffoldedViewsTask} writes, exactly as the runtime
 * resolver names and models them.
 *
 * <p>This mirrors {@code org.apache.grails.scaffolding.ScaffoldedPages} and the model built by
 * {@code grails.codegen.model.ModelBuilder}, which the plugin cannot depend on. The two modules
 * are held together by the same test vector. A difference between them costs only
 * precompilation: the resolver looks for a name this did not write and expands the template
 * itself.</p>
 *
 * @since 8.0
 */
@CompileStatic
final class ScaffoldedPages {

    /** The directory under the views root that holds the pages, as the runtime resolver reads it. */
    static final String DIRECTORY = 'grails-scaffolded'

    /** Bytes of the digest kept in a page's name. */
    private static final int KEY_BYTES = 16

    private ScaffoldedPages() {
    }

    /**
     * The names a template is expanded with for a domain class, as {@code ModelBuilder} binds them
     * at runtime.
     */
    static Map<String, Object> model(String fullName) {
        String className = capitalize(GrailsNameUtils.getShortName(fullName))
        String propertyName = GrailsNameUtils.getPropertyName(fullName)
        String packageName = GrailsNameUtils.getPackageName(fullName)
        [className: className,
         fullName: fullName,
         propertyName: propertyName,
         modelName: propertyName,
         packageName: packageName,
         packagePath: packageName.replace('.' as char, File.separatorChar),
         simpleName: className,
         lowerCaseName: GrailsNameUtils.getScriptName(fullName)] as Map<String, Object>
    }

    /** Where, under the views root, the page expanded from {@code template} with {@code model} is written. */
    static String path(Map<String, Object> model, byte[] template) {
        "${DIRECTORY}/${model.fullName}/${key(model, template)}.gsp"
    }

    private static String key(Map<String, Object> model, byte[] template) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        digest.update(template)
        digest.update((byte) 0)
        for (String name : new TreeSet<String>(model.keySet())) {
            digest.update(name.getBytes(StandardCharsets.UTF_8))
            digest.update((byte) 0)
            digest.update(String.valueOf(model.get(name)).getBytes(StandardCharsets.UTF_8))
            digest.update((byte) 0)
        }
        HexFormat.of().formatHex(digest.digest(), 0, KEY_BYTES)
    }

    /**
     * Capitalises the way the application's Groovy does in {@code BeanUtils.capitalize}, which the
     * runtime model uses. The build's own Groovy is an earlier release whose version upper-cases
     * through the default locale and so can disagree.
     */
    private static String capitalize(String name) {
        if (!name || !Character.isLowerCase(name.charAt(0))) {
            return name
        }
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1))) {
            return name
        }
        char[] chars = name.toCharArray()
        chars[0] = Character.toUpperCase(chars[0])
        new String(chars)
    }
}

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
package org.apache.grails.scaffolding

import java.nio.charset.StandardCharsets

import groovy.transform.CompileStatic

import grails.codegen.model.ModelBuilder

/**
 * Expands scaffolding templates into the pages the build compiles, run by the Gradle plugin's
 * {@code generateScaffoldedViews} task in a JVM on the application's own classpath.
 *
 * <p>Running here rather than in the build means a page is modelled by the application's
 * {@link ModelBuilder}, expanded by the application's Groovy and named by {@link ScaffoldedPages},
 * exactly as the resolver models, expands and names it when the view is asked for.</p>
 *
 * <pre>
 * ScaffoldedPagesGenerator &lt;templates directory&gt; &lt;domain class list&gt; &lt;output directory&gt;
 * </pre>
 *
 * <p>The templates directory holds one file per template path, such as {@code show.gsp} or
 * {@code admin/show.gsp}; the list names one domain class per line.</p>
 *
 * @since 8.0
 */
@CompileStatic
class ScaffoldedPagesGenerator implements ModelBuilder {

    static void main(String[] args) {
        if (args.length != 3) {
            System.err.println('Usage: ScaffoldedPagesGenerator <templates directory> <domain class list> <output directory>')
            System.exit(2)
        }
        List<String> domains = new File(args[1]).readLines('UTF-8')*.trim().findAll { String line -> line }
        new ScaffoldedPagesGenerator().generate(new File(args[0]), domains, new File(args[2]))
    }

    /**
     * Writes the page for every template and domain class under {@code outputDir}, where the
     * resolver looks for it. A template that cannot be expanded for a domain class is reported and
     * left to be expanded when it is first rendered.
     *
     * @return how many pages were written
     */
    int generate(File templatesDir, List<String> domains, File outputDir) {
        Map<String, byte[]> templates = new TreeMap<>()
        if (templatesDir.isDirectory()) {
            templatesDir.eachFileRecurse { File file ->
                if (file.isFile() && file.name.endsWith('.gsp')) {
                    String path = templatesDir.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/' as char)
                    templates.put(path.substring(0, path.length() - '.gsp'.length()), file.bytes)
                }
            }
        }
        int written = 0
        for (String domain : domains) {
            Map<String, Object> model = model(domain).asMap()
            for (Map.Entry<String, byte[]> template : templates.entrySet()) {
                String page
                try {
                    page = ScaffoldedPages.expand(template.value, model)
                }
                catch (Exception e) {
                    System.err.println("Could not expand the scaffolding template ${template.key} for ${domain}, so it is " +
                            "expanded when it is first rendered instead, which a native image cannot do: ${e.cause ?: e}")
                    continue
                }
                File target = new File(outputDir, ScaffoldedPages.uri(model, template.value).substring(1))
                target.parentFile.mkdirs()
                target.setText(page, StandardCharsets.UTF_8.name())
                written++
            }
        }
        written
    }
}

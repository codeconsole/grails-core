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
 * ScaffoldedPagesGenerator &lt;domain class list&gt; &lt;output directory&gt; &lt;templates directory&gt;...
 * </pre>
 *
 * <p>The list names one domain class per line. Each templates directory holds one copy of a set of
 * templates, a file per template path such as {@code show.gsp} or {@code admin/show.gsp}; another
 * copy of the same template goes in another directory, and each copy is expanded.</p>
 *
 * @since 8.0
 */
@CompileStatic
class ScaffoldedPagesGenerator implements ModelBuilder {

    static void main(String[] args) {
        if (args.length < 3) {
            System.err.println('Usage: ScaffoldedPagesGenerator <domain class list> <output directory> <templates directory>...')
            System.exit(2)
        }
        List<String> domains = new File(args[0]).readLines('UTF-8')*.trim().findAll { String line -> line }
        List<File> templateDirs = args.drop(2).collect { String dir -> new File(dir) }
        new ScaffoldedPagesGenerator().generate(templateDirs, domains, new File(args[1]))
    }

    /**
     * Writes the page for every template in every directory, for every domain class, under
     * {@code outputDir}, where the resolver looks for it. A template that cannot be expanded for a
     * domain class is reported and left out.
     *
     * @return how many pages were written
     */
    int generate(List<File> templateDirs, List<String> domains, File outputDir) {
        List<Map.Entry<String, byte[]>> templates = []
        for (File templatesDir : templateDirs) {
            if (!templatesDir.isDirectory()) {
                continue
            }
            templatesDir.eachFileRecurse { File file ->
                if (file.isFile() && file.name.endsWith('.gsp')) {
                    String path = templatesDir.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/' as char)
                    templates.add(new AbstractMap.SimpleImmutableEntry<String, byte[]>(path.substring(0, path.length() - '.gsp'.length()), file.bytes))
                }
            }
        }
        int written = 0
        for (String domain : domains) {
            Map<String, Object> model = model(domain).asMap()
            for (Map.Entry<String, byte[]> template : templates) {
                String page
                try {
                    page = ScaffoldedPages.expand(template.value, model)
                }
                catch (Exception e) {
                    System.err.println("Could not expand the scaffolding template ${template.key} for ${domain}, so no page " +
                            "is compiled for it; if it is rendered it fails the same way: ${e.cause ?: e}")
                    continue
                }
                File target = new File(outputDir, ScaffoldedPages.uri(template.key, model, template.value).substring(1))
                target.parentFile.mkdirs()
                target.setText(page, StandardCharsets.UTF_8.name())
                written++
            }
        }
        written
    }
}

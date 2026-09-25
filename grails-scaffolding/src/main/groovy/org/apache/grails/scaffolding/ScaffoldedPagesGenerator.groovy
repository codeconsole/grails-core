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
 * ScaffoldedPagesGenerator &lt;plan&gt; &lt;output directory&gt; &lt;page encoding&gt;
 * </pre>
 *
 * <p>Each line of the plan names a domain class and, tab separated, the templates directories to
 * expand for it. A templates directory holds a file per template path, such as {@code show.gsp} or
 * {@code admin/show.gsp}. The pages are written in the encoding they will be compiled with.</p>
 *
 * @since 8.0
 */
@CompileStatic
class ScaffoldedPagesGenerator implements ModelBuilder {

    static void main(String[] args) {
        if (args.length != 3) {
            System.err.println('Usage: ScaffoldedPagesGenerator <plan> <output directory> <page encoding>')
            System.exit(2)
        }
        Map<String, List<File>> plan = [:]
        new File(args[0]).readLines('UTF-8').each { String line ->
            List<String> fields = line.split('\t').toList()*.trim().findAll { String field -> field }
            if (fields) {
                plan.put(fields.head(), fields.tail().collect { String dir -> new File(dir) })
            }
        }
        new ScaffoldedPagesGenerator().generate(plan, new File(args[1]), args[2])
    }

    /**
     * Writes, under {@code outputDir} where the resolver looks for it, the page for each domain
     * class and each template in the directories planned for it. A template that cannot be
     * expanded for a domain class is reported and left out.
     *
     * @param plan each domain class, with the templates directories to expand for it
     * @return how many pages were written
     */
    int generate(Map<String, List<File>> plan, File outputDir, String encoding = 'UTF-8') {
        int written = 0
        plan.each { String domain, List<File> templateDirs ->
            Map<String, Object> model = model(domain).asMap()
            for (Map.Entry<String, byte[]> template : read(templateDirs)) {
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
                target.setText(page, encoding)
                written++
            }
        }
        written
    }

    private static List<Map.Entry<String, byte[]>> read(List<File> templateDirs) {
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
        templates
    }
}

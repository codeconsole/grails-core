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
 * ScaffoldedPagesGenerator &lt;plan&gt; &lt;origins&gt; &lt;output directory&gt; &lt;page encoding&gt;
 * </pre>
 *
 * <p>Each line of the plan names a domain class and, tab separated, the templates directories to
 * expand for it. A templates directory holds a file per template path, such as {@code show.gsp} or
 * {@code admin/show.gsp}. Each line of the origins names a templates directory and, after a tab,
 * where its template came from. The pages are written in the encoding they will be compiled
 * with.</p>
 *
 * @since 8.0
 */
@CompileStatic
class ScaffoldedPagesGenerator implements ModelBuilder {

    static void main(String[] args) {
        if (args.length != 4) {
            System.err.println('Usage: ScaffoldedPagesGenerator <plan> <origins> <output directory> <page encoding>')
            System.exit(2)
        }
        Map<String, List<File>> plan = [:]
        new File(args[0]).readLines('UTF-8').each { String line ->
            List<String> fields = line.split('\t').toList()*.trim().findAll { String field -> field }
            if (fields) {
                plan.put(fields.head(), fields.tail().collect { String dir -> new File(dir) })
            }
        }
        Map<File, String> origins = [:]
        new File(args[1]).readLines('UTF-8').each { String line ->
            int tab = line.indexOf('\t')
            if (tab > 0) {
                origins.put(new File(line.substring(0, tab)), line.substring(tab + 1))
            }
        }
        new ScaffoldedPagesGenerator().generate(plan, new File(args[2]), args[3], origins)
    }

    /**
     * Writes, under {@code outputDir} where the resolver looks for it, the page for each domain
     * class and each template in the directories planned for it. A template that cannot be
     * expanded for a domain class is reported and left out.
     *
     * <p>A page ends with a comment naming the template it was expanded from, which renders as
     * nothing, so that a page the build reports can be traced to the template to fix.</p>
     *
     * @param plan each domain class, with the templates directories to expand for it
     * @param origins where the template in each templates directory came from; the template's own
     *     file for a directory not named
     * @return how many pages were written
     */
    int generate(Map<String, List<File>> plan, File outputDir, String encoding = 'UTF-8', Map<File, String> origins = [:]) {
        int written = 0
        plan.each { String domain, List<File> templateDirs ->
            Map<String, Object> model = model(domain).asMap()
            for (Template template : read(templateDirs, origins)) {
                String page
                try {
                    page = ScaffoldedPages.expand(template.content, model)
                }
                catch (Exception e) {
                    System.err.println("Could not expand the scaffolding template ${template.path}, from ${template.origin}, " +
                            "for ${domain}, so no page is compiled for it; if it is rendered it fails the same way: ${e.cause ?: e}")
                    continue
                }
                File target = new File(outputDir, ScaffoldedPages.uri(template.path, model, template.content).substring(1))
                target.parentFile.mkdirs()
                target.setText("${page}%{-- expanded from ${template.origin} for ${domain} --}%", encoding)
                written++
            }
        }
        written
    }

    private static List<Template> read(List<File> templateDirs, Map<File, String> origins) {
        List<Template> templates = []
        for (File templatesDir : templateDirs) {
            if (!templatesDir.isDirectory()) {
                continue
            }
            templatesDir.eachFileRecurse { File file ->
                if (file.isFile() && file.name.endsWith('.gsp')) {
                    String path = templatesDir.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/' as char)
                    templates.add(new Template(path.substring(0, path.length() - '.gsp'.length()), file.bytes,
                            origins.get(templatesDir) ?: file.path))
                }
            }
        }
        templates
    }

    /** A template, by its path, and where it came from. */
    private static final class Template {

        final String path

        final byte[] content

        final String origin

        Template(String path, byte[] content, String origin) {
            this.path = path
            this.content = content
            this.origin = origin
        }

    }
}

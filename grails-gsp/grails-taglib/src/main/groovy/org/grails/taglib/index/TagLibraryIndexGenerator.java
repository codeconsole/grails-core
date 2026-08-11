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
package org.grails.taglib.index;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;

import org.grails.taglib.discovery.TagLibraryAstDiscovery;

/**
 * Writes the tag library index for a source set.
 *
 * <p>Sources are parsed to the point where the syntax tree is complete and no further, so a tag
 * library is never loaded or executed to find out what it declares. Reading the tree rather than the
 * text means the answer follows Groovy's own understanding of the source.
 *
 * <p>The index is rewritten in full each time rather than added to. A tag library that has been
 * renamed or deleted therefore disappears from it, where an index accumulated as each class compiled
 * would keep describing tags that no longer exist until the build directory was cleaned.
 *
 * <p>Invoked in a forked process by the build, with the source set's own compile classpath, because
 * the rules it applies belong to the framework rather than to the build tooling.
 *
 * @since 8.0.0
 */
public final class TagLibraryIndexGenerator {

    private static final String TAG_LIB_ANNOTATION = "grails.gsp.TagLib";
    private static final String ARTEFACT_ANNOTATION = "grails.artefact.Artefact";
    private static final String TAG_LIB_ARTEFACT = "TagLib";

    private TagLibraryIndexGenerator() {
    }

    /**
     * @param args source directory, output directory, whether parameter names are retained, the
     *        source encoding, and whether to discard an index already present
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: <sourceDir> <outputDir> [parameterNamesRetained] [sourceEncoding]");
        }
        File sourceDir = new File(args[0]);
        File outputDir = new File(args[1]);
        boolean parameterNamesRetained = args.length < 3 || Boolean.parseBoolean(args[2]);
        String encoding = args.length > 3 && !args[3].isEmpty() ? args[3] : "UTF-8";
        boolean clearExisting = args.length < 5 || Boolean.parseBoolean(args[4]);
        generate(sourceDir, outputDir, parameterNamesRetained, encoding, clearExisting);
    }

    /**
     * Regenerates the index describing every tag library under a source directory.
     *
     * @param sourceDir the directory to scan for tag libraries
     * @param outputDir the directory the index is written beneath
     * @param parameterNamesRetained whether the compilation writes parameter names into class files
     * @param encoding the source encoding
     * @throws IOException if the index cannot be written
     */
    public static void generate(File sourceDir, File outputDir, boolean parameterNamesRetained,
            String encoding) throws IOException {
        generate(sourceDir, outputDir, parameterNamesRetained, encoding, true);
    }

    /**
     * Regenerates the index, optionally adding to what is already there.
     *
     * @param sourceDir the directory to scan for tag libraries
     * @param outputDir the directory the index is written beneath
     * @param parameterNamesRetained whether the compilation writes parameter names into class files
     * @param encoding the source encoding
     * @param clearExisting whether to discard an index already present, which several source
     *        directories contributing to one index must do only on the first of them
     * @throws IOException if the index cannot be written
     */
    public static void generate(File sourceDir, File outputDir, boolean parameterNamesRetained,
            String encoding, boolean clearExisting) throws IOException {
        if (clearExisting) {
            TagLibraryIndexWriter.clear(outputDir);
        }
        if (sourceDir == null || !sourceDir.isDirectory()) {
            return;
        }
        List<File> sources = findGroovySources(sourceDir);
        if (sources.isEmpty()) {
            return;
        }

        for (ClassNode classNode : parse(sources, parameterNamesRetained, encoding)) {
            if (!isTagLibrary(classNode)) {
                continue;
            }
            String namespace = TagLibraryAstDiscovery.resolveNamespace(classNode);
            if (namespace == null) {
                // Only knowable once the initialiser runs, so recording it would file the tags under
                // a guess. Left out, which leaves the tag library to runtime resolution.
                continue;
            }
            TagLibraryIndexWriter.write(outputDir, classNode.getName(), namespace,
                    TagLibraryAstDiscovery.findTags(classNode, parameterNamesRetained));
        }
    }

    /**
     * Parses the sources far enough to describe them.
     *
     * <p>A tag library referring to something outside this directory and off the classpath given here,
     * such as a service in the same project, cannot be resolved before that project is compiled. Those
     * are parsed on their own and skipped when they still fail, rather than losing the index for every
     * other tag library alongside them. A skipped tag library still has its descriptor written by the
     * compiler as it is built, and until then its tags resolve dynamically, exactly as a tag library
     * with no descriptor does.
     */
    private static List<ClassNode> parse(List<File> sources, boolean parameterNamesRetained,
            String encoding) {
        try {
            return collectClassNodes(compile(sources, parameterNamesRetained, encoding));
        } catch (Exception wholeSourceSetFailed) {
            List<ClassNode> classNodes = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (File source : sources) {
                try {
                    classNodes.addAll(collectClassNodes(
                            compile(List.of(source), parameterNamesRetained, encoding)));
                } catch (Exception singleSourceFailed) {
                    skipped.add(source.getName());
                }
            }
            if (!skipped.isEmpty()) {
                System.out.println("Tag library index: could not read " + String.join(", ", skipped) +
                        " before compilation; their tags resolve dynamically until they are compiled.");
            }
            classNodes.sort(Comparator.comparing(ClassNode::getName));
            return classNodes;
        }
    }

    private static CompilationUnit compile(List<File> sources, boolean parameterNamesRetained,
            String encoding) {
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.setParameters(parameterNamesRetained);
        configuration.setSourceEncoding(encoding);
        CompilationUnit unit = new CompilationUnit(configuration);
        for (File source : sources) {
            unit.addSource(source);
        }
        // Canonicalization is the last phase before bytecode, by which point traits are applied and
        // annotations resolved, and it stops short of generating or loading any class.
        unit.compile(Phases.CANONICALIZATION);
        return unit;
    }

    private static List<ClassNode> collectClassNodes(CompilationUnit unit) {
        List<ClassNode> classNodes = new ArrayList<>();
        unit.getAST().getModules().forEach(module -> classNodes.addAll(module.getClasses()));
        // Sorted so that the index is identical for identical sources regardless of the order the
        // file system enumerated them, keeping the build reproducible.
        classNodes.sort(Comparator.comparing(ClassNode::getName));
        return classNodes;
    }

    private static boolean isTagLibrary(ClassNode classNode) {
        for (AnnotationNode annotation : classNode.getAnnotations()) {
            String annotationName = annotation.getClassNode().getName();
            if (TAG_LIB_ANNOTATION.equals(annotationName)) {
                return true;
            }
            if (ARTEFACT_ANNOTATION.equals(annotationName)) {
                var member = annotation.getMember("value");
                if (member != null && TAG_LIB_ARTEFACT.equals(member.getText())) {
                    return true;
                }
            }
        }
        return classNode.getName().endsWith(TAG_LIB_ARTEFACT);
    }

    private static List<File> findGroovySources(File sourceDir) throws IOException {
        try (Stream<Path> paths = Files.walk(sourceDir.toPath())) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".groovy"))
                    .sorted()
                    .map(Path::toFile)
                    .toList();
        }
    }
}

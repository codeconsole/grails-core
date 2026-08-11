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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Properties;
import java.util.TreeSet;

/**
 * Writes the compile-time descriptor for a single tag library.
 *
 * <p>Two files are produced per tag library: a descriptor named after the tag library class, and an
 * entry in a shared {@code index.properties} manifest naming it. The manifest exists because a
 * classpath directory cannot be enumerated from inside a jar, so the reader needs the names up front.
 * Both live under {@link TagLibraryIndex#INDEX_LOCATION} and merge across jars without a build step.
 *
 * @since 8.0.0
 */
public final class TagLibraryIndexWriter {

    private TagLibraryIndexWriter() {
    }

    /**
     * Removes any index previously written beneath a directory, so that a regenerated index describes
     * only the tag libraries that exist now. Without this a renamed or deleted tag library would keep
     * a descriptor, and the manifest naming it, until the build directory was cleaned.
     *
     * @param outputDirectory the directory the index is written beneath
     * @throws IOException if an existing index cannot be removed
     */
    public static void clear(File outputDirectory) throws IOException {
        if (outputDirectory == null) {
            return;
        }
        File indexDirectory = new File(outputDirectory, TagLibraryIndex.INDEX_LOCATION);
        File[] existing = indexDirectory.listFiles();
        if (existing == null) {
            return;
        }
        for (File file : existing) {
            if (file.isFile() && file.getName().endsWith(".properties")) {
                Files.deleteIfExists(file.toPath());
            }
        }
    }

    /**
     * Writes the descriptor for a tag library into a compiler output directory.
     *
     * @param outputDirectory the compilation target directory; nothing is written when {@code null}
     * @param className the binary name of the tag library
     * @param namespace the namespace the tag library declares
     * @param tagNames the tag names the tag library declares
     * @throws IOException if the descriptor cannot be written
     */
    public static void write(File outputDirectory, String className, String namespace,
            Collection<String> tagNames) throws IOException {
        if (outputDirectory == null || className == null || className.isEmpty() ||
                namespace == null || namespace.isEmpty()) {
            return;
        }
        File indexDirectory = new File(outputDirectory, TagLibraryIndex.INDEX_LOCATION);
        if (!indexDirectory.isDirectory() && !indexDirectory.mkdirs() && !indexDirectory.isDirectory()) {
            return;
        }

        Properties descriptor = new Properties();
        descriptor.setProperty(TagLibraryIndex.VERSION_KEY, String.valueOf(TagLibraryIndex.FORMAT_VERSION));
        descriptor.setProperty(TagLibraryIndex.NAMESPACE_KEY, namespace);
        descriptor.setProperty(TagLibraryIndex.CLASS_KEY, className);
        // Sorted so that recompiling unchanged sources produces byte-identical output, which keeps
        // the build reproducible and avoids spurious up-to-date checks failing downstream.
        descriptor.setProperty(TagLibraryIndex.TAGS_KEY, String.join(",", new TreeSet<>(tagNames)));
        store(new File(indexDirectory, className + ".properties"), descriptor);

        File manifest = new File(indexDirectory, "index.properties");
        Properties names = new Properties();
        if (manifest.isFile()) {
            try (InputStream in = Files.newInputStream(manifest.toPath())) {
                names.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        }
        names.setProperty(className, "");
        store(manifest, names);
    }

    private static void store(File file, Properties properties) throws IOException {
        // Properties.store stamps a comment with the current time, which would make output differ
        // between builds; the entries are written directly instead to keep the descriptor stable.
        StringBuilder text = new StringBuilder();
        for (String key : new TreeSet<>(properties.stringPropertyNames())) {
            text.append(escape(key)).append('=').append(escape(properties.getProperty(key))).append('\n');
        }
        try (OutputStream out = Files.newOutputStream(file.toPath());
             Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            writer.write(text.toString());
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("=", "\\=").replace(":", "\\:");
    }
}

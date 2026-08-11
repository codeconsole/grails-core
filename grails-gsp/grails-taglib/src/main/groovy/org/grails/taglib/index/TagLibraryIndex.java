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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The set of tag libraries and tag names known at compile time.
 *
 * <p>Each tag library contributes one descriptor under {@value #INDEX_LOCATION}, written by the
 * {@code TagLib} AST transformation as the tag library is compiled. Descriptors are per class rather
 * than per module so that libraries packaged in separate jars merge on the classpath without any
 * build step having to combine them, in the same way {@code META-INF/services} entries do.
 *
 * <p>Reading the index answers "which tags exist in namespace x" without loading or reflecting over a
 * single tag library class, which is what allows GSP expressions to be resolved when a page is
 * compiled rather than dispatched dynamically when it renders.
 *
 * @since 8.0.0
 */
public final class TagLibraryIndex {

    /**
     * Classpath directory holding one descriptor per compiled tag library.
     */
    public static final String INDEX_LOCATION = "META-INF/grails/taglibs/";

    /**
     * Descriptor format this build writes and understands. A descriptor carrying anything else was
     * produced by a different version of Grails and is ignored, so its tags resolve dynamically rather
     * than being read under the wrong set of rules.
     */
    public static final int FORMAT_VERSION = 1;

    static final String VERSION_KEY = "version";
    static final String NAMESPACE_KEY = "namespace";
    static final String CLASS_KEY = "class";
    static final String TAGS_KEY = "tags";

    private final Map<String, Map<String, TagLibraryIndexEntry>> byNamespace;
    private final Map<String, Set<String>> ambiguousByNamespace;

    private TagLibraryIndex(Map<String, Map<String, TagLibraryIndexEntry>> byNamespace,
            Map<String, Set<String>> ambiguousByNamespace) {
        this.byNamespace = byNamespace;
        this.ambiguousByNamespace = ambiguousByNamespace;
    }

    /**
     * Reads every tag library descriptor visible to the given class loader.
     *
     * @param classLoader the loader to scan; when {@code null} the thread context loader is used
     * @return the merged index, never {@code null}
     */
    public static TagLibraryIndex load(ClassLoader classLoader) {
        ClassLoader loader = classLoader != null ? classLoader : Thread.currentThread().getContextClassLoader();
        Map<String, Map<String, TagLibraryIndexEntry>> merged = new TreeMap<>();
        Map<String, Set<String>> ambiguous = new TreeMap<>();
        if (loader == null) {
            return new TagLibraryIndex(merged, ambiguous);
        }
        // A directory resource enumerates its children on some classpath layouts but not inside jars,
        // so the descriptors are discovered through the manifest of names each descriptor records
        // rather than by listing the directory.
        for (URL url : listDescriptors(loader)) {
            Properties properties = read(url);
            if (properties == null) {
                continue;
            }
            if (!String.valueOf(FORMAT_VERSION).equals(properties.getProperty(VERSION_KEY))) {
                continue;
            }
            String namespace = properties.getProperty(NAMESPACE_KEY);
            String className = properties.getProperty(CLASS_KEY);
            String tags = properties.getProperty(TAGS_KEY, "");
            if (namespace == null || namespace.isEmpty() || className == null || className.isEmpty()) {
                continue;
            }
            Map<String, TagLibraryIndexEntry> tagsForNamespace =
                    merged.computeIfAbsent(namespace, k -> new TreeMap<>());
            for (String tagName : tags.split(",")) {
                String trimmed = tagName.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                TagLibraryIndexEntry existing = tagsForNamespace.get(trimmed);
                if (existing != null && !existing.tagLibraryClassName().equals(className)) {
                    // At runtime the tag library registered last wins, and registration order comes
                    // from artefact scanning rather than from classpath order, so which of these two
                    // will win cannot be known here. Resolving it either way risks compiling against
                    // one implementation and dispatching to the other, so the tag is marked ambiguous
                    // and left to runtime resolution.
                    ambiguous.computeIfAbsent(namespace, k -> new TreeSet<>()).add(trimmed);
                    continue;
                }
                tagsForNamespace.put(trimmed, new TagLibraryIndexEntry(namespace, trimmed, className));
            }
        }
        return new TagLibraryIndex(merged, ambiguous);
    }

    private static Set<URL> listDescriptors(ClassLoader loader) {
        Set<URL> urls = new LinkedHashSet<>();
        try {
            Enumeration<URL> manifests = loader.getResources(INDEX_LOCATION + "index.properties");
            while (manifests.hasMoreElements()) {
                URL manifest = manifests.nextElement();
                Properties names = read(manifest);
                if (names == null) {
                    continue;
                }
                for (String className : names.stringPropertyNames()) {
                    Enumeration<URL> descriptors = loader.getResources(INDEX_LOCATION + className + ".properties");
                    while (descriptors.hasMoreElements()) {
                        urls.add(descriptors.nextElement());
                    }
                }
            }
        } catch (IOException e) {
            // A classpath that cannot be enumerated yields no statically known tags, which degrades to
            // the dynamic dispatch that was in place before the index existed.
            return urls;
        }
        return urls;
    }

    private static Properties read(URL url) {
        try (InputStream in = url.openStream()) {
            Properties properties = new Properties();
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            return properties;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * @param namespace a tag library namespace, for example {@code g}
     * @return true if any compiled tag library declared that namespace
     */
    public boolean hasNamespace(String namespace) {
        return byNamespace.containsKey(namespace);
    }

    /**
     * @param namespace a tag library namespace
     * @param tagName a tag name within that namespace
     * @return the declaring tag library, or {@code null} when the tag is not statically known
     */
    public TagLibraryIndexEntry lookup(String namespace, String tagName) {
        if (isAmbiguous(namespace, tagName)) {
            return null;
        }
        Map<String, TagLibraryIndexEntry> tags = byNamespace.get(namespace);
        return tags != null ? tags.get(tagName) : null;
    }

    /**
     * Whether more than one tag library declares this tag, in which case which one the runtime will
     * dispatch to depends on registration order and cannot be decided here.
     *
     * @param namespace a tag library namespace
     * @param tagName a tag name within that namespace
     * @return true when the tag is declared by more than one tag library
     */
    public boolean isAmbiguous(String namespace, String tagName) {
        Set<String> ambiguousTags = ambiguousByNamespace.get(namespace);
        return ambiguousTags != null && ambiguousTags.contains(tagName);
    }

    /**
     * @param namespace a tag library namespace
     * @return the tags in that namespace declared by more than one tag library
     */
    public Set<String> getAmbiguousTagNames(String namespace) {
        Set<String> ambiguousTags = ambiguousByNamespace.get(namespace);
        return ambiguousTags != null ? Collections.unmodifiableSet(new TreeSet<>(ambiguousTags)) :
                Collections.emptySet();
    }

    /**
     * @return every namespace contributed by a compiled tag library
     */
    public Set<String> getNamespaces() {
        return Collections.unmodifiableSet(new TreeSet<>(byNamespace.keySet()));
    }

    /**
     * @param namespace a tag library namespace
     * @return the tag names declared in that namespace, empty when the namespace is unknown
     */
    public Set<String> getTagNames(String namespace) {
        Map<String, TagLibraryIndexEntry> tags = byNamespace.get(namespace);
        return tags != null ? Collections.unmodifiableSet(new TreeSet<>(tags.keySet())) :
                Collections.emptySet();
    }

    /**
     * The tags a given tag library declares, as recorded when it was compiled.
     *
     * <p>Lets a tag library be registered without discovering its tags by reflection, which otherwise
     * means touching the metaclass of every tag library as an application starts.
     *
     * @param tagLibraryClassName the binary name of a tag library
     * @return its tags, or an empty set when it has no descriptor, in which case the caller must
     *         discover them itself
     */
    public Set<String> getTagNamesForClass(String tagLibraryClassName) {
        if (tagLibraryClassName == null) {
            return Collections.emptySet();
        }
        Set<String> tagNames = new TreeSet<>();
        for (Map<String, TagLibraryIndexEntry> tags : byNamespace.values()) {
            for (TagLibraryIndexEntry entry : tags.values()) {
                if (tagLibraryClassName.equals(entry.tagLibraryClassName())) {
                    tagNames.add(entry.tagName());
                }
            }
        }
        // A tag this class declares that another also declares is ambiguous and was not recorded
        // against either, so fall back to discovery rather than register an incomplete set.
        for (Set<String> ambiguousTags : ambiguousByNamespace.values()) {
            if (!ambiguousTags.isEmpty() && !tagNames.isEmpty()) {
                return Collections.emptySet();
            }
        }
        return Collections.unmodifiableSet(tagNames);
    }

    /**
     * @return true when no compiled tag library was found, in which case callers must fall back to
     *         runtime resolution
     */
    public boolean isEmpty() {
        return byNamespace.isEmpty();
    }

    @Override
    public String toString() {
        Map<String, Set<String>> summary = new LinkedHashMap<>();
        byNamespace.forEach((ns, tags) -> summary.put(ns, tags.keySet()));
        return "TagLibraryIndex" + summary;
    }
}

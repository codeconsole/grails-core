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
import java.util.WeakHashMap;

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
    public static final int FORMAT_VERSION = 2;

    /**
     * Settings the build states for the compilation the index is read in, written alongside the
     * descriptors by the build and deliberately not packaged into the artifact: they describe how this
     * project is compiled, not what its tag libraries declare.
     */
    public static final String SETTINGS_LOCATION = INDEX_LOCATION + "compile-settings.properties";

    static final String VERSION_KEY = "version";
    static final String NAMESPACE_KEY = "namespace";
    static final String CLASS_KEY = "class";
    static final String TAGS_KEY = "tags";
    static final String STRICT_KEY = "strictTags";
    static final String DYNAMIC_NAMESPACES_KEY = "dynamicTagNamespaces";

    /**
     * One index per class loader. A compilation gets a class loader of its own, so this is read once
     * per compilation rather than once per source file, and is not held after that compilation ends.
     * Caching in a plain static field instead would carry one project's tag libraries into the next
     * compilation in the same Gradle daemon.
     */
    private static final Map<ClassLoader, TagLibraryIndex> BY_CLASS_LOADER =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final Map<String, Map<String, TagLibraryIndexEntry>> byNamespace;
    private final Map<String, Set<String>> ambiguousByNamespace;
    private final Map<String, Set<String>> tagNamesByClass;
    private final boolean strict;
    private final Set<String> dynamicNamespaces;

    private TagLibraryIndex(Map<String, Map<String, TagLibraryIndexEntry>> byNamespace,
            Map<String, Set<String>> ambiguousByNamespace, Map<String, Set<String>> tagNamesByClass,
            boolean strict, Set<String> dynamicNamespaces) {
        this.byNamespace = byNamespace;
        this.ambiguousByNamespace = ambiguousByNamespace;
        this.tagNamesByClass = tagNamesByClass;
        this.strict = strict;
        this.dynamicNamespaces = dynamicNamespaces;
    }

    /**
     * Reads the index for a class loader, reusing the one already read for it.
     *
     * <p>Reading walks every jar on the classpath, so a compiler that consults the index for each
     * source file it compiles would walk it once per file. Use this from compilation; use
     * {@link #load(ClassLoader)} where a fresh read is wanted.
     *
     * @param classLoader the loader to scan; when {@code null} the thread context loader is used
     * @return the merged index, never {@code null}
     */
    public static TagLibraryIndex forClassLoader(ClassLoader classLoader) {
        ClassLoader loader = classLoader != null ? classLoader : Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            return load(null);
        }
        return BY_CLASS_LOADER.computeIfAbsent(loader, TagLibraryIndex::load);
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
        Map<String, Set<String>> byClass = new TreeMap<>();
        if (loader == null) {
            return new TagLibraryIndex(merged, ambiguous, byClass, false, Collections.emptySet());
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
            for (String encodedTag : tags.split(",")) {
                String trimmed = encodedTag.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                // Recorded as "name:KIND"; an unrecognised kind is treated as the dynamic one so that a
                // descriptor from a later version cannot cause a call to be bound wrongly.
                int separator = trimmed.lastIndexOf(':');
                String tagName = separator > 0 ? trimmed.substring(0, separator) : trimmed;
                TagLibraryIndexEntry.Kind kind = TagLibraryIndexEntry.Kind.LEGACY_CLOSURE;
                if (separator > 0) {
                    try {
                        kind = TagLibraryIndexEntry.Kind.valueOf(trimmed.substring(separator + 1));
                    } catch (IllegalArgumentException unknownKind) {
                        kind = TagLibraryIndexEntry.Kind.LEGACY_CLOSURE;
                    }
                }
                trimmed = tagName;
                // Recorded against the declaring class before ambiguity is considered, so that asking
                // what one tag library declares is answered from its own descriptor and is unaffected
                // by whether some other tag library happens to declare the same name.
                byClass.computeIfAbsent(className, k -> new TreeSet<>()).add(trimmed);
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
                tagsForNamespace.put(trimmed,
                        new TagLibraryIndexEntry(namespace, trimmed, className, kind, true));
            }
        }
        Properties settings = readSettings(loader);
        boolean strict = Boolean.parseBoolean(settings.getProperty(STRICT_KEY, "false"));
        Set<String> dynamic = new TreeSet<>();
        for (String namespace : settings.getProperty(DYNAMIC_NAMESPACES_KEY, "").split(",")) {
            String trimmed = namespace.trim();
            if (!trimmed.isEmpty()) {
                dynamic.add(trimmed);
            }
        }
        return new TagLibraryIndex(merged, ambiguous, byClass, strict, Collections.unmodifiableSet(dynamic));
    }

    /**
     * Reads the settings the build states for this compilation. Only the project being compiled
     * contributes them, so the first one found wins rather than several being merged.
     */
    private static Properties readSettings(ClassLoader loader) {
        URL url = loader.getResource(SETTINGS_LOCATION);
        if (url == null) {
            return new Properties();
        }
        Properties settings = read(url);
        return settings != null ? settings : new Properties();
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
     * <p>Answered from that tag library's own descriptor, so a tag it declares is reported whether or
     * not another tag library declares the same name. Which of two tag libraries answers to a name at
     * runtime is a separate question, asked through {@link #lookup} and {@link #isAmbiguous}.
     *
     * <p>This describes the class as it was compiled. A class that has since been reloaded, or one
     * built without a descriptor, is not described here and has to be asked directly.
     *
     * @param tagLibraryClassName the binary name of a tag library
     * @return its tags, or an empty set when it has no descriptor
     */
    public Set<String> getTagNamesForClass(String tagLibraryClassName) {
        if (tagLibraryClassName == null) {
            return Collections.emptySet();
        }
        Set<String> tagNames = tagNamesByClass.get(tagLibraryClassName);
        return tagNames != null ? Collections.unmodifiableSet(new TreeSet<>(tagNames)) :
                Collections.emptySet();
    }

    /**
     * Whether a descriptor for this tag library already exists.
     *
     * <p>Lets a tag library being compiled tell whether something has already described it - the build
     * generating the index ahead of compilation - so that it does not write a second, separately
     * maintained copy. A tag library the build did not manage to describe is not covered here and
     * describes itself instead.
     *
     * @param tagLibraryClassName the binary name of a tag library
     * @return true when a descriptor for it was read
     */
    public boolean isClassDescribed(String tagLibraryClassName) {
        return tagLibraryClassName != null && tagNamesByClass.containsKey(tagLibraryClassName);
    }

    /**
     * Whether the build asked for a tag no compiled tag library declares to fail compilation.
     *
     * @return true when the build set {@code grails.compileStatic.strictTags}
     */
    public boolean isStrict() {
        return strict;
    }

    /**
     * Namespaces the build declared as filled in at runtime, whose tags are therefore never reported
     * as unknown however complete the index is.
     *
     * @return the declared dynamic namespaces, empty when none were declared
     */
    public Set<String> getDynamicNamespaces() {
        return dynamicNamespaces;
    }

    /**
     * @param namespace a tag library namespace
     * @return true when the build declared this namespace as filled in at runtime
     */
    public boolean isDynamicNamespace(String namespace) {
        return namespace != null && dynamicNamespaces.contains(namespace);
    }

    /**
     * Whether a compiled tag library declares this tag, including one declared by more than one of
     * them. Such a tag exists; which tag library answers to it is settled at runtime.
     *
     * @param namespace a tag library namespace
     * @param tagName a tag name within that namespace
     * @return true when the tag is known to the index
     */
    public boolean isKnown(String namespace, String tagName) {
        if (isAmbiguous(namespace, tagName)) {
            return true;
        }
        Map<String, TagLibraryIndexEntry> tags = byNamespace.get(namespace);
        return tags != null && tags.containsKey(tagName);
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

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
package org.apache.grails.scaffolding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeSet;

/**
 * Names the page the build compiles from a scaffolding template, so that the runtime resolver can
 * find it.
 *
 * <p>A page is named for a digest of the template it was expanded from and of every name the model
 * binds. Two expansions share a name only when their inputs are identical, so a template the build
 * did not see, or a model derived differently from the one bound here, names a page that does not
 * exist rather than a different one.</p>
 *
 * <p>The Gradle plugin's {@code GenerateScaffoldedViewsTask} derives the same names independently
 * and writes the pages under the application's views, and the two are held together by the same
 * test vector in both modules.</p>
 *
 * @since 8.0
 */
public final class ScaffoldedPages {

    /**
     * The directory under the views root that holds the compiled pages. A controller's views are
     * resolved from a directory named after a Java identifier, which cannot contain a hyphen, so no
     * request for a controller's view reaches a page here.
     */
    public static final String DIRECTORY = "grails-scaffolded";

    /** Bytes of the digest kept in a page's name. */
    private static final int KEY_BYTES = 16;

    private ScaffoldedPages() {
    }

    /**
     * The URI, relative to the views root, of the page expanded from {@code template} with
     * {@code model}.
     *
     * @param model the names the template is expanded with, including {@code fullName}
     * @param template the template exactly as it is read
     * @return the URI a page locator resolves the page by
     */
    public static String uri(Map<String, ?> model, byte[] template) {
        return "/" + DIRECTORY + "/" + model.get("fullName") + "/" + key(model, template) + ".gsp";
    }

    private static String key(Map<String, ?> model, byte[] template) {
        MessageDigest digest = sha256();
        digest.update(template);
        digest.update((byte) 0);
        for (String name : new TreeSet<>(model.keySet())) {
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(String.valueOf(model.get(name)).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest(), 0, KEY_BYTES);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
    }
}

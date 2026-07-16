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
package org.grails.forge.build.gradle;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.order.Ordered;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public interface GradleRepository extends Ordered {
    @NonNull
    String toSnippet(String basePadding);

    static Set<GradleRepository> getDefaultRepositories(String grailsVersion) {
        return getDefaultRepositories(grailsVersion, System.getenv("GRAILS_REPO_URL"));
    }

    static Set<GradleRepository> getDefaultRepositories(String grailsVersion, String overrideRepo) {
        Set<GradleRepository> repositories = new LinkedHashSet<>();

        if (overrideRepo != null && !overrideRepo.isEmpty()) {
            List<String> overrides = Arrays.stream(overrideRepo.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

            for (String overrideUrl : overrides) {
                repositories.add(
                    new DefaultGradleRepository(
                        repositories.size(),
                        validateOverrideRepository(overrideUrl)
                    )
                );
            }
        }
        repositories.add(new MavenCentralRepository(repositories.size()));
        repositories.add(new DefaultGradleRepository(repositories.size(), "https://repo.grails.org/grails/restricted"));
        if (grailsVersion.endsWith("SNAPSHOT")) {
            repositories.add(new DefaultGradleRepository(
                repositories.size(),
                "https://repository.apache.org/content/groups/snapshots",
                null,
                List.of(
                    new VersionRegexRepoFilter(
                        "org[.]apache[.]grails.*", ".*", ".*-SNAPSHOT"
                    ),
                    new VersionRegexRepoFilter(
                        "org[.]apache[.]groovy.*", "groovy.*", ".*-SNAPSHOT"
                    )
                ),
                List.of(VersionType.SNAPSHOT)
            ));
            repositories.add(new DefaultGradleRepository(
                repositories.size(),
                "https://central.sonatype.com/repository/maven-snapshots",
                null,
                List.of(
                    new VersionRegexRepoFilter(
                        "org[.]sitemesh.*", ".*", ".*"
                    )
                ),
                List.of(VersionType.SNAPSHOT)
            ));
            repositories.add(new DefaultGradleRepository(
                repositories.size(),
                "https://repository.apache.org/content/groups/staging",
                null,
                List.of(
                    new VersionRegexRepoFilter(
                        "org[.]apache[.]grails[.]gradle", "grails-publish", ".*"
                    ),
                    new VersionRegexRepoFilter(
                        "org[.]apache[.]groovy.*", "groovy.*", ".*"
                    )
                ),
                List.of(VersionType.RELEASE)
            ));
        }

        return repositories;
    }

    private static String validateOverrideRepository(String overrideUrl) {
        // Local filesystem repositories (including Windows drive paths such as C:\repo,
        // C:/repo and drive-relative C:repo) are always allowed; only genuine remote URLs
        // are constrained to HTTPS.
        if (isLocalRepository(overrideUrl)) {
            return overrideUrl;
        }
        try {
            URI uri = new URI(overrideUrl);
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return overrideUrl;
            }
            throw new IllegalArgumentException("Remote GRAILS_REPO_URL repositories must use HTTPS: " + overrideUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid GRAILS_REPO_URL repository: " + overrideUrl, e);
        }
    }

    private static boolean isLocalRepository(String overrideUrl) {
        // A Windows drive path (absolute "C:\repo" / "C:/repo" or drive-relative "C:repo",
        // including nested paths and spaces) is checked before URI parsing, because a
        // single-letter drive prefix is otherwise mistaken for a URL scheme.
        if (isWindowsDrivePath(overrideUrl)) {
            return true;
        }
        try {
            URI uri = new URI(overrideUrl);
            String scheme = uri.getScheme();
            return scheme == null || "file".equalsIgnoreCase(scheme);
        } catch (URISyntaxException e) {
            return !looksLikeUri(overrideUrl);
        }
    }

    private static boolean looksLikeUri(String overrideUrl) {
        int colonIndex = overrideUrl.indexOf(':');
        if (colonIndex < 1) {
            return false;
        }
        for (int i = 0; i < colonIndex; i++) {
            char character = overrideUrl.charAt(i);
            if (!Character.isLetterOrDigit(character) && character != '+' && character != '-' && character != '.') {
                return false;
            }
        }
        return Character.isLetter(overrideUrl.charAt(0));
    }

    private static boolean isWindowsDrivePath(String overrideUrl) {
        if (overrideUrl.length() < 2
                || !Character.isLetter(overrideUrl.charAt(0))
                || overrideUrl.charAt(1) != ':') {
            return false;
        }
        // Distinguish a drive path ("C:\repo", "C:/repo", "C:repo") from a single-letter
        // URL scheme ("c://host"), which has a "//" authority separator after the colon.
        return !overrideUrl.regionMatches(2, "//", 0, 2);
    }
}

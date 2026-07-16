/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package grails.init;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Helper class to locate the remote or local repository for the `grails-cli`
 */
public class GrailsWrapperRepo {
    private String baseUrl;
    private String repoPath;
    private String metadataName;
    boolean isFile;

    private GrailsWrapperRepo() {
    }

    /**
     * @return the base url of the given repository
     */
    String getUrl() {
        return join(baseUrl, repoPath);
    }

    /**
     * Get the path to the root metadata file in the configured repository
     *
     * @return the url to the root metadata-maven.xml file
     */
    String getRootMetadataUrl() {
        return join(getUrl(), metadataName);
    }

    /**
     * Given a grails version, get the path to the version specific metadata file in the configured repository
     *
     * @param version the desired grails version
     * @return the url to the version specific metadata-maven.xml file
     */
    String getMetadataUrl(GrailsVersion version) {
        return join(getUrl(), version.version, metadataName);
    }

    /**
     * Given a grails version & a file name, get the path to that file in the configured repository
     *
     * @param version the grails version
     * @param name    the file name of the `grails-cli` jar
     * @return the file url to the `grails-cli` jar
     */
    String getFileUrl(GrailsVersion version, String name) {
        return join(getUrl(), version.version, name);

    }

    private String join(String... elements) {
        return String.join(isFile ? File.separator : "/", elements);
    }

    /**
     * @return the repo the wrapper should look for the `grails-cli` jar
     */
    static List<GrailsWrapperRepo> getSelectedRepos() {
        List<GrailsWrapperRepo> repos = new ArrayList<>();

        // Prefer the override repo first
        for (String overRepoUrl : getOverriddenMavenRepos()) {
            if (overRepoUrl != null) {
                System.out.println("...Update Repository is overridden to prefer [" + overRepoUrl + "].");
                repos.add(createGrailsWrapperRepo(overRepoUrl));
            }
        }

        // prefer maven central second
        repos.add(createGrailsWrapperRepo("https://repo1.maven.org/maven2"));

        // finally fallback to ASF repo (only valid for snapshot/stage testing)
        repos.add(createGrailsWrapperRepo("https://repository.apache.org/content/groups/public"));

        return repos;
    }

    static GrailsWrapperRepo createGrailsWrapperRepo(String urlOrFile) {
        GrailsWrapperRepo repo = new GrailsWrapperRepo();
        repo.isFile = isFileRepository(urlOrFile);
        if (!repo.isFile) {
            validateRemoteRepositoryUrl(urlOrFile);
        }
        repo.repoPath = repo.isFile ?
            String.join(File.separator, "org", "apache", "grails", GrailsWrapperHome.CLI_COMBINED_PROJECT_NAME) :
            "org/apache/grails/" + GrailsWrapperHome.CLI_COMBINED_PROJECT_NAME;
        repo.baseUrl = normalizeBaseUrl(urlOrFile, repo.isFile);

        if ((repo.isFile && endsWithFileSeparator(repo.baseUrl)) || (!repo.isFile && repo.baseUrl.endsWith("/"))) {
            // remove trailing slash
            repo.baseUrl = repo.baseUrl.substring(0, repo.baseUrl.length() - 1);
        }

        repo.metadataName = repo.isFile ? "maven-metadata-local.xml" : "maven-metadata.xml";
        return repo;
    }

    private static void validateRemoteRepositoryUrl(String url) {
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("Grails wrapper remote repository URLs must use HTTPS: " + url);
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid Grails wrapper remote repository URL: " + url, e);
        }
    }

    private static boolean isFileRepository(String urlOrFile) {
        if (isWindowsAbsolutePath(urlOrFile)) {
            return true;
        }
        try {
            URI uri = new URI(urlOrFile);
            String scheme = uri.getScheme();
            return scheme == null || "file".equalsIgnoreCase(scheme);
        } catch (URISyntaxException e) {
            return true;
        }
    }

    private static String normalizeBaseUrl(String urlOrFile, boolean fileRepository) {
        if (!fileRepository || isWindowsAbsolutePath(urlOrFile)) {
            return urlOrFile;
        }
        try {
            URI uri = new URI(urlOrFile);
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return new File(uri).getPath();
            }
        } catch (IllegalArgumentException | URISyntaxException e) {
            return urlOrFile;
        }
        return urlOrFile;
    }

    private static boolean endsWithFileSeparator(String urlOrFile) {
        return urlOrFile.endsWith("/") || urlOrFile.endsWith(File.separator);
    }

    private static boolean isWindowsAbsolutePath(String urlOrFile) {
        return urlOrFile.length() > 2 &&
            Character.isLetter(urlOrFile.charAt(0)) &&
            urlOrFile.charAt(1) == ':' &&
            (urlOrFile.charAt(2) == '\\' || urlOrFile.charAt(2) == '/');
    }

    /**
     * @return the overridden maven repositories if configured via the property `grails.repo.url` or environment variable `GRAILS_REPO_URL`
     */
    static List<String> getOverriddenMavenRepos() {
        String baseUrl = System.getProperty("grails.repo.url");
        if (baseUrl != null) {
            return Arrays.stream(baseUrl.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        }

        String envBasedUrl = System.getenv("GRAILS_REPO_URL");
        if (envBasedUrl != null) {
            return Arrays.stream(envBasedUrl.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        }

        return new ArrayList<>();
    }
}

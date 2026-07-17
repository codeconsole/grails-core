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
package org.grails.doc.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Scans generated Groovydoc HTML for malformed navigation and inner-class
 * links. Kept free of the Gradle API so it can be unit tested directly -
 * {@code build-logic/docs-core} deliberately keeps Gradle classes off the
 * test compile classpath.
 */
class GroovydocLinkAuditor {

    // Patterns common to Groovydoc navigation failures
    private static final Map<Pattern, String> NAV_LINK_PATTERNS = [
            (Pattern.compile(/href='([^']+?)\/deprecated-list\.html'/)): "href='deprecated-list.html'",
            (Pattern.compile(/href='([^']+?)\/help-doc\.html'/)): "href='help-doc.html'",
            (Pattern.compile(/href='([^']+?)\/index-all\.html'/)): "href='index-all.html'",
            (Pattern.compile(/href='([^']+?)\/overview-summary\.html'/)): "href='overview-summary.html'"
    ].asImmutable()

    // Inner class path issues like Query/Order.Direction.html -> Query.Order.Direction.html
    private static final Pattern INNER_CLASS_LINK_PATTERN =
            Pattern.compile(/href='([^']+?)\/([A-Z][A-Za-z0-9_]*?)\/([A-Z][A-Za-z0-9_.]*?\.html)'/)

    static List<String> findViolations(File apiDir) {
        List<String> violations = []

        apiDir.eachFileRecurse { File file ->
            if (file.name.endsWith('.html')) {
                violations.addAll(findViolationsInFile(file))
            }
        }

        violations
    }

    private static List<String> findViolationsInFile(File file) {
        List<String> violations = []
        String content = file.text

        NAV_LINK_PATTERNS.each { Pattern pattern, String replacement ->
            Matcher matcher = pattern.matcher(content)
            if (matcher.find()) {
                violations << "Malformed nav link in ${file.name}: ${matcher.group(0)}".toString()
            }
        }

        Matcher innerMatcher = INNER_CLASS_LINK_PATTERN.matcher(content)
        while (innerMatcher.find()) {
            String relPath = innerMatcher.group(1)
            String outer = innerMatcher.group(2)
            String inner = innerMatcher.group(3)

            Path currentPath = file.toPath().parent
            Path targetPath = currentPath.resolve(relPath).resolve("${outer}.${inner}").normalize()

            if (Files.exists(targetPath)) {
                violations << "Malformed inner class link in ${file.name}: ${innerMatcher.group(0)}".toString()
            }
        }

        violations
    }
}

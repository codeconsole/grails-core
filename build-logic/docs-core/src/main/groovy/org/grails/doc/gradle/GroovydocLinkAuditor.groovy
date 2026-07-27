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
 * Scans generated Groovydoc HTML for malformed inner-class links, where a
 * slash-separated path segment (produced for an inner class, e.g.
 * {@code Outer/Inner.html}) should have been the dotted Groovydoc naming
 * convention ({@code Outer.Inner.html}). Kept free of the Gradle API so it
 * can be unit tested directly - {@code build-logic/docs-core} deliberately
 * keeps Gradle classes off the test compile classpath.
 *
 * <p>This intentionally does not audit the navigation links (deprecated-list.html,
 * help-doc.html, etc.): the bottom nav bar that {@code groovy-groovydoc} emits on
 * package-summary pages omits the relative-root prefix that every other occurrence
 * carries, which is a pre-existing quirk of that template rather than a content
 * issue this repository can fix - checking for it would flag hundreds of instances
 * of the same known, low-impact tool behavior on every doc build.</p>
 */
class GroovydocLinkAuditor {

    // Inner class path issues like Query/Order.Direction.html -> Query.Order.Direction.html.
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
        Path currentPath = file.toPath().parent

        Matcher innerMatcher = INNER_CLASS_LINK_PATTERN.matcher(content)
        while (innerMatcher.find()) {
            String relPath = innerMatcher.group(1)
            String outer = innerMatcher.group(2)
            String inner = innerMatcher.group(3)

            Path targetPath = currentPath.resolve(relPath).resolve("${outer}.${inner}").normalize()

            if (Files.exists(targetPath)) {
                violations << "Malformed inner class link in ${file.name}: ${innerMatcher.group(0)}".toString()
            }
        }

        violations
    }
}

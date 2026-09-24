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
package grails.openapi

import java.lang.reflect.Method
import java.util.function.Predicate

import groovy.transform.CompileStatic

import org.springframework.util.AntPathMatcher

/**
 * Which operations a document describes: the default document, or one named group.
 *
 * <p>The criteria are the ones springdoc applies to a group: they are read from
 * {@code grails.openapi} for the default document and from {@code grails.openapi.groups.<name>} for
 * a group, and from a springdoc {@code GroupedOpenApi} where the application declares one, so a
 * document generated at build time and one springdoc serves select the same operations.</p>
 *
 * <p>A path criterion is an Ant pattern matched against the described path, such as
 * {@code /api/v1/**}. A package criterion names the package of the controller that serves the
 * operation, and matches its sub-packages too. A media type criterion is matched the way springdoc
 * matches a handler method: an operation matches only where it produces, or consumes, exactly the
 * media types the criterion lists. A URL mapping declares no header condition, so an operation
 * never matches a header criterion. A criterion left empty selects everything.</p>
 *
 * @since 8.0
 */
@CompileStatic
class OpenApiSelection {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher()

    /**
     * The group name, or {@code null} for the default document.
     */
    String group

    /**
     * The name a viewer shows for the group.
     */
    String displayName

    List<String> pathsToMatch = []

    List<String> pathsToExclude = []

    List<String> packagesToScan = []

    List<String> packagesToExclude = []

    /**
     * The media types an operation must produce: those of the formats its controller declares in
     * {@code responseFormats}, or {@code application/json} where it declares none.
     */
    List<String> producesToMatch = []

    /**
     * The media types an operation must consume: those it produces, where it binds a body.
     */
    List<String> consumesToMatch = []

    /**
     * The header conditions an operation must declare, which a URL mapping never does.
     */
    List<String> headersToMatch = []

    /**
     * Decide, from the method an action is declared as, whether its operations are described, the
     * way a springdoc method filter decides for a handler method. An action is described only
     * where every filter includes it.
     */
    List<Predicate<Method>> actionFilters = []

    /**
     * @return a selection with the same criteria, which can be added to without changing these
     */
    OpenApiSelection copy() {
        new OpenApiSelection(
                group: group,
                displayName: displayName,
                pathsToMatch: new ArrayList<String>(pathsToMatch),
                pathsToExclude: new ArrayList<String>(pathsToExclude),
                packagesToScan: new ArrayList<String>(packagesToScan),
                packagesToExclude: new ArrayList<String>(packagesToExclude),
                producesToMatch: new ArrayList<String>(producesToMatch),
                consumesToMatch: new ArrayList<String>(consumesToMatch),
                headersToMatch: new ArrayList<String>(headersToMatch),
                actionFilters: new ArrayList<Predicate<Method>>(actionFilters))
    }

    /**
     * @param path the described path, such as {@code /books/{id}}
     * @param controllerClass the controller serving the operation, if known
     * @return whether the operation belongs in the document
     */
    boolean selects(String path, Class<?> controllerClass) {
        selectsPath(path) && selectsPackage(controllerClass?.package?.name)
    }

    /**
     * @param action the method the action is declared as, if known
     * @return whether every action filter includes the action
     */
    boolean selectsAction(Method action) {
        action == null || actionFilters.every { Predicate<Method> filter -> filter.test(action) }
    }

    /**
     * @param produces the media types the operation responds in
     * @param consumes the media types the operation binds a body from, empty where it binds none
     * @return whether the operation's media types are the ones the criteria ask for
     */
    boolean selectsMediaTypes(Collection<String> produces, Collection<String> consumes) {
        matches(producesToMatch, produces) && matches(consumesToMatch, consumes) && !headersToMatch
    }

    private static boolean matches(List<String> criterion, Collection<String> declared) {
        !criterion || (declared && criterion.size() == declared.size() && criterion.containsAll(declared))
    }

    private boolean selectsPath(String path) {
        if (pathsToExclude.any { String pattern -> PATH_MATCHER.match(pattern, path) }) {
            return false
        }
        !pathsToMatch || pathsToMatch.any { String pattern -> PATH_MATCHER.match(pattern, path) }
    }

    private boolean selectsPackage(String packageName) {
        if (packageName == null) {
            // Nothing to test a package criterion against, so only an empty one can select it.
            return !packagesToScan
        }
        if (packagesToExclude.any { String excluded -> inPackage(packageName, excluded) }) {
            return false
        }
        !packagesToScan || packagesToScan.any { String scanned -> inPackage(packageName, scanned) }
    }

    private static boolean inPackage(String packageName, String candidate) {
        packageName == candidate || packageName.startsWith(candidate + '.')
    }
}

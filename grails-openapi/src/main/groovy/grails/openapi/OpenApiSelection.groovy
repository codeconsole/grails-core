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

import groovy.transform.CompileStatic
import groovy.transform.PackageScope

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.Operation
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
 * operation, and matches its sub-packages too. A media type or header criterion is matched the way
 * springdoc matches a handler method: an operation matches only where it produces, consumes, or
 * declares exactly what the criterion lists. A criterion left empty selects everything.</p>
 *
 * <p>A subclass can decide by the action itself, and customize each operation, by overriding
 * {@link #selectsAction} and {@link #customize}; springdoc's method filters and operation
 * customizers are applied that way.</p>
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
     * The title of the document, and the name a viewer shows for the group.
     */
    String displayName

    /**
     * Ant patterns of the paths described, such as {@code /api/v1/**}; empty for every path.
     */
    List<String> pathsToMatch = []

    /**
     * Ant patterns of the paths left out.
     */
    List<String> pathsToExclude = []

    /**
     * The packages, with their sub-packages, of the controllers whose operations are described;
     * empty for every package.
     */
    List<String> packagesToScan = []

    /**
     * The packages, with their sub-packages, of the controllers whose operations are left out.
     */
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
     * The header conditions an operation must declare, such as {@code Accept-Version=1.0}.
     */
    List<String> headersToMatch = []

    OpenApiSelection() {
    }

    /**
     * A selection with the same criteria as another.
     */
    OpenApiSelection(OpenApiSelection criteria) {
        group = criteria.group
        displayName = criteria.displayName
        pathsToMatch = new ArrayList<String>(criteria.pathsToMatch)
        pathsToExclude = new ArrayList<String>(criteria.pathsToExclude)
        packagesToScan = new ArrayList<String>(criteria.packagesToScan)
        packagesToExclude = new ArrayList<String>(criteria.packagesToExclude)
        producesToMatch = new ArrayList<String>(criteria.producesToMatch)
        consumesToMatch = new ArrayList<String>(criteria.consumesToMatch)
        headersToMatch = new ArrayList<String>(criteria.headersToMatch)
    }

    /**
     * Whether the action serving an operation is described, decided by the method it is declared
     * as. Every action is.
     *
     * @param action the method the action is declared as, the one taking its parameters
     */
    protected boolean selectsAction(Method action) {
        true
    }

    /**
     * Customizes an operation the document describes, once the annotations of its action are
     * applied. The operation is described as it is.
     *
     * @param controller the controller serving the action, where the application context holds one
     * @param action the method the action is declared as, if known
     * @return the operation to describe, or {@code null} to leave it out
     */
    protected Operation customize(Operation operation, Components components, Object controller, Method action) {
        operation
    }

    @PackageScope
    boolean selects(String path, Class<?> controllerClass) {
        selectsPath(path) && selectsPackage(controllerClass?.package?.name)
    }

    @PackageScope
    boolean selectsConditions(Collection<String> produces, Collection<String> consumes, Collection<String> headers) {
        matches(producesToMatch, produces) && matches(consumesToMatch, consumes) && matches(headersToMatch, headers)
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

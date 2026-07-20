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
package grails.compiler.beans;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.codehaus.groovy.transform.GroovyASTTransformationClass;

/**
 * Marks a class whose {@code beans} closure property is a bean-definition DSL that should be
 * compiled into real {@code @Bean} factory methods, so the class can serve as a plain
 * {@code @AutoConfiguration} with no closure DSL surviving into the compiled bytecode.
 *
 * <p>The annotated class must declare a {@code beans} property initialised to a closure whose
 * statements are calls of the form {@code bean(Type[, "name"]) { ... }}, optionally qualified
 * with {@code .conditionalOnMissingBean(Type...)}. The closure body becomes the generated
 * method's body verbatim, and closure parameters become the generated method's parameters
 * (for constructor-style bean injection).
 *
 * <p>May also be applied to a {@code grails.plugins.Plugin} subclass, letting bean definitions
 * live in the familiar {@code *GrailsPlugin.groovy} file. In that case the generated methods land
 * on a new sibling {@code <PluginClassName>AutoConfiguration} class instead - a {@code Plugin}
 * subclass is never processed by Spring as a bean - and any {@code @AutoConfiguration} annotation
 * on the plugin class moves onto that sibling, since it has no effect where the author wrote it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@GroovyASTTransformationClass("org.grails.compiler.beans.GrailsBeansASTTransformation")
public @interface GrailsBeans {
}

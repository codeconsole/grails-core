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
 * statements are one of:
 * <ul>
 * <li>{@code bean(Type[, "name"]) { ... }}, optionally chained with any combination of
 * {@code .conditionalOnMissingBean(Type...)}, {@code .primary()}, {@code .lazy()},
 * {@code .scope("name")}, {@code .named("...")}, and (repeatably)
 * {@code .annotate(AnnotationType[, attr: value, ...])} - the last a generic escape hatch
 * attaching any other single-valued annotation. The closure body becomes the generated method's
 * body verbatim, and closure parameters become the generated method's parameters (for
 * constructor-style bean injection). The bean name doubles as the generated method's name by
 * default; when it isn't a valid Java identifier (e.g. {@code "my-service"}), chain
 * {@code .named("...")} to give the method a different, valid name explicitly - omitting it is a
 * compile-time error in that case.</li>
 * <li>{@code field(Type[, "name"])}, optionally chained (repeatably) with
 * {@code .annotate(AnnotationType[, attr: value, ...])} - typically {@code .annotate(Value, value:
 * "${...}")}. Declares a private field on the generated class, for state shared across bean
 * methods (e.g. injected configuration).</li>
 * <li>{@code method(Type[, "name"]) { ... }}, with the same chaining as {@code field(...)}.
 * Declares a private helper method on the generated class, for logic shared across bean methods,
 * lifted from the closure the same way {@code bean(...)} is.</li>
 * </ul>
 * When no name is given, one is derived from the type name following the JavaBeans convention
 * ({@link java.beans.Introspector#decapitalize(String)}).
 *
 * <p>May also be applied to a {@code grails.plugins.Plugin} subclass, letting bean definitions
 * live in the familiar {@code *GrailsPlugin.groovy} file. In that case the generated methods land
 * on a new sibling {@code <PluginClassName>AutoConfiguration} class instead (or
 * {@link #autoConfigurationName} if given) - a {@code Plugin} subclass is never processed by
 * Spring as a bean - and {@code @AutoConfiguration} together with every annotation that gates or
 * configures it (the {@code @Conditional*} family, {@code @Import}/{@code @ImportAutoConfiguration},
 * {@code @EnableConfigurationProperties}, {@code @PropertySource},
 * {@code @AutoConfigureOrder}/{@code Before}/{@code After} - including any composed annotation
 * meta-annotated with one of these) found on the plugin class moves onto that sibling, since none
 * of them has any effect where the author wrote them.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@GroovyASTTransformationClass("org.grails.compiler.beans.GrailsBeansASTTransformation")
public @interface GrailsBeans {

    /**
     * The simple name of the generated sibling class, for a {@code grails.plugins.Plugin}
     * subclass. Defaults to {@code <PluginClassName>AutoConfiguration}; set this when converting
     * an existing public {@code @AutoConfiguration} class into the DSL and its class identity
     * must be preserved (e.g. for {@code exclude =} references, {@code before=}/{@code after=}
     * ordering from other modules, or tests that import it by name).
     */
    String autoConfigurationName() default "";

}

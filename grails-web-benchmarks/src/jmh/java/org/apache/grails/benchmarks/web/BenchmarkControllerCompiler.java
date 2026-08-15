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
package org.apache.grails.benchmarks.web;

import java.net.URL;

import groovy.lang.GroovyClassLoader;

import org.codehaus.groovy.control.CompilationUnit;

import grails.compiler.ast.ClassInjector;
import org.grails.compiler.injection.GrailsAwareClassLoader;
import org.grails.compiler.web.ControllerActionTransformer;

/**
 * Compiles controller sources through the real {@code ControllerActionTransformer}, so that the
 * bytecode a benchmark invokes is the bytecode a Grails application would run.
 *
 * <p>This is the same arrangement {@code ControllerActionTransformerSpec} uses: a
 * {@link GrailsAwareClassLoader} whose only URL-driven injector is the controller action
 * transformer, with {@code shouldInject} forced on because sources compiled from a String have no
 * {@code grails-app/controllers} URL to recognise. The {@code @Artefact('Controller')} annotation in
 * each source still drives the normal annotation-driven injection, which is what applies the
 * {@code Controller} trait.</p>
 */
public final class BenchmarkControllerCompiler {

    private BenchmarkControllerCompiler() {
    }

    /**
     * @return a class loader that runs the controller action transformer over everything it compiles
     */
    public static GroovyClassLoader newTransformingClassLoader() {
        GrailsAwareClassLoader classLoader = new GrailsAwareClassLoader();
        ControllerActionTransformer transformer = new ControllerActionTransformer() {
            @Override
            public boolean shouldInject(URL url) {
                return true;
            }
        };
        transformer.setCompilationUnit(new CompilationUnit());
        classLoader.setClassInjectors(new ClassInjector[] { transformer });
        return classLoader;
    }
}

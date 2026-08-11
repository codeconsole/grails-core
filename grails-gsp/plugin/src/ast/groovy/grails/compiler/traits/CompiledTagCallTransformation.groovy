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
package grails.compiler.traits

import groovy.transform.CompileStatic
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation

import grails.artefact.gsp.TagLibraryInvoker
import grails.gsp.taglib.compiler.CompiledTagCallRewriter
import org.grails.taglib.index.TagLibraryIndex

/**
 * Compiles a call to a known tag into a direct invocation, wherever tags can be called from.
 *
 * <p>A tag library rewrites its own calls as it is compiled, but a controller can call tags too, and
 * gains that ability from the {@link TagLibraryInvoker} trait rather than from being a tag library.
 * Any class carrying that trait is therefore a candidate, which covers controllers without naming
 * them and without a second copy of the rewriting rules.
 *
 * <p>Runs after trait injection, since whether a class can call tags is only settled once its traits
 * have been applied.
 *
 * @since 8.0.0
 */
@CompileStatic
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
class CompiledTagCallTransformation implements ASTTransformation {

    private static final ClassNode TAG_LIBRARY_INVOKER = ClassHelper.make(TagLibraryInvoker)

    @Override
    void visit(ASTNode[] nodes, SourceUnit source) {
        ModuleNode module = source.getAST()
        if (module == null) {
            return
        }
        TagLibraryIndex index = null
        for (ClassNode classNode : module.getClasses()) {
            if (!callsTags(classNode)) {
                continue
            }
            if (index == null) {
                index = TagLibraryIndex.load(source.getClassLoader())
                if (index.isEmpty()) {
                    return
                }
            }
            new CompiledTagCallRewriter(source, index, classNode).rewrite()
        }
    }

    /**
     * @return true when the class can call tags, which is what carrying the tag library invoker trait
     *         means, whether it is a controller, a tag library or anything else given that ability
     */
    private static boolean callsTags(ClassNode classNode) {
        classNode.implementsInterface(TAG_LIBRARY_INVOKER) || classNode.declaresInterface(TAG_LIBRARY_INVOKER)
    }
}

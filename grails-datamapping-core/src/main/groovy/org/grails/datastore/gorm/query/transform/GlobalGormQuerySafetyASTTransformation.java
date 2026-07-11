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
package org.grails.datastore.gorm.query.transform;

import java.util.List;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.transform.ASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformation;
import org.codehaus.groovy.transform.TransformWithPriority;

import org.apache.grails.common.compiler.GroovyTransformOrder;

/**
 * Global version of {@link GormQuerySafetyTransformer} - runs automatically against every class
 * in every Grails application that has {@code grails-datamapping-core} on its compile classpath,
 * with no developer configuration required.
 *
 * @since 8.1
 */
@GroovyASTTransformation(phase = CompilePhase.CANONICALIZATION)
public class GlobalGormQuerySafetyASTTransformation implements ASTTransformation, TransformWithPriority {

    public void visit(ASTNode[] nodes, SourceUnit source) {
        GormQuerySafetyTransformer transformer = new GormQuerySafetyTransformer(source);
        ModuleNode ast = source.getAST();
        List<ClassNode> classes = ast.getClasses();
        for (ClassNode aClass : classes) {
            transformer.visitClass(aClass);
        }
    }

    @Override
    public int priority() {
        return GroovyTransformOrder.QUERY_SAFETY_ORDER;
    }
}

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
package org.grails.orm.hibernate.query;

import java.util.List;

import org.hibernate.metamodel.model.domain.ReturnableType;
import org.hibernate.query.sqm.function.AbstractSqmSelfRenderingFunctionDescriptor;
import org.hibernate.query.sqm.produce.function.StandardArgumentsValidators;
import org.hibernate.query.sqm.produce.function.StandardFunctionReturnTypeResolvers;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.tree.SqlAstNode;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.Literal;
import org.hibernate.sql.ast.tree.expression.SqlTuple;
import org.hibernate.type.StandardBasicTypes;
import org.hibernate.type.spi.TypeConfiguration;

/**
 * Renders a {@link SqlRestriction} as a predicate. The arguments are the SQL condition as a literal, a column of
 * the queried entity whose table alias replaces {@code {alias}}, and the value of each {@code ?} placeholder.
 *
 * @since 8.0.0
 */
public class GrailsSqlRestrictionFunction extends AbstractSqmSelfRenderingFunctionDescriptor {

    public static final String NAME = "grails_sql_restriction";

    static final String ALIAS_PLACEHOLDER = "{alias}";

    public GrailsSqlRestrictionFunction(TypeConfiguration typeConfiguration) {
        super(
                NAME,
                StandardArgumentsValidators.min(2),
                StandardFunctionReturnTypeResolvers.invariant(
                        typeConfiguration.getBasicTypeRegistry().resolve(StandardBasicTypes.BOOLEAN)),
                null);
    }

    @Override
    public boolean isPredicate() {
        return true;
    }

    @Override
    public void render(
            SqlAppender sqlAppender,
            List<? extends SqlAstNode> arguments,
            ReturnableType<?> returnType,
            SqlAstTranslator<?> walker) {
        if (!(arguments.get(0) instanceof Literal literal) || !(literal.getLiteralValue() instanceof String sql)) {
            throw new IllegalArgumentException("The first argument of " + NAME + " must be the SQL as a literal");
        }
        if (sql.contains(ALIAS_PLACEHOLDER)) {
            sql = sql.replace(ALIAS_PLACEHOLDER, tableAlias(arguments.get(1)));
        }
        int index = 0;
        for (int i = 2; i < arguments.size(); i++) {
            int placeholder = sql.indexOf('?', index);
            if (placeholder == -1) {
                throw new IllegalArgumentException("The SQL restriction has fewer ? placeholders than values: " + sql);
            }
            sqlAppender.append(sql, index, placeholder);
            arguments.get(i).accept(walker);
            index = placeholder + 1;
        }
        if (sql.indexOf('?', index) != -1) {
            throw new IllegalArgumentException("The SQL restriction has more ? placeholders than values: " + sql);
        }
        sqlAppender.append(sql, index, sql.length());
    }

    private static String tableAlias(SqlAstNode column) {
        SqlAstNode node = column instanceof SqlTuple tuple ? tuple.getExpressions().get(0) : column;
        ColumnReference columnReference = node instanceof Expression expression ? expression.getColumnReference() : null;
        if (columnReference != null && columnReference.getQualifier() != null) {
            return columnReference.getQualifier();
        }
        throw new IllegalStateException("Cannot resolve the table alias for {alias} from " + column);
    }
}

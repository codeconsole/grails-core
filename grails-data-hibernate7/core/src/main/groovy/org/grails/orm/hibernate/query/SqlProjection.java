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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hibernate.type.BasicTypeReference;
import org.hibernate.type.Type;

import org.grails.datastore.mapping.query.Query;

/**
 * Projection that selects the value of a native SQL expression, created by {@code sqlProjection} or
 * {@code sqlGroupProjection} in a criteria query. In the SQL, {@code {alias}} stands for the table alias of the
 * queried entity.
 *
 * <p>The SQL of a {@code sqlProjection} may select several columns, separated by commas, each one optionally
 * followed by {@code as} and its column alias, as on Hibernate 5. It is split into one projection per column, so
 * every column is a selection of its own.</p>
 *
 * @since 8.0.0
 */
public class SqlProjection extends Query.Projection {

    private static final Pattern TRAILING_ALIAS = Pattern.compile(
            "(?is)^(.*?)\\s+as\\s+(?:\"([\\w$]+)\"|`([\\w$]+)`|\\[([\\w$]+)]|([\\w$]+))\\s*$");

    private final String sql;
    private final String columnAlias;
    private final Object declaredType;
    private final Class<?> type;

    /**
     * @param sql the SQL expression of the projected column, without its column alias
     * @param columnAlias the alias of the projected column
     * @param type the type of the projected value: an {@code org.hibernate.type.StandardBasicTypes} constant, an
     *     {@code org.hibernate.type.Type}, a Java class or {@code null} for a value of any type
     * @throws IllegalArgumentException if the SQL is empty or the type is none of these
     */
    public SqlProjection(String sql, String columnAlias, Object type) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("The SQL of a sqlProjection must not be empty");
        }
        this.sql = sql;
        this.columnAlias = columnAlias;
        this.declaredType = type;
        this.type = javaType(type);
    }

    /**
     * Returns the SQL expression of the projected column, without its column alias.
     */
    public String getSql() {
        return sql;
    }

    /**
     * Returns the alias of the projected column, which names it in an {@code order} of the criteria query.
     */
    public String getColumnAlias() {
        return columnAlias;
    }

    /**
     * Returns the Java type of the projected value.
     */
    public Class<?> getType() {
        return type;
    }

    /**
     * Returns the type of the projected value as it was given: an {@code org.hibernate.type.StandardBasicTypes}
     * constant or an {@code org.hibernate.type.Type}, which also tell how the value is read, a Java class or
     * {@code null}.
     */
    public Object getDeclaredType() {
        return declaredType;
    }

    /**
     * Splits the SQL of a {@code sqlProjection} or {@code sqlGroupProjection} into one projection per column.
     *
     * @param sql the SQL selecting the columns, separated by commas
     * @param columnAliases the alias of each column, in order
     * @param types the type of each column, in order: an {@code org.hibernate.type.StandardBasicTypes} constant, an
     *     {@code org.hibernate.type.Type} or a Java class
     * @return the projection of each column
     * @throws IllegalArgumentException if the number of columns, aliases and types differ
     */
    public static List<SqlProjection> of(String sql, List<String> columnAliases, List<?> types) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("The SQL of a sqlProjection must not be empty");
        }
        if (columnAliases == null || types == null || columnAliases.size() != types.size()) {
            throw new IllegalArgumentException("A sqlProjection needs as many types as column aliases: " + sql);
        }
        List<String> columns = splitColumns(sql);
        if (columns.size() != columnAliases.size()) {
            throw new IllegalArgumentException("The SQL of a sqlProjection selects " + columns.size() +
                    " columns but " + columnAliases.size() + " column aliases were given: " + sql);
        }
        List<SqlProjection> projections = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            String alias = columnAliases.get(i);
            projections.add(new SqlProjection(withoutAlias(columns.get(i), alias), alias, types.get(i)));
        }
        return projections;
    }

    /**
     * Returns the Java type of a projected value given as an {@code org.hibernate.type.StandardBasicTypes} constant,
     * an {@code org.hibernate.type.Type} or a Java class.
     */
    static Class<?> javaType(Object type) {
        if (type == null) {
            return Object.class;
        }
        if (type instanceof Class<?> javaClass) {
            return javaClass;
        }
        if (type instanceof BasicTypeReference<?> reference) {
            return reference.getJavaType();
        }
        if (type instanceof Type hibernateType) {
            return hibernateType.getReturnedClass();
        }
        throw new IllegalArgumentException("The type of a sqlProjection must be a StandardBasicTypes constant, " +
                "an org.hibernate.type.Type or a class, not " + type.getClass().getName());
    }

    /**
     * Splits SQL at the commas that separate its columns: those outside parentheses, square brackets, string
     * literals, quoted identifiers and comments.
     */
    static List<String> splitColumns(String sql) {
        List<String> columns = new ArrayList<>();
        int length = sql.length();
        int depth = 0;
        int start = 0;
        int i = 0;
        while (i < length) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = SqlRestriction.skipQuoted(sql, i, c);
            } else if (c == '-' && sql.startsWith("--", i)) {
                i = SqlRestriction.endOfLine(sql, i);
            } else if (c == '/' && sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                i = end == -1 ? length : end + 2;
            } else {
                if (c == '(' || c == '[') {
                    depth++;
                } else if (c == ')' || c == ']') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    columns.add(sql.substring(start, i).trim());
                    start = i + 1;
                }
                i++;
            }
        }
        columns.add(sql.substring(start).trim());
        return columns;
    }

    /**
     * Removes a trailing {@code as alias} naming the given column alias, which the selection carries instead, so the
     * expression can also be grouped and ordered by. The alias may be quoted with double quotes, backquotes or
     * square brackets.
     */
    static String withoutAlias(String column, String alias) {
        if (alias == null) {
            return column;
        }
        Matcher matcher = TRAILING_ALIAS.matcher(column);
        if (matcher.matches() && alias.equalsIgnoreCase(trailingAlias(matcher))) {
            return matcher.group(1).trim();
        }
        return column;
    }

    private static String trailingAlias(Matcher matcher) {
        for (int group = 2; group <= matcher.groupCount(); group++) {
            if (matcher.group(group) != null) {
                return matcher.group(group);
            }
        }
        return null;
    }
}

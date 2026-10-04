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
import java.util.Collections;
import java.util.List;

import org.grails.datastore.mapping.query.Query;

/**
 * Criterion that restricts the results with a native SQL condition, created by {@code sqlRestriction} in a
 * criteria query. In the SQL, {@code {alias}} stands for the table alias of the queried entity, and each
 * {@code ?} for one of the values, which are bound as parameters.
 *
 * @param sql the SQL condition
 * @param values the values of the {@code ?} placeholders, in order
 * @since 8.0.0
 */
public record SqlRestriction(String sql, List<?> values) implements Query.Criterion {

    public SqlRestriction {
        if (sql == null) {
            throw new IllegalArgumentException("The SQL of a sqlRestriction must not be null");
        }
        if (values == null) {
            values = List.of();
        }
        List<Object> copy = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value == null) {
                throw new IllegalArgumentException("The values of a sqlRestriction must not be null: " + sql);
            }
            copy.add(value instanceof CharSequence ? value.toString() : value);
        }
        long placeholders = sql.chars().filter(c -> c == '?').count();
        if (placeholders != copy.size()) {
            throw new IllegalArgumentException("The SQL of a sqlRestriction has " + placeholders +
                    " ? placeholders but " + copy.size() + " values: " + sql);
        }
        values = Collections.unmodifiableList(copy);
    }
}

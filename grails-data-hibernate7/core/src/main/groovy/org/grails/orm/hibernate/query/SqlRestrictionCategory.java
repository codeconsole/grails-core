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

import org.grails.datastore.gorm.query.criteria.AbstractDetachedCriteria;
import org.grails.datastore.mapping.query.api.Criteria;

/**
 * Category that adds {@code sqlRestriction} to the detached criteria of a criteria query's {@code and}, {@code or}
 * and {@code not} blocks, which these blocks resolve their calls against. A {@code sqlRestriction} in such a block,
 * or in an association block inside it, thus restricts the criteria it is called in.
 *
 * @since 8.0.0
 */
public final class SqlRestrictionCategory {

    private SqlRestrictionCategory() {
    }

    public static Criteria sqlRestriction(AbstractDetachedCriteria<?> self, String sqlRestriction) {
        return sqlRestriction(self, sqlRestriction, List.of());
    }

    public static Criteria sqlRestriction(AbstractDetachedCriteria<?> self, String sqlRestriction, List<?> values) {
        self.add(new SqlRestriction(sqlRestriction, values));
        return self;
    }
}

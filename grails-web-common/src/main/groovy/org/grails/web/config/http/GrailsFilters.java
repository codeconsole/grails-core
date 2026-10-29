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
package org.grails.web.config.http;

import org.springframework.core.Ordered;

/**
 * Stores the default order numbers of all Grails filters for use in configuration.
 * These filters are run prior to the Spring Security Filter Chain which is at DEFAULT_FILTER_ORDER.
 *
 * @since 7.0
 */
public enum GrailsFilters {

    /**
     * The first slot after the character encoding filter, which runs at {@link Ordered#HIGHEST_PRECEDENCE}. A filter
     * at this order runs ahead of the other Grails filters, with the request encoding already set.
     */
    FIRST,
    ASSET_PIPELINE_FILTER,

    /**
     * The order of the character encoding filter, {@link Ordered#HIGHEST_PRECEDENCE}, ahead of every other filter.
     * Spring Boot registers its encoding filter at this order. A filter at {@code CHARACTER_ENCODING_FILTER.getOrder() + 1}
     * runs right after it, with the request encoding set.
     *
     * @deprecated since 9.0, for removal. Grails no longer registers a character encoding filter of its own; order a
     * filter after Spring Boot's with {@code Ordered.HIGHEST_PRECEDENCE + 1}. The filters after this one in the enum
     * take their orders from their position, so when it is removed, give them their current orders explicitly.
     */
    @Deprecated(since = "9.0", forRemoval = true)
    CHARACTER_ENCODING_FILTER(Ordered.HIGHEST_PRECEDENCE),

    HIDDEN_HTTP_METHOD_FILTER,
    SITEMESH_FILTER,
    GRAILS_WEB_REQUEST_FILTER,
    LAST(GrailsFilterOrder.DEFAULT_FILTER_ORDER - 10);

    private static final int INTERVAL = 10;
    private final int order;

    GrailsFilters() {
        this.order = GrailsFilterOrder.DEFAULT_FILTER_ORDER - 100 + ordinal() * INTERVAL;
    }

    GrailsFilters(int order) {
        this.order = order;
    }

    public int getOrder() {
        return this.order;
    }

}

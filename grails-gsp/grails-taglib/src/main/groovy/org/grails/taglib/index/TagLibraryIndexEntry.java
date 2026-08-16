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
package org.grails.taglib.index;

/**
 * One tag recorded in the {@link TagLibraryIndex} at compile time.
 *
 * @param namespace the tag library namespace the tag is reachable through
 * @param tagName the tag name within that namespace
 * @param tagLibraryClassName the binary name of the tag library declaring the tag
 * @param kind how the tag is implemented
 * @param acceptsBody whether the tag can be called with a body
 * @since 8.0.0
 */
public record TagLibraryIndexEntry(String namespace, String tagName, String tagLibraryClassName,
        Kind kind, boolean acceptsBody) {

    /**
     * How a tag is implemented.
     *
     * <p>No call-site decision turns on this. A resolved call is compiled into an invocation that
     * selects the tag by name when it runs, and a closure answers to a name as readily as a method
     * does, so both forms are compiled the same way.
     *
     * <p>It is recorded because it is the difference between a tag a caller could one day bind to a
     * signature and one that could never carry a signature to bind to. Binding to a specific method
     * is not part of this release; recording the distinction now means the descriptor format does not
     * have to change when it is.
     */
    public enum Kind {

        /**
         * A method, which carries a signature.
         */
        METHOD,

        /**
         * A {@code Closure} field, the deprecated form, which carries none.
         */
        LEGACY_CLOSURE
    }

    /**
     * @return true when the tag carries a signature a caller could be bound to. Not consulted when
     *         deciding whether to compile a call: see {@link Kind}.
     */
    public boolean isBindable() {
        return kind == Kind.METHOD;
    }
}

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
package org.grails.taglib;

import java.util.Collections;
import java.util.Map;

import org.grails.taglib.encoder.OutputContext;
import org.grails.taglib.encoder.OutputContextLookupHelper;

/**
 * Invokes a tag whose namespace and name are known without going through Groovy's method dispatch.
 *
 * <p>Calling a tag as {@code g.message(code: 'x')} reaches the tag library through {@code
 * invokeMethod}, which means a dynamic call site in the caller's bytecode even when that caller is
 * statically compiled. The tag being called is fixed in the source, so once it has been resolved
 * against the tag library index there is nothing left to decide at runtime beyond which bean holds it.
 *
 * <p>This is the entry point such a call is expressed as: an ordinary method call taking the
 * namespace and name as arguments. It applies the same attribute and body handling, output capture,
 * encoding and return-object behaviour as the dynamic path, because both end at
 * {@link TagOutput#captureTagOutput}.
 *
 * @since 8.0.0
 */
public final class CompiledTagInvocation {

    private CompiledTagInvocation() {
    }

    /**
     * Invokes a tag with attributes and a body.
     *
     * @param lookup the tag libraries available to the caller
     * @param namespace the tag library namespace
     * @param tagName the tag name within that namespace
     * @param attrs the tag attributes, treated as empty when {@code null}
     * @param body the tag body as a closure or as text, or {@code null} when there is none
     * @return whatever the tag produces, which for a tag that writes to the output is its output
     */
    public static Object invoke(TagLibraryLookup lookup, String namespace, String tagName,
            Map<?, ?> attrs, Object body) {
        return invoke(lookup, namespace, tagName, attrs, body,
                OutputContextLookupHelper.lookupOutputContext());
    }

    /**
     * Invokes a tag against a known output context, for a caller that already has one to hand.
     *
     * @param lookup the tag libraries available to the caller
     * @param namespace the tag library namespace
     * @param tagName the tag name within that namespace
     * @param attrs the tag attributes, treated as empty when {@code null}
     * @param body the tag body as a closure or as text, or {@code null} when there is none
     * @param outputContext where the tag writes
     * @return whatever the tag produces
     */
    public static Object invoke(TagLibraryLookup lookup, String namespace, String tagName,
            Map<?, ?> attrs, Object body, OutputContext outputContext) {
        if (lookup == null) {
            throw new GrailsTagException("Tag [" + tagName + "] cannot be invoked without a tag library lookup");
        }
        Map<?, ?> attributes = attrs != null ? attrs : Collections.emptyMap();
        // A body may be a closure or the text a caller wrote directly, which the dynamic path accepted
        // through overloads that wrapped the text. Narrowing this to Closure would turn a string body
        // into a cast failure.
        Object tagBody = body instanceof CharSequence ? new TagOutput.ConstantClosure((CharSequence) body) : body;
        return TagOutput.captureTagOutput(lookup, namespace, tagName, attributes, tagBody, outputContext);
    }
}

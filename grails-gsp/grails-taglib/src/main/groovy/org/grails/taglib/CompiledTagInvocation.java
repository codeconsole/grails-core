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
import java.util.LinkedHashMap;
import java.util.Map;

import groovy.lang.Closure;

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

    private static final Object[] EMPTY_ARGUMENTS = new Object[0];

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

    /**
     * Invokes a tag with whatever arguments the call was written with.
     *
     * <p>A tag call is written in more shapes than attributes and a body: with nothing, with a body
     * alone, or with a single value that the tag reads under its own name. Where the shape is not
     * evident in the source - a map held in a variable, say - the arguments are only known once they
     * have been evaluated, which is what this takes.
     *
     * @param lookup the tag libraries available to the caller
     * @param namespace the tag library namespace
     * @param tagName the tag name within that namespace
     * @param args the evaluated arguments, in the order they were written
     * @return whatever the tag produces
     */
    public static Object invokeArguments(TagLibraryLookup lookup, String namespace, String tagName,
            Object... args) {
        return invokeArgumentsInContext(lookup, namespace, tagName,
                OutputContextLookupHelper.lookupOutputContext(), args);
    }

    /**
     * Invokes a tag with whatever arguments the call was written with, against a known output context.
     *
     * @param lookup the tag libraries available to the caller
     * @param namespace the tag library namespace
     * @param tagName the tag name within that namespace
     * @param outputContext where the tag writes
     * @param args the evaluated arguments, in the order they were written
     * @return whatever the tag produces
     */
    public static Object invokeArgumentsInContext(TagLibraryLookup lookup, String namespace,
            String tagName, OutputContext outputContext, Object... args) {
        Object[] arguments = args != null ? args : EMPTY_ARGUMENTS;
        Map<?, ?> attrs = Collections.emptyMap();
        Object body = null;
        // Deliberately the same shapes, in the same order, as the dynamic dispatch in
        // TagLibraryMetaUtils.methodMissingForTagLib, including its treatment of argument lists that
        // match none of them: a call that produced an empty invocation there must produce one here.
        switch (arguments.length) {
            case 0:
                break;
            case 1:
                if (arguments[0] instanceof Map<?, ?> map) {
                    attrs = map;
                }
                else if (arguments[0] instanceof Closure || arguments[0] instanceof CharSequence) {
                    body = arguments[0];
                }
                else {
                    Map<String, Object> named = new LinkedHashMap<>(1);
                    named.put(tagName, arguments[0]);
                    attrs = named;
                }
                break;
            case 2:
                if (arguments[0] instanceof Map<?, ?> map) {
                    attrs = map;
                    body = arguments[1];
                }
                break;
            default:
                break;
        }
        return invoke(lookup, namespace, tagName, attrs, body, outputContext);
    }
}

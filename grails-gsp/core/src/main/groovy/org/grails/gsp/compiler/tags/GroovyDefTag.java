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
package org.grails.gsp.compiler.tags;

import grails.util.GrailsStringUtils;
import org.grails.taglib.GrailsTagException;

/**
 * Allows defining of variables within the page context.
 *
 * @author Graeme Rocher
 */
public class GroovyDefTag extends GroovySyntaxTag {

    public static final String TAG_NAME = "def";
    private static final String ATTRIBUTE_VALUE = "value";
    private static final String ATTRIBUTE_TYPE = "type";

    public void doStartTag() {
        String expr = attributes.get(ATTRIBUTE_VALUE);
        if (GrailsStringUtils.isBlank(expr)) {
            throw new GrailsTagException("Tag [" + TAG_NAME + "] missing required attribute [" + ATTRIBUTE_VALUE + "]", parser.getPageName(), parser.getCurrentOutputLineNumber());
        }
        expr = groovyExpressionFor(expr);

        String var = attributes.get(ATTRIBUTE_VAR);
        if (GrailsStringUtils.isBlank(var)) {
            throw new GrailsTagException("Tag [" + TAG_NAME + "] missing required attribute [" + ATTRIBUTE_VAR + "]", parser.getPageName(), parser.getCurrentOutputLineNumber());
        }
        var = extractAttributeValue(var);

        String typeName = attributes.get(ATTRIBUTE_TYPE);
        if (GrailsStringUtils.isBlank(typeName)) {
            typeName = "def";
        } else {
            typeName = extractAttributeValue(typeName);
        }

        out.print(typeName + " ");
        out.print(var);
        out.print('=');

        if (typeName.equals("def") || typeName.equals("Object")) {
            out.println(expr);
        } else {
            // A Groovy cast rather than Class.cast, which only accepts what is already of the type
            // and so rejects every conversion Groovy would otherwise have made. The expression is
            // parenthesised because it is written by the page and may be a GString or an operation.
            out.println("(" + typeName + ") (" + expr + ")");
        }
    }

    /**
     * The Groovy an attribute value stands for.
     *
     * <p>A lone <code>${...}</code> is the expression it holds, and text with no expression in it is
     * read as written, which is what an untyped tag naming a variable relies on. Text mixing the two,
     * or holding more than one expression, is neither: it is a GString, and emitting it unquoted
     * produced source that did not parse.
     */
    private static String groovyExpressionFor(String value) {
        String text = value.trim();
        if ((text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))) {
            text = text.substring(1, text.length() - 1).trim();
        }
        if (!text.contains("${")) {
            return text;
        }
        if (text.startsWith("${") && text.endsWith("}") && text.indexOf("${", 2) < 0) {
            return text.substring(2, text.length() - 1).trim();
        }
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    public void doEndTag() {
        // do nothing
    }

    public String getName() {
        return TAG_NAME;
    }

    @Override
    public boolean isKeepPrecedingWhiteSpace() {
        return true;
    }

    @Override
    public boolean isAllowPrecedingContent() {
        return true;
    }
}

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
package org.apache.grails.benchmarks.web;

import jakarta.servlet.ServletContext;

import org.springframework.mock.web.MockHttpServletRequest;

/**
 * A mock request that counts attribute operations, used once outside the measured region to report
 * how much request-attribute bookkeeping a generated controller action actually performs.
 *
 * <p>This exists so a benchmark can state what it measures rather than assert it: the count printed
 * during setup is the difference the generated code makes, independent of the timing numbers.</p>
 */
public class AttributeCountingRequest extends MockHttpServletRequest {

    private int getAttributeCount;

    private int setAttributeCount;

    private int removeAttributeCount;

    public AttributeCountingRequest(ServletContext servletContext, String method, String requestUri) {
        super(servletContext, method, requestUri);
    }

    @Override
    public Object getAttribute(String name) {
        this.getAttributeCount++;
        return super.getAttribute(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        this.setAttributeCount++;
        super.setAttribute(name, value);
    }

    @Override
    public void removeAttribute(String name) {
        this.removeAttributeCount++;
        super.removeAttribute(name);
    }

    public void resetCounts() {
        this.getAttributeCount = 0;
        this.setAttributeCount = 0;
        this.removeAttributeCount = 0;
    }

    public String describeCounts() {
        return "getAttribute=" + this.getAttributeCount
                + " setAttribute=" + this.setAttributeCount
                + " removeAttribute=" + this.removeAttributeCount;
    }
}

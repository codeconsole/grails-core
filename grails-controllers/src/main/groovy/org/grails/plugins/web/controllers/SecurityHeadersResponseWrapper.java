/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.grails.plugins.web.controllers;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Response wrapper that runs a callback exactly once, immediately before the response
 * is committed. Every commit path is intercepted: redirects, errors, explicit buffer
 * flushes, flushing or closing the writer or output stream, and writes that reach a
 * declared {@code Content-Length}. If the wrapped chain returns without committing,
 * the owner is expected to invoke {@link #beforeCommit()} itself.
 *
 * <p>Writing headers this late lets anything further down the filter chain (a
 * controller, an interceptor, Spring Security's header writers, another filter) set
 * its own value first; the callback only fills what is still missing.</p>
 */
final class SecurityHeadersResponseWrapper extends HttpServletResponseWrapper {

    private static final String CONTENT_LENGTH = "Content-Length";

    private final Runnable beforeCommit;

    private boolean fired;

    private long contentLength = -1;

    private long contentWritten;

    SecurityHeadersResponseWrapper(HttpServletResponse response, Runnable beforeCommit) {
        super(response);
        this.beforeCommit = beforeCommit;
    }

    /**
     * Runs the callback if it has not run yet. Safe to call repeatedly.
     */
    void beforeCommit() {
        if (!this.fired) {
            this.fired = true;
            this.beforeCommit.run();
        }
    }

    @Override
    public void sendError(int sc) throws IOException {
        beforeCommit();
        super.sendError(sc);
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
        beforeCommit();
        super.sendError(sc, msg);
    }

    @Override
    public void sendRedirect(String location) throws IOException {
        beforeCommit();
        super.sendRedirect(location);
    }

    @Override
    public void sendRedirect(String location, int sc) throws IOException {
        beforeCommit();
        super.sendRedirect(location, sc);
    }

    @Override
    public void sendRedirect(String location, boolean clearBuffer) throws IOException {
        beforeCommit();
        super.sendRedirect(location, clearBuffer);
    }

    @Override
    public void sendRedirect(String location, int sc, boolean clearBuffer) throws IOException {
        beforeCommit();
        super.sendRedirect(location, sc, clearBuffer);
    }

    @Override
    public void flushBuffer() throws IOException {
        beforeCommit();
        super.flushBuffer();
    }

    @Override
    public void setContentLength(int len) {
        this.contentLength = len;
        super.setContentLength(len);
    }

    @Override
    public void setContentLengthLong(long len) {
        this.contentLength = len;
        super.setContentLengthLong(len);
    }

    @Override
    public void setHeader(String name, String value) {
        trackContentLengthHeader(name, value);
        super.setHeader(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        trackContentLengthHeader(name, value);
        super.addHeader(name, value);
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        return new CommitAwareOutputStream(super.getOutputStream());
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        return new CommitAwareWriter(super.getWriter());
    }

    private void trackContentLengthHeader(String name, String value) {
        if (CONTENT_LENGTH.equalsIgnoreCase(name) && value != null) {
            try {
                this.contentLength = Long.parseLong(value.trim());
            }
            catch (NumberFormatException ignored) {
                // The container rejects or ignores a malformed Content-Length; nothing to track.
            }
        }
    }

    private void trackWritten(long count) {
        if (this.fired) {
            return;
        }
        this.contentWritten += count;
        if (this.contentLength >= 0 && this.contentWritten >= this.contentLength) {
            beforeCommit();
        }
    }

    private final class CommitAwareOutputStream extends ServletOutputStream {

        private final ServletOutputStream delegate;

        CommitAwareOutputStream(ServletOutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            trackWritten(1);
            this.delegate.write(b);
        }

        @Override
        public void write(byte[] b) throws IOException {
            trackWritten(b.length);
            this.delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            trackWritten(len);
            this.delegate.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            beforeCommit();
            this.delegate.flush();
        }

        @Override
        public void close() throws IOException {
            beforeCommit();
            this.delegate.close();
        }

        @Override
        public boolean isReady() {
            return this.delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            this.delegate.setWriteListener(listener);
        }
    }

    private final class CommitAwareWriter extends PrintWriter {

        CommitAwareWriter(PrintWriter delegate) {
            super(delegate, false);
        }

        @Override
        public void write(int c) {
            trackWritten(1);
            super.write(c);
        }

        @Override
        public void write(char[] buf, int off, int len) {
            trackWritten(len);
            super.write(buf, off, len);
        }

        @Override
        public void write(String s, int off, int len) {
            trackWritten(len);
            super.write(s, off, len);
        }

        @Override
        public void flush() {
            beforeCommit();
            super.flush();
        }

        @Override
        public void close() {
            beforeCommit();
            super.close();
        }
    }
}

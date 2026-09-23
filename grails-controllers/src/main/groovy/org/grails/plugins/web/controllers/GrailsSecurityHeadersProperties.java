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
package org.grails.plugins.web.controllers;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "grails.security.headers")
public class GrailsSecurityHeadersProperties {

    private boolean enabled = true;

    private Defaults defaults = Defaults.AUTO;

    private Header contentTypeOptions = new Header(true, "nosniff");

    private Header frameOptions = new Header(true, "SAMEORIGIN");

    private Header referrerPolicy = new Header(true, "strict-origin-when-cross-origin");

    private Header xssProtection = new Header(true, "0");

    private Header hsts = new Header(false, "max-age=31536000");

    private Header contentSecurityPolicy = new Header(false, null);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Defaults getDefaults() {
        return defaults;
    }

    public void setDefaults(Defaults defaults) {
        this.defaults = defaults;
    }

    public Header getContentTypeOptions() {
        return contentTypeOptions;
    }

    public void setContentTypeOptions(Header contentTypeOptions) {
        this.contentTypeOptions = contentTypeOptions;
    }

    public Header getFrameOptions() {
        return frameOptions;
    }

    public void setFrameOptions(Header frameOptions) {
        this.frameOptions = frameOptions;
    }

    public Header getReferrerPolicy() {
        return referrerPolicy;
    }

    public void setReferrerPolicy(Header referrerPolicy) {
        this.referrerPolicy = referrerPolicy;
    }

    public Header getXssProtection() {
        return xssProtection;
    }

    public void setXssProtection(Header xssProtection) {
        this.xssProtection = xssProtection;
    }

    public Header getHsts() {
        return hsts;
    }

    public void setHsts(Header hsts) {
        this.hsts = hsts;
    }

    public Header getContentSecurityPolicy() {
        return contentSecurityPolicy;
    }

    public void setContentSecurityPolicy(Header contentSecurityPolicy) {
        this.contentSecurityPolicy = contentSecurityPolicy;
    }

    /**
     * Controls when the built-in default header values are applied. Headers the
     * application configured explicitly (any {@code grails.security.headers.<header>.*}
     * key) are always applied, whatever this setting says.
     */
    public enum Defaults {

        /**
         * Apply the defaults unless the request is detected as having come through a
         * reverse proxy, in which case only explicitly configured headers are sent.
         */
        AUTO,

        /**
         * Apply the defaults on every response, even behind a reverse proxy.
         */
        ALWAYS,

        /**
         * Never apply the defaults; only explicitly configured headers are sent.
         */
        NEVER
    }

    public static class Header {

        private boolean enabled;

        private String value;

        private boolean explicit;

        public Header() {
        }

        Header(boolean enabled, String value) {
            this.enabled = enabled;
            this.value = value;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
            this.explicit = true;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
            this.explicit = true;
        }

        /**
         * Whether the application configured this header itself rather than relying on
         * the built-in default. Explicitly configured headers are applied regardless of
         * {@link Defaults} and reverse proxy detection.
         */
        public boolean isExplicit() {
            return explicit;
        }
    }
}

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
package com.example

import groovy.transform.CompileStatic
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.core.context.SecurityContext
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@CompileStatic
class LoginRequestTraceFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
        String requestSession = request.requestedSessionId
        long started = System.nanoTime()
        try {
            chain.doFilter(request, response)
        } finally {
            if (true) {
                def session = request.getSession(false)
                SecurityContext context = (SecurityContext) session?.getAttribute('SPRING_SECURITY_CONTEXT')
                System.out.println("LOGIN_TRACE ${request.method} ${request.requestURI} status=${response.status} " +
                        "location=${response.getHeader('Location')} authenticated=${context?.authentication?.authenticated} " +
                        "requestedSession=${requestSession?.hashCode()} session=${session?.id?.hashCode()} " +
                        "elapsedMs=${(System.nanoTime() - started) / 1000000} " +
                        "query=${request.queryString} dest=${request.getHeader('Sec-Fetch-Dest')} " +
                        "referer=${request.getHeader('Referer')} dispatcher=${request.dispatcherType}")
            }
        }
    }
}

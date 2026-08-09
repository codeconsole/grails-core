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
package org.grails.gradle.plugin.aot

import java.util.concurrent.CountDownLatch

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer

/**
 * An application for the trace to record: it serves a form, and writes down what it was asked for.
 *
 * <p>The form is the point. A page can be fetched by anything; only submitting one shows whether the
 * fields a form declares are the fields that arrive.</p>
 */
class TracedRunFixture {

    static void main(String[] args) {
        int port = Integer.parseInt(args[0])
        File requested = new File(args[1])
        String behaviour = args.length > 2 ? args[2] : 'ok'

        HttpServer server = HttpServer.create(new InetSocketAddress('localhost', port), 0)
        server.createContext('/') { HttpExchange exchange ->
            String path = exchange.requestURI.path
            String method = exchange.requestMethod
            String body = method == 'POST' ? exchange.requestBody.getText('UTF-8') : ''
            requested << "${method} ${path}${body ? ' ' + body : ''}\n"

            int status = 200
            String response = 'served'
            if (path == '/broken') {
                status = 500
                response = 'no'
            }
            else if (path == '/create') {
                response = '''<html><body>
                    <form action="/save" method="post">
                        <input type="hidden" name="_csrf" value="a-token"/>
                        <input type="text" name="title"/>
                        <input type="password" name="secret"/>
                        <input type="checkbox" name="published"/>
                        <input type="submit" name="go" value="Save"/>
                    </form></body></html>'''
            }
            else if (path == '/save' && behaviour == 'save-fails') {
                status = 500
                response = 'no'
            }

            byte[] bytes = response.bytes
            exchange.sendResponseHeaders(status, bytes.length)
            exchange.responseBody.withCloseable { it.write(bytes) }
        }
        server.start()
        println 'Started Application in 0.05 seconds'
        System.out.flush()
        new CountDownLatch(1).await()
    }
}

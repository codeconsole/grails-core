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
package grails.async

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor

import org.grails.async.factory.PromiseFactoryBuilder
import org.grails.async.factory.future.CompletableFuturePromise
import org.grails.async.factory.future.CompletableFuturePromiseFactory
import spock.lang.Specification

class CompletableFuturePromiseFactorySpec extends Specification {

    private final Executor sameThreadExecutor = { Runnable task -> task.run() }
    private CompletableFuturePromiseFactory factory

    void setup() {
        factory = new CompletableFuturePromiseFactory(sameThreadExecutor)
        Promises.promiseFactory = factory
    }

    void cleanup() {
        Promises.promiseFactory = null
    }

    void 'uses a supplied executor and exposes JDK completion-stage behavior'() {
        when:
        Promise<Integer> promise = factory.createPromise { 21 * 2 }

        then:
        promise instanceof CompletableFuturePromise
        ((CompletableFuturePromise<Integer>) promise).thenApply { it + 1 }.get() == 43
    }

    void 'preserves falsey values'() {
        expect:
        factory.createPromise(work).get() == expected

        where:
        work          || expected
        ({ false })   || false
        ({ 0 })       || 0
        ({ '' })      || ''
        ({ [] })      || []
        ({ null })    || null
    }

    void 'chains completion callbacks without custom callback queues'() {
        expect:
        factory.createPromise { 2 }.then { it * 4 }.then { it + 2 }.get() == 10
    }

    void 'invokes error callbacks and retains exceptional completion'() {
        given:
        Throwable observed

        when:
        factory.createPromise { throw new IllegalStateException('bad') }
                .onError { Throwable failure -> observed = failure }
                .get()

        then:
        ExecutionException failure = thrown()
        failure.cause instanceof IllegalStateException
        observed.is(failure.cause)
    }

    void 'uses the modern factory by default'() {
        expect:
        new PromiseFactoryBuilder().build() instanceof CompletableFuturePromiseFactory
    }
}

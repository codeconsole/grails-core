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
package org.grails.plugins.web.async

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import org.springframework.beans.factory.support.BeanRegistryAdapter
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.core.task.TaskDecorator
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.support.TaskExecutorAdapter
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

import grails.async.PromiseFactory
import org.grails.plugins.web.async.mvc.AsyncActionResultTransformer

import spock.lang.Specification

class ControllersAsyncGrailsPluginSpec extends Specification {

    void cleanup() {
        grails.async.Promises.promiseFactory = null
        grails.async.web.WebPromises.promiseFactory = null
    }

    void "beanRegistrar registers the async promise beans"() {
        given:
        def beanFactory = new DefaultListableBeanFactory()
        def registrar = new ControllersAsyncGrailsPlugin().beanRegistrar()

        when:
        new BeanRegistryAdapter(beanFactory, new StandardEnvironment(), registrar.getClass()).register(registrar)

        then:
        beanFactory.getBeanDefinition('asyncPromiseResponseActionResultTransformer').beanClassName == AsyncActionResultTransformer.name
        beanFactory.getBeanDefinition('grailsPromiseFactory').beanClassName == PromiseFactory.name
        beanFactory.getBeanDefinition('grailsWebRequestTaskDecorator').beanClassName == TaskDecorator.name
        beanFactory.getBeanDefinition('grailsPromiseExecutor').fallback
    }

    void 'promise factory uses the application task executor'() {
        given:
        def beanFactory = new DefaultListableBeanFactory()
        AsyncTaskExecutor executor = new TaskExecutorAdapter(new SyncTaskExecutor())
        beanFactory.registerSingleton('applicationTaskExecutor', executor)
        beanFactory.registerSingleton('taskScheduler', new ThreadPoolTaskScheduler())
        beanFactory.registerSingleton('otherExecutor', new TaskExecutorAdapter(new SyncTaskExecutor()))
        def registrar = new ControllersAsyncGrailsPlugin().beanRegistrar()
        new BeanRegistryAdapter(beanFactory, new StandardEnvironment(), registrar.getClass()).register(registrar)

        when:
        PromiseFactory promiseFactory = beanFactory.getBean('grailsPromiseFactory', PromiseFactory)

        then:
        promiseFactory.createPromise { Thread.currentThread() }.get().is(Thread.currentThread())
    }

    void 'promise factory uses a managed fallback executor when Boot does not provide one'() {
        given:
        def beanFactory = new DefaultListableBeanFactory()
        beanFactory.registerSingleton('taskScheduler', new ThreadPoolTaskScheduler())
        def registrar = new ControllersAsyncGrailsPlugin().beanRegistrar()
        new BeanRegistryAdapter(beanFactory, new StandardEnvironment(), registrar.getClass()).register(registrar)

        when:
        PromiseFactory promiseFactory = beanFactory.getBean('grailsPromiseFactory', PromiseFactory)
        Thread worker = promiseFactory.createPromise { Thread.currentThread() }.get()

        then:
        worker.name.startsWith('grails-promise-')

        cleanup:
        beanFactory.destroySingletons()
    }

    void 'fallback executes dependent promises concurrently'() {
        given:
        def beanFactory = new DefaultListableBeanFactory()
        def registrar = new ControllersAsyncGrailsPlugin().beanRegistrar()
        new BeanRegistryAdapter(beanFactory, new StandardEnvironment(), registrar.getClass()).register(registrar)
        def factory = beanFactory.getBean('grailsPromiseFactory', PromiseFactory)
        def released = new CountDownLatch(1)

        when:
        def first = factory.createPromise { released.await(5, TimeUnit.SECONDS) }
        def second = factory.createPromise { released.countDown() }

        then:
        first.get(10, TimeUnit.SECONDS)
        second.get(10, TimeUnit.SECONDS) == null

        cleanup:
        released.countDown()
        beanFactory.destroySingletons()
    }
}

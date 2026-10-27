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
package org.apache.grails.data.hibernate5.core.querytail

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection

import groovy.transform.CompileStatic
import org.hibernate.boot.registry.StandardServiceInitiator
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.engine.jdbc.connections.internal.ConnectionProviderInitiator
import org.hibernate.engine.jdbc.connections.internal.MultiTenantConnectionProviderInitiator
import org.hibernate.service.Service
import org.hibernate.service.spi.Configurable
import org.hibernate.service.spi.ServiceBinding
import org.hibernate.service.spi.ServiceRegistryAwareService
import org.hibernate.service.spi.ServiceRegistryImplementor
import org.hibernate.service.spi.Startable
import org.hibernate.service.spi.Stoppable

import org.apache.grails.datamapping.core.querytail.QueryTailJdbcConnection
import org.apache.grails.datamapping.core.querytail.QueryTailRecorder

/**
 * Decorates the provider Hibernate selects, preserving its configuration and service lifecycle.
 * Connections are captured at execution and returned to their provider as the original instances.
 *
 * @since 9.0
 */
@CompileStatic
final class QueryTailConnectionProvider<S extends Service> implements StandardServiceInitiator<S> {

    private final StandardServiceInitiator<S> delegate
    private final QueryTailRecorder recorder

    private QueryTailConnectionProvider(StandardServiceInitiator<S> delegate, QueryTailRecorder recorder) {
        this.delegate = delegate
        this.recorder = recorder
    }

    static void install(StandardServiceRegistryBuilder builder, QueryTailRecorder recorder) {
        builder.addInitiator(new QueryTailConnectionProvider<>(ConnectionProviderInitiator.INSTANCE, recorder))
        builder.addInitiator(new QueryTailConnectionProvider<>(MultiTenantConnectionProviderInitiator.INSTANCE, recorder))
    }

    @Override
    Class<S> getServiceInitiated() {
        delegate.serviceInitiated
    }

    @Override
    S initiateService(Map<String, Object> settings, ServiceRegistryImplementor registry) {
        S provider = delegate.initiateService(settings, registry)
        if (provider == null) {
            return null
        }
        List<Class<?>> interfaces = [serviceInitiated, ServiceRegistryAwareService]
        for (Class<?> lifecycle in [Configurable, Startable, Stoppable]) {
            if (lifecycle.isInstance(provider)) {
                interfaces.add(lifecycle)
            }
        }
        ServiceBinding.ServiceLifecycleOwner owner = registry.locateServiceBinding(serviceInitiated).lifecycleOwner
        ServiceBinding<S> binding = new ServiceBinding<>(owner, serviceInitiated, provider)
        serviceInitiated.cast(Proxy.newProxyInstance(serviceInitiated.classLoader, interfaces as Class<?>[],
                new Connections(provider, binding, recorder)))
    }

    @CompileStatic
    private static final class Connections implements InvocationHandler {

        private final Service provider
        private final ServiceBinding<?> binding
        private final QueryTailRecorder recorder

        Connections(Service provider, ServiceBinding<?> binding, QueryTailRecorder recorder) {
            this.provider = provider
            this.binding = binding
            this.recorder = recorder
        }

        @Override
        Object invoke(Object proxy, Method method, Object[] args) {
            if (method.name == 'injectServices') {
                // Let Hibernate perform both annotated injections and ServiceRegistryAwareService callbacks.
                binding.lifecycleOwner.injectDependencies(binding)
                return null
            }
            Object[] supplied = args == null ? null : args.clone()
            if (supplied != null) {
                for (int i = 0; i < supplied.length; i++) {
                    if (supplied[i] instanceof Connection) {
                        supplied[i] = QueryTailJdbcConnection.original((Connection) supplied[i])
                    }
                }
            }
            try {
                Object result = method.invoke(provider, supplied)
                // Bootstrap schema work precedes the session factory and must not open a capture on a failed build.
                result instanceof Connection && recorder.capture != null ?
                        QueryTailJdbcConnection.wrap((Connection) result, recorder) : result
            }
            catch (InvocationTargetException e) {
                throw e.cause
            }
        }
    }
}

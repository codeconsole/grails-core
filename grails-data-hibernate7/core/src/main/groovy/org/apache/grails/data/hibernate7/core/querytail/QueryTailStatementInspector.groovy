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
package org.apache.grails.data.hibernate7.core.querytail

import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.hibernate.SessionFactory
import org.hibernate.SessionFactoryObserver
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.boot.registry.selector.spi.StrategySelectionException
import org.hibernate.boot.registry.selector.spi.StrategySelector
import org.hibernate.cfg.AvailableSettings
import org.hibernate.cfg.Configuration
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.resource.jdbc.spi.StatementInspector

import org.apache.grails.datamapping.core.querytail.QueryTailCapture
import org.apache.grails.datamapping.core.querytail.QueryTailDialects
import org.apache.grails.datamapping.core.querytail.QueryTailRecorder
import org.apache.grails.datastore.core.querytail.QueryTailSettings
import org.grails.datastore.mapping.core.exceptions.ConfigurationException
import org.grails.orm.hibernate.cfg.HibernateMappingContextConfiguration

/**
 * The statement inspector GORM installs on a session factory when query-tail capture is enabled. It
 * installs execution capture on Hibernate's connection providers and returns the statement as it
 * found it: the database runs
 * it unchanged. The inspector the session factory would run without the capture keeps running,
 * wherever the application configured it: with {@code hibernate.session_factory.statement_inspector},
 * on the configuration with {@link Configuration#setStatementInspector}, or in the settings of the
 * service registry, as a {@code hibernate.configClass} may. It sees the statement first, and the
 * statement it returns is the one run and recorded. Recording happens at JDBC execution, once for
 * each entry submitted in a batch, rather than when the reusable statement is prepared.
 *
 * <p>The session factory owns the capture. Each session factory a configuration builds has an
 * inspector, and a capture, of its own, so a configuration that builds several leaves each of them
 * recording until it closes. The inspector observes its session factory: its
 * {@link QueryTailRecorder} opens the capture when the session factory is created. The inspector takes from the dialect, as
 * {@link QueryTailDialects} tells it, how the database quotes values, and the recorder closes the
 * capture when the session factory closes, so a session factory that fails to start leaves no
 * capture open either. An observer the application set on
 * the configuration keeps being called, even one it sets as the configuration builds the session
 * factory.</p>
 *
 * <p>Nothing of this is installed unless {@link QueryTailSettings#SETTING_ENABLED} is set, so an
 * application that does not capture pays nothing per statement.</p>
 *
 * @since 9.0
 */
@CompileStatic
class QueryTailStatementInspector implements StatementInspector, SessionFactoryObserver {

    @Serial
    private static final long serialVersionUID = 1L

    private final QueryTailRecorder recorder
    private SessionFactoryObserver observer
    private StatementInspector delegate

    /**
     * @param dataSourceName the connection source the inspected session factory belongs to
     * @param file the capture file statements are recorded to
     */
    QueryTailStatementInspector(String dataSourceName, File file) {
        this.recorder = new QueryTailRecorder(dataSourceName, file)
    }

    /**
     * @return the connection source name statements are recorded under
     */
    String getDataSourceName() {
        recorder.dataSourceName
    }

    /**
     * @return the file statements are recorded to
     */
    File getFile() {
        recorder.file
    }

    /**
     * Enables capture on the configuration of a session factory when the settings enable it: every
     * session factory the configuration builds is then given an inspector of its own, which takes the
     * place of its statement inspector and of its observer.
     *
     * @param settings the query-tail settings of the connection source
     * @param configuration the configuration the session factories are built from
     */
    static void install(QueryTailSettings settings, HibernateMappingContextConfiguration configuration) {
        if (settings != null && settings.enabled) {
            configuration.queryTailFile = settings.resolveFile()
        }
    }

    /**
     * Takes the place of the statement inspector the session factory would run, and runs it: the
     * one set on the configuration, which Hibernate prefers, else the one the settings of the
     * service registry name, where the properties of the configuration are applied over the
     * settings the registry builder came with. The configuration calls this as it builds the
     * session factory, once every setting is in place.
     *
     * @param registry the builder of the service registry the session factory is built with
     * @param configuration the configuration the session factory is built from
     * @throws ConfigurationException when the inspector the application configured cannot be created
     */
    void wrap(StandardServiceRegistryBuilder registry, Configuration configuration) {
        delegate = configuration.statementInspector ?: resolve(registry.bootstrapServiceRegistry.getService(StrategySelector),
                registry.settings.get(AvailableSettings.STATEMENT_INSPECTOR))
        registry.applySetting(AvailableSettings.STATEMENT_INSPECTOR, this)
        configuration.statementInspector = this
        QueryTailConnectionProvider.install(registry, recorder)
    }

    /**
     * Takes the place of the session factory observer the configuration holds, and tells that one,
     * set by the application, of every event first. The configuration calls this as it builds the
     * session factory, after the application had every chance to set its own, even as the build
     * began.
     *
     * @param configuration the configuration the session factory is built from
     */
    void observe(Configuration configuration) {
        observer = configuration.sessionFactoryObserver
        configuration.sessionFactoryObserver = this
    }

    /**
     * @return the inspector the session factory would run without the capture, or null when it
     * would run none; known once the session factory is built
     */
    StatementInspector getDelegate() {
        delegate
    }

    /**
     * @return the session factory observer the application configured, or null when it configured
     * none; known once the session factory is built
     */
    SessionFactoryObserver getObserver() {
        observer
    }

    /**
     * Resolves the inspector an application configured as Hibernate does: an instance, a class, a
     * class name or a name registered with the strategy selector of the service registry.
     *
     * @param strategies the strategy selector of the service registry the session factory is built with
     * @param configured the configured value, or null
     * @return the inspector, or null when nothing was configured
     * @throws ConfigurationException when the configured inspector cannot be loaded or created
     */
    @PackageScope
    static StatementInspector resolve(StrategySelector strategies, Object configured) {
        if (configured == null) {
            return null
        }
        String name = configured instanceof Class ? ((Class) configured).name : configured.toString()
        try {
            strategies.resolveStrategy(StatementInspector, configured)
        }
        catch (StrategySelectionException e) {
            throw new ConfigurationException("Cannot load the statement inspector [${name}] configured with " +
                    AvailableSettings.STATEMENT_INSPECTOR, e)
        }
        catch (ClassCastException e) {
            // the selector creates whatever class is named, and only the cast of what it creates tells it is no inspector
            throw new ConfigurationException("The statement inspector [${name}] configured with " +
                    "${AvailableSettings.STATEMENT_INSPECTOR} does not implement ${StatementInspector.name}", e)
        }
    }

    @Override
    String inspect(String sql) {
        String inspected = delegate?.inspect(sql)
        inspected != null ? inspected : sql
    }

    /**
     * Opens the capture, and records the statements of the session factory in the syntax of its dialect.
     */
    @Override
    void sessionFactoryCreated(SessionFactory factory) {
        if (observer != null) {
            observer.sessionFactoryCreated(factory)
        }
        Class<?> dialect = factory instanceof SessionFactoryImplementor ? factory.jdbcServices.dialect.getClass() : null
        recorder.open(QueryTailDialects.syntaxOf(dialect))
    }

    @Override
    void sessionFactoryClosing(SessionFactory factory) {
        if (observer != null) {
            observer.sessionFactoryClosing(factory)
        }
    }

    /**
     * Closes the capture.
     */
    @Override
    void sessionFactoryClosed(SessionFactory factory) {
        try {
            if (observer != null) {
                observer.sessionFactoryClosed(factory)
            }
        }
        finally {
            close()
        }
    }

    /**
     * Stops recording and closes the capture; a capture that was never opened stays unopened.
     */
    void close() {
        recorder.close()
    }

    /**
     * @return where statements are recorded, or null before the first statement or the creation of the session factory
     */
    QueryTailCapture getCapture() {
        recorder.capture
    }
}

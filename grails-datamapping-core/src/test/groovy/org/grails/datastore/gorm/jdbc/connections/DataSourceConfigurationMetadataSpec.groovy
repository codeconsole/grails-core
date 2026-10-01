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
package org.grails.datastore.gorm.jdbc.connections

import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import org.grails.datastore.mapping.core.DatastoreUtils

/**
 * The configuration metadata published for the {@code dataSource} settings is what an IDE and the configReport command
 * show as each setting's default, so it has to state the default an application gets when the setting is not
 * configured.
 */
class DataSourceConfigurationMetadataSpec extends Specification {

    private static final String METADATA = 'META-INF/spring-configuration-metadata.json'

    @Shared
    Map<String, Map> publishedProperties

    @Shared
    DataSourceSettings unconfigured

    void setupSpec() {
        List<URL> metadataResources = getClass().classLoader.getResources(METADATA).toList()
        publishedProperties = metadataResources.collectMany { URL resource ->
            Map metadata = (Map) new JsonSlurper().parse(resource)
            (List<Map>) metadata.get('properties') ?: []
        }.findAll { Map property ->
            ((String) property.get('name')).startsWith('dataSource.')
        }.collectEntries { Map property ->
            [(property.get('name')): property]
        }
        assert publishedProperties
        unconfigured = new DataSourceSettingsBuilder(DatastoreUtils.createPropertyResolver([:])).build()
    }

    @Unroll
    void 'test the metadata states the default of #setting that an unconfigured data source has'() {
        expect:
        publishedProperties.containsKey(setting)
        publishedProperties[setting].defaultValue == unconfigured."$property"

        where:
        setting                | property
        'dataSource.url'       | 'url'
        'dataSource.dbCreate'  | 'dbCreate'
        'dataSource.pooled'    | 'pooled'
        'dataSource.logSql'    | 'logSql'
        'dataSource.formatSql' | 'formatSql'
        'dataSource.readOnly'  | 'readOnly'
    }

    @Unroll
    void 'test the metadata states no default for #setting since an unconfigured data source has none'() {
        expect:
        unconfigured."$property" == null
        publishedProperties.containsKey(setting)
        !publishedProperties[setting].containsKey('defaultValue')

        where:
        setting                      | property
        'dataSource.driverClassName' | 'driverClassName'
        'dataSource.username'        | 'username'
        'dataSource.password'        | 'password'
    }
}

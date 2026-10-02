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
package org.grails.orm.hibernate

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

import groovy.transform.CompileStatic

import grails.gorm.annotation.Entity
import grails.gorm.hibernate.HibernateEntity
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.orm.hibernate.cfg.Settings
import org.hibernate.mapping.SimpleValue
import org.hibernate.type.descriptor.WrapperOptions
import org.hibernate.usertype.UserType
import spock.lang.Specification
import spock.lang.Unroll

class GlobalUserTypeMappingSpec extends Specification {

    @Unroll
    void "a global user-type given as #description applies to every property of the mapped class"() {
        given: 'a datastore whose default mapping registers a user type for Boolean'
        HibernateDatastore datastore = new HibernateDatastore(DatastoreUtils.createPropertyResolver(
                (Settings.SETTING_DB_CREATE): 'create-drop',
                'dataSource.url': "jdbc:h2:mem:globalUserType${iteration};LOCK_TIMEOUT=10000",
                'grails.gorm.default.mapping': {
                    'user-type'(type: userType, 'class': Boolean)
                }
        ), GlobalUserTypeFlag)

        when: 'an entity with a Boolean property is saved and read back'
        GlobalUserTypeFlag.withTransaction {
            new GlobalUserTypeFlag(name: 'enabled', active: true).save(flush: true, failOnError: true)
            new GlobalUserTypeFlag(name: 'disabled', active: false).save(flush: true, failOnError: true)
        }
        Map<String, Boolean> readBack = GlobalUserTypeFlag.withTransaction {
            GlobalUserTypeFlag.list().collectEntries { [(it.name): it.active] }
        } as Map<String, Boolean>

        then: 'the property is bound to the user type'
        def value = datastore.metadata.getEntityBinding(GlobalUserTypeFlag.name).getProperty('active').value as SimpleValue
        value.typeName == YesNoBooleanUserType.name

        and: 'the user type writes the column'
        storedFlags(datastore) == [disabled: 'N', enabled: 'Y']

        and: 'the user type reads the column'
        readBack == [disabled: false, enabled: true]

        cleanup:
        datastore?.close()

        where:
        description     | iteration | userType
        'a Class'       | 1         | YesNoBooleanUserType
        'a class name'  | 2         | YesNoBooleanUserType.name
    }

    private static Map<String, String> storedFlags(HibernateDatastore datastore) {
        Map<String, String> flags = [:]
        datastore.dataSource.connection.withCloseable { conn ->
            conn.createStatement().withCloseable { stmt ->
                stmt.executeQuery('select name, active from global_user_type_flag order by name').withCloseable { rs ->
                    while (rs.next()) {
                        flags[rs.getString('name')] = rs.getString('active')
                    }
                }
            }
        }
        flags
    }
}

@Entity
class GlobalUserTypeFlag implements HibernateEntity<GlobalUserTypeFlag> {
    String name
    Boolean active
}

@CompileStatic
class YesNoBooleanUserType implements UserType<Boolean> {

    @Override
    int getSqlType() {
        Types.VARCHAR
    }

    @Override
    Class<Boolean> returnedClass() {
        Boolean
    }

    @Override
    Boolean nullSafeGet(ResultSet rs, int position, WrapperOptions options) throws SQLException {
        String value = rs.getString(position)
        value == null ? null : value == 'Y'
    }

    @Override
    void nullSafeSet(PreparedStatement st, Boolean value, int index, WrapperOptions options) throws SQLException {
        if (value == null) {
            st.setNull(index, Types.VARCHAR)
        } else {
            st.setString(index, value ? 'Y' : 'N')
        }
    }

    @Override
    Boolean deepCopy(Boolean value) {
        value
    }

    @Override
    boolean isMutable() {
        false
    }
}

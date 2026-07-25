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
package grails.gorm.tests

import grails.gorm.annotation.Entity
import grails.gorm.transactions.Rollback
import org.grails.orm.hibernate.HibernateDatastore
import org.hibernate.engine.spi.SessionImplementor
import spock.lang.AutoCleanup
import spock.lang.Issue
import spock.lang.Shared
import spock.lang.Specification

import java.sql.ResultSet

/**
 * Reproduces https://github.com/apache/grails-core/issues/16051
 *
 * A domain with a `hasMany` collection whose related type is an enum (a
 * Set of a basic/enum type, not an entity) produces broken join table DDL:
 * the element column is bound against the owning entity's table instead of
 * the join table, and its name is derived from the enum's fully-qualified
 * class name instead of its simple name.
 */
@Rollback
class EnumHasManyDdlSpec extends Specification {

    @Shared @AutoCleanup HibernateDatastore datastore =
        new HibernateDatastore(SurveyResponse)

    @Issue("https://github.com/apache/grails-core/issues/16051")
    void "join table for a hasMany of enum is created with the element column"() {
        given:
        SessionImplementor sessionImplementor = (SessionImplementor) datastore.sessionFactory.currentSession

        expect: "the join table exists and has an answers column, not just the owner FK"
        ResultSet columns = sessionImplementor.doReturningWork {
            it.prepareStatement(
                "select column_name from information_schema.columns " +
                "where table_name = 'SURVEY_RESPONSE_ANSWERS'"
            ).executeQuery()
        }
        Set<String> columnNames = []
        while (columns.next()) {
            columnNames << columns.getString('column_name').toLowerCase()
        }
        columnNames.contains('survey_response_id')
        columnNames.any { it.contains('answer') }
    }

    @Issue("https://github.com/apache/grails-core/issues/16051")
    void "the owner table does not get a spurious column for the hasMany enum element"() {
        given:
        SessionImplementor sessionImplementor = (SessionImplementor) datastore.sessionFactory.currentSession

        expect: "no answer-related column leaked onto survey_response itself"
        ResultSet columns = sessionImplementor.doReturningWork {
            it.prepareStatement(
                "select column_name from information_schema.columns " +
                "where table_name = 'SURVEY_RESPONSE'"
            ).executeQuery()
        }
        Set<String> columnNames = []
        while (columns.next()) {
            columnNames << columns.getString('column_name').toLowerCase()
        }
        !columnNames.any { it.contains('answer') }
    }

    @Issue("https://github.com/apache/grails-core/issues/16051")
    void "a hasMany of enum can actually be saved and reloaded"() {
        given:
        def response = new SurveyResponse(respondent: "Alice")
        response.addToAnswers(SurveyAnswer.MAYBE)
        response.addToAnswers(SurveyAnswer.DONT_KNOW)

        when:
        response.save(flush: true)
        response.discard()
        def reloaded = SurveyResponse.get(response.id)

        then:
        reloaded.answers.sort() == [SurveyAnswer.MAYBE, SurveyAnswer.DONT_KNOW].sort()
    }
}

@Entity
class SurveyResponse {
    String respondent
    Set<SurveyAnswer> answers

    static hasMany = [answers: SurveyAnswer]

    static constraints = {
        respondent blank: false
    }
}

enum SurveyAnswer {
    FOR_SURE, MAYBE, MAYBE_NOT, DONT_KNOW
}

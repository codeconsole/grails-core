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
package grails.rest.render.errors

import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.FieldError
import org.springframework.validation.ObjectError

import spock.lang.Specification

class ValidationProblemDetailFactorySpec extends Specification {

    void 'creates an RFC 9457 validation problem without exposing rejected values'() {
        given:
        def errors = new BeanPropertyBindingResult(new Object(), 'book')
        errors.addError(new FieldError('book', 'title', 'secret', false,
                ['book.title.blank', 'blank'] as String[], null, 'Title must not be blank'))
        errors.addError(new ObjectError('book', ['book.invalid'] as String[], null, 'Book is invalid'))

        when:
        def problem = new ValidationProblemDetailFactory().create(errors)
        def entries = problem.properties.errors

        then:
        problem.status == 422
        problem.title == 'Validation failed'
        problem.detail == 'Request validation failed with 2 errors.'
        entries == [
                [object: 'book', field: 'title', codes: ['book.title.blank', 'blank'],
                 message: 'Title must not be blank'],
                [object: 'book', codes: ['book.invalid'], message: 'Book is invalid'],
        ]
        !entries.first().containsKey('rejectedValue')
    }

    void 'includes rejected values only when explicitly requested'() {
        given:
        def errors = new BeanPropertyBindingResult(new Object(), 'book')
        errors.addError(new FieldError('book', 'title', 'submitted-value', false,
                ['book.title.invalid'] as String[], null, 'Title is invalid'))

        expect:
        new ValidationProblemDetailFactory(true).create(errors)
                .properties.errors.first().rejectedValue == 'submitted-value'
    }
}

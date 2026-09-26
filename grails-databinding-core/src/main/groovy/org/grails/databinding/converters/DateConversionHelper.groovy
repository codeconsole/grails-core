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
package org.grails.databinding.converters

import java.text.DateFormat
import java.text.ParseException
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

import groovy.transform.CompileStatic

import grails.databinding.converters.ValueConverter

/**
 * @author Jeff Brown
 * @since 2.3
 */
@CompileStatic
class DateConversionHelper implements ValueConverter {

    /**
     * A List of String which represent date formats compatible with {@link SimpleDateFormat}.  When
     * This converter attempts to convert a String to a Date, these formats will be tried in
     * the order in which they appear in the List.
     */
    List<String> formatStrings = []

    /**
     * Whether data parsing is lenient
     */
    boolean dateParsingLenient = false

    /**
     * Converts a date and time with an offset, such as {@code 2024-05-01T10:00:00Z} or
     * {@code 2024-05-01T10:00:00+02:00}, as ISO 8601 and RFC 3339 write it, and as Grails renders a
     * date in JSON, to the instant it names, whatever the zone of the server. Any other value is
     * converted by the first of the {@link #formatStrings} that reads all of it.
     */
    Object convert(value) {
        Date dateValue
        if (value instanceof String) {
            if (!value) {
                return null
            }
            dateValue = offsetDateTime((String) value)
            Exception firstException
            formatStrings.each { String format ->
                if (dateValue == null) {
                    DateFormat formatter = new SimpleDateFormat(format)
                    try {
                        formatter.lenient = dateParsingLenient
                        dateValue = parseAll(formatter, (String) value)
                    } catch (Exception e) {
                        firstException = firstException ?: e
                    }
                }
            }
            if (dateValue == null && firstException) {
                throw firstException
            }
        }
        dateValue
    }

    Class<?> getTargetType() {
        Date
    }

    private static Date offsetDateTime(String value) {
        try {
            return Date.from(ZonedDateTime.parse(value).toInstant())
        }
        catch (DateTimeParseException ignored) {
            return null
        }
    }

    /**
     * A format that reads only the start of a value, leaving an offset or a fraction of a second
     * after it unread, would convert it to another date than the one sent, so it does not convert it.
     */
    private static Date parseAll(DateFormat formatter, String value) {
        ParsePosition position = new ParsePosition(0)
        Date date = formatter.parse(value, position)
        if (date == null || position.index != value.length()) {
            throw new ParseException("Unparseable date: \"${value}\"".toString(),
                    position.errorIndex >= 0 ? position.errorIndex : position.index)
        }
        date
    }

    boolean canConvert(Object value) {
        value instanceof String
    }
}

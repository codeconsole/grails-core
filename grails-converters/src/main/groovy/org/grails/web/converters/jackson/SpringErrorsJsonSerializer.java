/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.web.converters.jackson;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

/** Serializes Spring validation errors without exposing internal binding state. */
final class SpringErrorsJsonSerializer extends ValueSerializer<Errors> {

    @Override
    public void serialize(Errors errors, JsonGenerator generator, SerializationContext context) throws JacksonException {
        generator.writeStartObject();
        generator.writeName("errors");
        generator.writeStartArray();
        for (ObjectError error : errors.getAllErrors()) {
            generator.writeStartObject();
            generator.writeStringProperty("object", error.getObjectName());
            if (error instanceof FieldError fieldError) {
                generator.writeStringProperty("field", fieldError.getField());
            }
            String code = error.getCode();
            if (code != null) {
                generator.writeStringProperty("code", code);
            }
            String message = error.getDefaultMessage();
            if (message != null) {
                generator.writeStringProperty("message", message);
            }
            generator.writeEndObject();
        }
        generator.writeEndArray();
        generator.writeEndObject();
    }
}

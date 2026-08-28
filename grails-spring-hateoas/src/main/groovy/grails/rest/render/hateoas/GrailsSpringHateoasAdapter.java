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
package grails.rest.render.hateoas;

import java.util.Objects;

import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.LinkRelation;

import grails.rest.Link;

/**
 * Adapts Grails link values to a Spring HATEOAS representation model.
 *
 * @since 8.0
 */
public final class GrailsSpringHateoasAdapter {

    /**
     * Wraps content and copies its Grails links into a Spring HATEOAS entity model.
     *
     * @param content response content
     * @param links links created through the existing Grails REST API
     * @param <T> content type
     * @return a representation that Spring MVC can render as HAL JSON
     */
    public <T> EntityModel<T> toEntityModel(T content, Iterable<Link> links) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(links, "links");

        EntityModel<T> model = EntityModel.of(content);
        for (Link link : links) {
            model.add(org.springframework.hateoas.Link.of(
                    link.getHref(), LinkRelation.of(link.getRel())));
        }
        return model;
    }
}

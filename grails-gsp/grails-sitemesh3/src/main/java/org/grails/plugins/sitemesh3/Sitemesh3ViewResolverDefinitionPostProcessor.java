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
package org.grails.plugins.sitemesh3;

import org.sitemesh.webmvc.SiteMeshViewResolver;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.type.MethodMetadata;
import org.springframework.util.ClassUtils;

import org.grails.plugins.web.GroovyPagesPostProcessor;

/**
 * Replaces the {@code jspViewResolver} bean definition with a
 * {@link GrailsSiteMeshViewResolver} definition that embeds the original
 * definition as an inner bean, so that every instantiation of the bean —
 * however early — yields the decorating resolver.
 *
 * <p>Wrapping at the bean-definition level (rather than post-processing the
 * bean instance) closes an initialization-order race: {@code jspViewResolver}
 * is registered lazy, so it is instantiated by whichever component first asks
 * for it. If that consumer is initialized before the SiteMesh
 * {@code BeanPostProcessor} takes effect — Spring Boot's
 * {@code ContentNegotiatingViewResolver} collecting every {@code ViewResolver}
 * while it initializes is one such consumer — it captures the raw,
 * non-decorating resolver and keeps rendering through it, silently disabling
 * layouts. With the wrap expressed in the definition itself there is no
 * "before the wrap" moment to observe. This mirrors the approach the SiteMesh
 * 2 module takes with its {@code GrailsLayoutViewResolverPostProcessor}.</p>
 *
 * <p>Runs after {@link GroovyPagesPostProcessor} (which contributes the
 * default GSP resolver definition when no plugin has registered one) so the
 * definition being wrapped is final, whether it came from grails-gsp, the
 * scaffolding plugin, or the application.</p>
 */
public class Sitemesh3ViewResolverDefinitionPostProcessor implements BeanDefinitionRegistryPostProcessor, Ordered {

    /**
     * After {@link GroovyPagesPostProcessor#ORDER} so the default GSP resolver
     * definition exists, and after the SiteMesh 2 module's post-processor
     * (ORDER - 1) so legacy layout wrapping, when present, wins and is detected.
     */
    public static final int ORDER = GroovyPagesPostProcessor.ORDER + 10;

    public static final String JSP_VIEW_RESOLVER_BEAN_NAME =
            GrailsSiteMeshViewResolverBeanPostProcessor.TARGET_VIEW_RESOLVER_BEAN_NAME;

    static final String CONTENT_PROCESSOR_BEAN_NAME = "contentProcessor";
    static final String DECORATOR_SELECTOR_BEAN_NAME = "decoratorSelector";
    static final String SERVLET_CONTEXT_BEAN_NAME = "servletContext";

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        if (!registry.containsBeanDefinition(JSP_VIEW_RESOLVER_BEAN_NAME)
                || !registry.containsBeanDefinition(CONTENT_PROCESSOR_BEAN_NAME)
                || !registry.containsBeanDefinition(DECORATOR_SELECTOR_BEAN_NAME)) {
            // Decoration is not possible in this context (no GSP view resolver, or a
            // context without the SiteMesh beans, e.g. the lightweight unit-test
            // contexts built by grails-testing-support) — leave the definition alone.
            return;
        }
        BeanDefinition existing = registry.getBeanDefinition(JSP_VIEW_RESOLVER_BEAN_NAME);
        if (isAlreadyDecorating(existing)) {
            return;
        }
        registry.removeBeanDefinition(JSP_VIEW_RESOLVER_BEAN_NAME);

        GenericBeanDefinition wrapper = new GenericBeanDefinition();
        wrapper.setBeanClass(GrailsSiteMeshViewResolver.class);
        wrapper.setLazyInit(existing.isLazyInit());
        wrapper.setPrimary(true);
        ConstructorArgumentValues arguments = wrapper.getConstructorArgumentValues();
        arguments.addIndexedArgumentValue(0, existing);
        arguments.addIndexedArgumentValue(1, new RuntimeBeanReference(CONTENT_PROCESSOR_BEAN_NAME));
        arguments.addIndexedArgumentValue(2, new RuntimeBeanReference(DECORATOR_SELECTOR_BEAN_NAME));
        arguments.addIndexedArgumentValue(3, new RuntimeBeanReference(SERVLET_CONTEXT_BEAN_NAME));
        registry.registerBeanDefinition(JSP_VIEW_RESOLVER_BEAN_NAME, wrapper);
    }

    /**
     * Skips definitions that already decorate: a {@link SiteMeshViewResolver}
     * (this module's wrapper, or a custom one), or the legacy grails-layout
     * module's {@code GrailsLayoutViewResolver} — an {@link ApplicationListener}
     * that performs SiteMesh 2 decoration itself, matching the instance-level
     * exclusion {@link GrailsSiteMeshViewResolverBeanPostProcessor} applies.
     */
    private boolean isAlreadyDecorating(BeanDefinition definition) {
        String className = definition.getBeanClassName();
        if (className == null && definition instanceof AnnotatedBeanDefinition annotated) {
            MethodMetadata factoryMethod = annotated.getFactoryMethodMetadata();
            if (factoryMethod != null) {
                className = factoryMethod.getReturnTypeName();
            }
        }
        if (className == null) {
            return false;
        }
        try {
            Class<?> beanClass = ClassUtils.forName(className, getClass().getClassLoader());
            return SiteMeshViewResolver.class.isAssignableFrom(beanClass)
                    || ApplicationListener.class.isAssignableFrom(beanClass);
        }
        catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}

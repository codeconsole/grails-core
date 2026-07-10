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
import org.sitemesh.webmvc.SiteMeshViewResolverPostProcessor;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.core.type.MethodMetadata;
import org.springframework.util.ClassUtils;

import org.grails.plugins.web.GroovyPagesPostProcessor;

/**
 * Grails-flavoured {@link SiteMeshViewResolverPostProcessor} — the upstream
 * bean-definition wrap mode ({@code sitemesh.viewResolver.wrapMode=bean-definition})
 * expressed with Grails semantics. It rewrites the {@code jspViewResolver} bean
 * definition into a {@link GrailsSiteMeshViewResolver} definition, so that every
 * instantiation of the bean — however early — yields the decorating resolver.
 *
 * <p>Wrapping at the bean-definition level (rather than post-processing the bean
 * instance) closes an initialization-order race: {@code jspViewResolver} is
 * registered lazy, so it is instantiated by whichever component first asks for
 * it. If that consumer is initialized before the SiteMesh
 * {@code BeanPostProcessor} takes effect — Spring Boot's
 * {@code ContentNegotiatingViewResolver} collecting every {@code ViewResolver}
 * while it initializes is one such consumer — it captures the raw, undecorating
 * resolver and keeps rendering through it, silently disabling layouts.</p>
 *
 * <p>It deliberately diverges from the upstream implementation on one point:
 * upstream re-registers the unwrapped resolver as a separate named bean
 * ({@code innerBeanName}) that the wrapper references, which leaves the raw
 * resolver discoverable by {@code getBeansOfType(ViewResolver)} sweeps — the
 * exact exposure this class exists to close. The original definition is instead
 * embedded as an anonymous inner-bean definition of the wrapper, making the
 * undecorated resolver structurally unreachable.</p>
 *
 * <p>Runs after {@link GroovyPagesPostProcessor} (which contributes the default
 * GSP resolver definition when no plugin has registered one) so the definition
 * being wrapped is final, whether it came from grails-gsp, the scaffolding
 * plugin, or the application.</p>
 */
public class Sitemesh3ViewResolverDefinitionPostProcessor extends SiteMeshViewResolverPostProcessor {

    /**
     * After {@link GroovyPagesPostProcessor#ORDER} so the default GSP resolver
     * definition exists whichever module contributed it, making this the last
     * word on the {@code jspViewResolver} definition.
     */
    public static final int ORDER = GroovyPagesPostProcessor.ORDER + 10;

    public Sitemesh3ViewResolverDefinitionPostProcessor() {
        setTargetViewResolverBeanName(GrailsSiteMeshViewResolverBeanPostProcessor.TARGET_VIEW_RESOLVER_BEAN_NAME);
        setSiteMeshViewResolverClass(GrailsSiteMeshViewResolver.class);
        setOrder(ORDER);
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        if (!registry.containsBeanDefinition(getTargetViewResolverBeanName()) ||
                !registry.containsBeanDefinition(getContentProcessorBeanName()) ||
                !registry.containsBeanDefinition(getDecoratorSelectorBeanName())) {
            // Decoration is not possible in this context (no GSP view resolver, or a
            // context without the SiteMesh beans, e.g. the lightweight unit-test
            // contexts built by grails-testing-support) — leave the definition alone.
            return;
        }
        BeanDefinition existing = registry.getBeanDefinition(getTargetViewResolverBeanName());
        if (isAlreadyDecorating(existing, registry)) {
            return;
        }
        registry.removeBeanDefinition(getTargetViewResolverBeanName());

        GenericBeanDefinition wrapper = new GenericBeanDefinition();
        wrapper.setBeanClass(getSiteMeshViewResolverClass());
        wrapper.setLazyInit(existing.isLazyInit());
        wrapper.setPrimary(true);
        ConstructorArgumentValues arguments = wrapper.getConstructorArgumentValues();
        arguments.addIndexedArgumentValue(0, existing);
        arguments.addIndexedArgumentValue(1, new RuntimeBeanReference(getContentProcessorBeanName()));
        arguments.addIndexedArgumentValue(2, new RuntimeBeanReference(getDecoratorSelectorBeanName()));
        arguments.addIndexedArgumentValue(3, new RuntimeBeanReference(getServletContextBeanName()));
        if (getDispatchMode() != null) {
            wrapper.getPropertyValues().add("dispatchMode", getDispatchMode());
        }
        wrapper.getPropertyValues().add("includeErrorPages", isIncludeErrorPages());
        registry.registerBeanDefinition(getTargetViewResolverBeanName(), wrapper);
    }

    /**
     * A definition that is already a {@link SiteMeshViewResolver} — this
     * module's wrapper, or a custom one — decorates by itself and must not be
     * wrapped again. The class is resolved with the bean class loader the
     * container itself will use to instantiate the definition, so application
     * classes in a child or restart class loader (e.g. devtools) are visible
     * to the check.
     */
    private boolean isAlreadyDecorating(BeanDefinition definition, BeanDefinitionRegistry registry) {
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
        ClassLoader loader = registry instanceof ConfigurableBeanFactory beanFactory ?
                beanFactory.getBeanClassLoader() :
                ClassUtils.getDefaultClassLoader();
        try {
            Class<?> beanClass = ClassUtils.forName(className, loader);
            return SiteMeshViewResolver.class.isAssignableFrom(beanClass);
        }
        catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}

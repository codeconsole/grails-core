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
package grails.spring;

import org.springframework.beans.factory.parsing.EmptyReaderEventListener;
import org.springframework.beans.factory.parsing.FailFastProblemReporter;
import org.springframework.beans.factory.parsing.NullSourceExtractor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.SimpleBeanDefinitionRegistry;
import org.springframework.beans.factory.xml.BeanDefinitionParserDelegate;
import org.springframework.beans.factory.xml.DefaultNamespaceHandlerResolver;
import org.springframework.beans.factory.xml.NamespaceHandler;
import org.springframework.beans.factory.xml.NamespaceHandlerResolver;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.beans.factory.xml.XmlReaderContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.Resource;

import org.grails.spring.RuntimeSpringConfiguration;

final class BeanBuilderXmlSupport {

    private final RuntimeSpringConfiguration springConfig;
    private ClassLoader classLoader;
    private NamespaceHandlerResolver namespaceHandlerResolver;
    private XmlBeanDefinitionReader xmlBeanDefinitionReader;
    private XmlReaderContext readerContext;
    private Resource readerContextResource;

    BeanBuilderXmlSupport(RuntimeSpringConfiguration springConfig, ClassLoader classLoader) {
        this.springConfig = springConfig;
        setClassLoader(classLoader);
    }

    void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
        namespaceHandlerResolver = new DefaultNamespaceHandlerResolver(classLoader);
        xmlBeanDefinitionReader = null;
        readerContext = null;
        readerContextResource = null;
    }

    void setNamespaceHandlerResolver(NamespaceHandlerResolver namespaceHandlerResolver) {
        this.namespaceHandlerResolver = namespaceHandlerResolver;
        readerContext = null;
        readerContextResource = null;
    }

    NamespaceHandler resolveNamespaceHandler(String uri) {
        return namespaceHandlerResolver.resolve(uri);
    }

    ParserContext createParserContext(Resource resource) {
        XmlReaderContext currentReaderContext = getReaderContext(resource);
        return new ParserContext(currentReaderContext, new BeanDefinitionParserDelegate(currentReaderContext));
    }

    XmlReaderContext getReaderContext(Resource resource) {
        if (readerContext == null || !resource.equals(readerContextResource)) {
            readerContext = new XmlReaderContext(resource, new FailFastProblemReporter(), new EmptyReaderEventListener(),
                    new NullSourceExtractor(), getXmlBeanDefinitionReader(), namespaceHandlerResolver);
            readerContextResource = resource;
        }
        return readerContext;
    }

    BeanDefinitionRegistry loadBeanDefinitions(Resource resource) {
        SimpleBeanDefinitionRegistry beanRegistry = new SimpleBeanDefinitionRegistry();
        XmlBeanDefinitionReader beanReader = new XmlBeanDefinitionReader(beanRegistry);
        beanReader.setBeanClassLoader(classLoader);
        beanReader.loadBeanDefinitions(resource);
        return beanRegistry;
    }

    private XmlBeanDefinitionReader getXmlBeanDefinitionReader() {
        if (xmlBeanDefinitionReader == null) {
            xmlBeanDefinitionReader = new XmlBeanDefinitionReader((GenericApplicationContext) springConfig.getUnrefreshedApplicationContext());
            xmlBeanDefinitionReader.setBeanClassLoader(classLoader);
        }
        return xmlBeanDefinitionReader;
    }
}

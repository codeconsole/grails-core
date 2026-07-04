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
package grails.spring

import org.grails.spring.DefaultRuntimeSpringConfiguration
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext
import org.springframework.core.io.ByteArrayResource

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

class BeanBuilderTests {

    @Test
    void testPlainBeanBuilderDslDoesNotInitializeXmlSupport() {
        def springConfig = new DefaultRuntimeSpringConfiguration() {
            @Override
            ApplicationContext getUnrefreshedApplicationContext() {
                throw new AssertionError('XML support should not be initialized for plain BeanBuilder DSL')
            }
        }
        def beanBuilder = new BeanBuilder(null, springConfig, getClass().classLoader)

        beanBuilder.beans {
            bean1(Bean1) {
                person = 'homer'
            }
        }

        assertTrue springConfig.containsBean('bean1')
        assertEquals Bean1, springConfig.createBeanDefinition('bean1').beanClass
    }

    @Test
    void testImportBeansXmlInitializesXmlSupportOnDemand() {
        def springConfig = new DefaultRuntimeSpringConfiguration()
        def beanBuilder = new BeanBuilder(null, springConfig, getClass().classLoader)

        beanBuilder.beans {
            importBeans new NamedByteArrayResource('''
                <beans xmlns="http://www.springframework.org/schema/beans"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.springframework.org/schema/beans https://www.springframework.org/schema/beans/spring-beans.xsd">
                    <bean id="xmlBean" class="java.lang.String">
                        <constructor-arg value="hello"/>
                    </bean>
                </beans>
            '''.bytes, 'test.xml')
        }

        assertTrue springConfig.containsBean('xmlBean')
        assertEquals String, springConfig.createBeanDefinition('xmlBean').beanClass
    }

    @Test
    void testXmlnsInitializesNamespaceSupportOnDemand() {
        def beanBuilder = new BeanBuilder()

        beanBuilder.beans {
            xmlns util: 'http://www.springframework.org/schema/util'

            util.list(id: 'letters') {
                value 'one'
                value 'two'
            }
        }

        assertEquals ['one', 'two'], beanBuilder.createApplicationContext().getBean('letters')
    }

    static class Bean1 {
        String person
    }

    private static class NamedByteArrayResource extends ByteArrayResource {
        private final String filename

        NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray)
            this.filename = filename
        }

        @Override
        String getFilename() {
            filename
        }
    }
}

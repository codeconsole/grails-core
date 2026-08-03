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
package org.grails.encoder

import java.util.concurrent.ConcurrentHashMap

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine

import groovy.transform.CompileStatic
import org.codehaus.groovy.runtime.GStringImpl
import org.codehaus.groovy.runtime.NullObject

import org.springframework.util.Assert

import grails.util.Environment
import grails.util.GrailsMetaClassUtils

/**
 * Helper methods for Codec metaclass operations.
 *
 * @author Lari Hotari
 * @since 2.3
 */
class CodecMetaClassSupport {

    static final Object[] EMPTY_ARGS = []
    static final String ENCODE_AS_PREFIX = 'encodeAs'
    static final String DECODE_PREFIX = 'decode'
    private static final Cache<CodecFactory, Set<MetaMethodRegistrationKey>> REGISTERED_META_METHODS = Caffeine.newBuilder()
            .weakKeys()
            .build()
    /**
     * Per-factory locks keep claim+register atomic for the same CodecFactory without
     * synchronizing on globally shared ExpandoMetaClass instances (String, Object, ...).
     */
    private static final Cache<CodecFactory, Object> FACTORY_REGISTRATION_LOCKS = Caffeine.newBuilder()
            .weakKeys()
            .build()

    /**
     * Adds "encodeAs*" and "decode*" metamethods for given codecClass
     *
     * @param codecClass the codec class
     */
    @CompileStatic
    void configureCodecMethods(CodecFactory codecFactory, boolean cacheLookup = !Environment.getCurrent().isDevelopmentMode(), List<ExpandoMetaClass> targetMetaClasses = resolveDefaultMetaClasses()) {
        Closure<String> encodeMethodNameClosure = { String codecName -> "${ENCODE_AS_PREFIX}${codecName}".toString() }
        Closure<String> decodeMethodNameClosure = { String codecName -> "${DECODE_PREFIX}${codecName}".toString() }

        String codecName = resolveCodecName(codecFactory)
        Assert.hasText(codecName, 'No resolvable codec name')

        String encodeMethodName = encodeMethodNameClosure(codecName)
        String decodeMethodName = decodeMethodNameClosure(codecName)

        Closure encoderClosure
        Closure decoderClosure
        if (!cacheLookup) {
            // Resolve codecs in every call in case of a codec reload
            encoderClosure = {
                ->
                def encoder = codecFactory.getEncoder()
                if (encoder) {
                    return encoder.encode(CodecMetaClassSupport.filterNullObject(delegate))
                }

                // note the call to delegate.getClass() instead of the more groovy delegate.class.
                // this is because the delegate might be a Map, in which case delegate.class doesn't
                // do what we want here...
                throw new MissingMethodException(encodeMethodName, delegate.getClass(), EMPTY_ARGS)
            }

            decoderClosure = {
                ->
                def decoder = codecFactory.getDecoder()
                if (decoder) {
                    return decoder.decode(CodecMetaClassSupport.filterNullObject(delegate))
                }

                // note the call to delegate.getClass() instead of the more groovy delegate.class.
                // this is because the delegate might be a Map, in which case delegate.class doesn't
                // do what we want here...
                throw new MissingMethodException(decodeMethodName, delegate.getClass(), EMPTY_ARGS)
            }
        }
        else {
            // Resolve codec methods once only at startup
            def encoder = codecFactory.getEncoder()
            if (encoder) {
                encoderClosure = { -> encoder.encode(CodecMetaClassSupport.filterNullObject(delegate)) }
            } else {
                encoderClosure = { -> throw new MissingMethodException(encodeMethodName, delegate.getClass(), EMPTY_ARGS) }
            }
            def decoder = codecFactory.getDecoder()
            if (decoder) {
                decoderClosure = { -> decoder.decode(CodecMetaClassSupport.filterNullObject(delegate)) }
            } else {
                decoderClosure = { -> throw new MissingMethodException(decodeMethodName, delegate.getClass(), EMPTY_ARGS) }
            }
        }

        addMetaMethod(targetMetaClasses, encodeMethodName, encoderClosure, cacheLookup, codecFactory)
        if (codecFactory.encoder) {
            addAliasMetaMethods(targetMetaClasses, codecFactory.encoder.codecIdentifier.codecAliases, encodeMethodNameClosure, encoderClosure, cacheLookup, codecFactory)
        }

        addMetaMethod(targetMetaClasses, decodeMethodName, decoderClosure, cacheLookup, codecFactory)
        if (codecFactory.decoder) {
            addAliasMetaMethods(targetMetaClasses, codecFactory.decoder.codecIdentifier.codecAliases, decodeMethodNameClosure, decoderClosure, cacheLookup, codecFactory)
        }
    }

    /**
     * returns given parameter if it's not a Groovy NullObject (and is not null)
     *
     * The check is made by looking at the Object's class, since NullObject.is & equals give wrong results (Groovy bug?).
     *
     * A NullObject get's passed to the closure in delegate perhaps because of a Groovy bug or feature
     * This happens when a NullObject's MetaMethod is called.
     *
     * @param delegate
     * @return
     */
    @CompileStatic
    private static Object filterNullObject(Object delegate) {
        delegate != null && delegate.getClass() != NullObject ? delegate : null
    }

    @CompileStatic
    private addAliasMetaMethods(List<ExpandoMetaClass> targetMetaClasses, Set<String> aliases, Closure<String> methodNameClosure, Closure methodClosure,
            boolean cacheLookup, CodecFactory codecFactory) {
        aliases?.each { String aliasName ->
            addMetaMethod(targetMetaClasses, methodNameClosure(aliasName), methodClosure, cacheLookup, codecFactory)
        }
    }

    private static String resolveCodecName(CodecFactory codecFactory) {
        codecFactory.encoder?.codecIdentifier?.codecName ?: codecFactory.decoder?.codecIdentifier?.codecName
    }

    private static List<ExpandoMetaClass> resolveDefaultMetaClasses() {
        [
            String,
            GStringImpl,
            StringBuffer,
            StringBuilder,
            Object
        ].collect { Class clazz ->
            GrailsMetaClassUtils.getExpandoMetaClass(clazz)
        }
    }

    protected void addMetaMethod(List<ExpandoMetaClass> targetMetaClasses, String methodName, Closure closure) {
        addMetaMethod(targetMetaClasses, methodName, closure, false, null)
    }

    protected void addMetaMethod(List<ExpandoMetaClass> targetMetaClasses, String methodName, Closure closure, boolean cacheLookup, CodecFactory codecFactory) {
        targetMetaClasses.each { ExpandoMetaClass emc ->
            if (!cacheLookup) {
                emc."${methodName}" << closure
            }
            else {
                // Serialize only this factory's registrations; never lock the shared EMC.
                synchronized (factoryRegistrationLock(codecFactory)) {
                    if (shouldRegisterMetaMethod(emc, methodName, codecFactory)) {
                        emc."${methodName}" << closure
                    }
                }
            }
        }
    }

    @CompileStatic
    private static boolean shouldRegisterMetaMethod(ExpandoMetaClass emc, String methodName, CodecFactory codecFactory) {
        MetaMethodRegistrationKey key = registrationKey(emc, methodName)
        registeredMetaMethodKeys(codecFactory).add(key) || emc.getMetaMethod(methodName, EMPTY_ARGS) == null
    }

    @CompileStatic
    private static MetaMethodRegistrationKey registrationKey(ExpandoMetaClass emc, String methodName) {
        new MetaMethodRegistrationKey(emc.getTheClass(), methodName)
    }

    @CompileStatic
    private static Set<MetaMethodRegistrationKey> registeredMetaMethodKeys(CodecFactory codecFactory) {
        REGISTERED_META_METHODS.get(codecFactory) { CodecFactory ignored ->
            Collections.newSetFromMap(new ConcurrentHashMap<MetaMethodRegistrationKey, Boolean>())
        }
    }

    @CompileStatic
    private static Object factoryRegistrationLock(CodecFactory codecFactory) {
        FACTORY_REGISTRATION_LOCKS.get(codecFactory) { CodecFactory ignored ->
            new Object()
        }
    }

    @CompileStatic
    private static class MetaMethodRegistrationKey {

        private final Class<?> targetClass
        private final String methodName

        MetaMethodRegistrationKey(Class<?> targetClass, String methodName) {
            this.targetClass = targetClass
            this.methodName = methodName
        }

        @Override
        boolean equals(Object other) {
            if (this.is(other)) {
                return true
            }
            if (!(other instanceof MetaMethodRegistrationKey)) {
                return false
            }

            MetaMethodRegistrationKey otherKey = (MetaMethodRegistrationKey) other
            targetClass == otherKey.targetClass &&
                    methodName == otherKey.methodName
        }

        @Override
        int hashCode() {
            int result = targetClass.hashCode()
            31 * result + methodName.hashCode()
        }
    }
}

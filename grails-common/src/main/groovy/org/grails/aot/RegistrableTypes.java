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
package org.grails.aot;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.asm.Type;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;

/**
 * Decides whether a type found by scanning can be registered for reflection.
 *
 * <p>Registering one that cannot be loaded here does not degrade at run time -- it fails the build,
 * because the image analysis parses what it is asked to keep. The framework compiles against
 * optional integrations, so a scan of its own packages finds classes an application need not be able
 * to load: the JSP support without the JSP API, the GSP compiler's task without Ant, a datastore
 * without its driver.</p>
 *
 * <p>Two questions are asked, because either alone lets one of those through. Whether the type and
 * the class declaring it load covers a closure whose enclosing class extends something absent, which
 * the closure's own bytecode need not name -- it reaches it through invokedynamic. Whether the types
 * its bytecode names load covers the opposite case, a class that loads cleanly while a method body
 * names something absent, because loading resolves signatures and not bodies.</p>
 *
 * @since 8.0
 */
public final class RegistrableTypes {

    private RegistrableTypes() {
    }

    /**
     * Whether the type, and the class declaring it, load here.
     *
     * @param className the binary name of the type
     * @param classLoader the loader to resolve against
     * @return whether the type can be registered
     */
    public static boolean loads(String className, @Nullable ClassLoader classLoader) {
        int declaring = className.indexOf('$');
        if (declaring > 0 && !ClassUtils.isPresent(className.substring(0, declaring), classLoader)) {
            return false;
        }
        return ClassUtils.isPresent(className, classLoader);
    }

    /**
     * Whether every type named in the given bytecode loads here, following the declaration and the
     * bodies of the methods.
     *
     * @param bytecode the class file to read; closed by this method
     * @param classLoader the loader to resolve against
     * @return whether the type can be registered
     */
    public static boolean referencesLoad(InputStream bytecode, @Nullable ClassLoader classLoader) {
        Set<String> referenced = new LinkedHashSet<>();
        try (InputStream input = bytecode) {
            new ClassReader(input).accept(new ReferenceCollector(referenced),
                    ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        catch (IOException | RuntimeException ex) {
            return false;
        }
        for (String name : referenced) {
            if (!ClassUtils.isPresent(name, classLoader)) {
                return false;
            }
        }
        return true;
    }

    /** Collects the types a class names, in its own declaration and in the bodies of its methods. */
    private static final class ReferenceCollector extends ClassVisitor {

        private final Set<String> referenced;

        private ReferenceCollector(Set<String> referenced) {
            super(SpringAsmInfo.ASM_VERSION);
            this.referenced = referenced;
        }

        private void add(@Nullable String internalName) {
            if (internalName == null || internalName.startsWith("[")) {
                return;
            }
            String className = ClassUtils.convertResourcePathToClassName(internalName);
            // the JDK is always present, and skipping it keeps this to the classes that can be absent
            if (!className.startsWith("java.") && !className.startsWith("jdk.")) {
                referenced.add(className);
            }
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                String[] interfaces) {
            add(superName);
            if (interfaces != null) {
                for (String each : interfaces) {
                    add(each);
                }
            }
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            for (Type argument : Type.getArgumentTypes(descriptor)) {
                add(argument.getSort() == Type.OBJECT ? argument.getInternalName() : null);
            }
            return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                @Override
                public void visitTypeInsn(int opcode, String type) {
                    add(type);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String methodName,
                        String methodDescriptor, boolean isInterface) {
                    add(owner);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String fieldName,
                        String fieldDescriptor) {
                    add(owner);
                }
            };
        }
    }
}

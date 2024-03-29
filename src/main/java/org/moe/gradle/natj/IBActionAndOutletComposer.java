/*
Copyright (C) 2016 Migeran

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package org.moe.gradle.natj;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.GradleException;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.moe.gradle.natj.NatJResolver.ResolvedClass;
import org.moe.gradle.options.UIActionsAndOutletsOptions;
import org.moe.gradle.utils.TaskUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ASM9;

/**
 * This class composes Objective-C source code from Java classes.
 */
public class IBActionAndOutletComposer {

    private static final Logger LOG = Logging.getLogger(IBActionAndOutletComposer.class);

    private final NatJResolver<ClassVisitor> resolver = new NatJResolver<>();

    public void read(InputStream inputStream) {
        ClassVisitor visitor = new ClassVisitor();

        ClassReader reader;
        try {
            reader = new ClassReader(inputStream);
        } catch (IOException e) {
            throw new GradleException("an IOException occurred", e);
        }
        reader.accept(visitor.getInitializingVisitor(ASM9, resolver::add), 0);
    }

    public String compose(UIActionsAndOutletsOptions options) {
        final List<String> classIncludeFilters = new ArrayList<>(options.getIncludes());
        if (classIncludeFilters.size() == 0) {
            classIncludeFilters.add(".*");
        }

        final StringBuilder builder = new StringBuilder();
        final StringBuilder headBuilder = new StringBuilder();

        final Set<String> allLibraries = new HashSet<>();
        allLibraries.add("UIKit");

        headBuilder.append("/** THIS FILE IS GENERATED BY MULTI-OS ENGINE AND MAY BE OVERWRITTEN! **/\n\n");
        headBuilder.append("#if TARGET_INTERFACE_BUILDER\n\n");
        resolver.resolve((k, resolvedClass) -> {
            final ClassVisitor v = resolvedClass.getClazz();
            if (!v.hasObjcClassName() || !resolvedClass.isValidObjCType() || v.hasObjcClassBinding()) {
                return;
            }
            final String prettyName = resolvedClass.getPrettyName();
            if (classIncludeFilters.stream().noneMatch(prettyName::matches)) {
                LOG.info("Skipping " + prettyName + ": not found in include list");
                return;
            }

            // Get class
            final String objcClassName = v.getObjcClassName();
            LOG.debug("Generating interface for " + objcClassName + "\n");

            // Get super class
            if (v.getSuperName() == null) {
                LOG.warn("Superclass is null for " + objcClassName);
                return;
            }
            final String objcSuperClassName = resolvedClass.getSuperObjCName();
            if (objcSuperClassName == null) {
                LOG.warn("Failed to locate superclass for " + objcClassName);
                return;
            }
            LOG.debug("    Found superclass " + objcSuperClassName + "\n");

            // Get interfaces
            final Set<String> interfaces = TaskUtils.compute(() -> {
                final Set<String> itfs = new HashSet<>();

                // Add libraries from interface hierarchy
                for (String superItf : v.getSuperInterfaces()) {
                    final ResolvedClass superItfRc = resolver.get(superItf);
                    if (superItfRc == null) {
                        continue;
                    }
                    NatJClass _v = superItfRc.getClazz();
                    if (_v != null && _v.hasObjcProtocolName()) {
                        if (_v.hasObjcProtocolSourceName()) {
                            itfs.add(_v.getObjcProtocolSourceName());
                        } else {
                            itfs.add(_v.getObjcProtocolName());
                        }
                    }
                }

                return itfs;
            });
            LOG.debug("    Found interfaces " + interfaces + "\n");

            // Get libraries
            final Set<String> libraries = TaskUtils.compute(() -> {
                final Set<String> libs = new HashSet<>();

                // Add first library in class hierarchy
                ResolvedClass _r = resolvedClass;
                do {
                    NatJClass _v = _r.getClazz();
                    if (_v.hasLibrary()) {
                        libs.add(_v.getLibrary());
                        break;
                    }
                    _r = _r.getSuper();
                } while (_r != null);

                // Add libraries from interface hierarchy
                for (String superItf : v.getSuperInterfaces()) {
                    _r = resolver.get(superItf);
                    NatJClass _v = _r.getClazz();
                    if (_v.hasLibrary()) {
                        libs.add(_v.getLibrary());
                        break;
                    }
                }

                return libs;
            });
            LOG.debug("    Found libraries " + libraries + "\n");
            allLibraries.addAll(libraries);

            // Generate interface
            headBuilder.append("@class ").append(objcClassName).append(";\n");
            builder.append("@interface ").append(objcClassName).append(" : ").append(objcSuperClassName);
            if (interfaces.size() > 0) {
                builder.append(" <").append(interfaces.stream().collect(Collectors.joining(", "))).append(">");
            }
            builder.append("\n");

            //Sort methods
            Collections.sort(v.methods);

            v.methods.forEach(m -> {
                final Type methodType = Type.getMethodType(m.getDesc());
                final String sel = m.getSel();

                // Generate properties
                if (m.isProperty()) {
                    LOG.debug("    Generating property for " + m.getName() + m.getDesc() + "\n");
                    if (methodType.getArgumentTypes().length > 0) {
                        logSkip(resolvedClass, m,
                                "cannot have the @Property annotation and have arguments at the same time");
                        return;
                    }
                    final Type returnType = methodType.getReturnType();
                    if (returnType.getSort() != Type.OBJECT) {
                        logSkip(resolvedClass, m,
                                "cannot have the @Property annotation and have a non-object return type");
                        return;
                    }
                    final ResolvedClass internalType = resolver.get(returnType.getInternalName());
                    if (internalType == null || internalType.getBindingType() == null) {
                        logSkip(resolvedClass, m, "unsupported return type");
                        return;
                    }
                    if (internalType.getClazz().hasLibrary()) {
                        allLibraries.add(internalType.getClazz().getLibrary());
                    }
                    builder.append("@property (strong) ");
                    if (m.isIBOutlet()) {
                        builder.append("IBOutlet ");
                    }
                    final int begin = builder.length();
                    builder.append(internalType.getBindingType());
                    builder.append(" ").append(sel).append(";\n");
                    return;
                }

                // Generate actions
                if (m.isIBAction()) {
                    LOG.debug("    Generating action for " + m.getName() + m.getDesc() + "\n");
                    final Type returnType = methodType.getReturnType();
                    if (returnType.getSort() != Type.VOID) {
                        logSkip(resolvedClass, m,
                                "cannot have the @IBAction annotation and have a non-void return type");
                        return;
                    }

                    final int numArgs = methodType.getArgumentTypes().length;
                    if (numArgs == 0) {
                        builder.append("- (IBAction)").append(sel).append(";\n");
                        return;
                    }

                    final Type arg0 = methodType.getArgumentTypes()[0];
                    if (arg0.getSort() != Type.OBJECT) {
                        logSkip(resolvedClass, m,
                                "cannot have the @IBAction annotation, have 1 one or more arguments and have a "
                                        + "non-object first argument type");
                        return;
                    }
                    final ResolvedClass arg0InternalType = resolver.get(arg0.getInternalName());
                    if (arg0InternalType == null || arg0InternalType.getBindingType() == null) {
                        logSkip(resolvedClass, m, "unsupported first argument type");
                        return;
                    }

                    if (numArgs == 1) {
                        if (StringUtils.countMatches(sel, ":") != 1) {
                            logSkip(resolvedClass, m, "bad selector, expected one argument in selector");
                            return;
                        }
                        if (!sel.endsWith(":")) {
                            logSkip(resolvedClass, m, "malformed selector, selector must end in ':'");
                            return;
                        }
                        final int begin = builder.length();
                        builder.append("- (IBAction)").append(sel).append("(");
                        builder.append(arg0InternalType.getBindingType());
                        builder.append(")sender;\n");
                        return;
                    }

                    final Type arg1 = methodType.getArgumentTypes()[1];
                    if (arg1.getSort() != Type.OBJECT) {
                        logSkip(resolvedClass, m,
                                "cannot have the @IBAction annotation, have 2 one or more arguments and have a "
                                        + "non UIEvent second argument type");
                        return;
                    }
                    final String arg1InternalName = arg1.getInternalName();
                    final ResolvedClass arg1InternalType = resolver.get(arg1InternalName);
                    if (arg1InternalType == null || !"UIEvent"
                            .equals(arg1InternalType.getClazz().getObjcClassBinding())) {
                        logSkip(resolvedClass, m,
                                "cannot have the @IBAction annotation, have 2 one or more arguments and have a "
                                        + "non UIEvent second argument type");
                        return;
                    }

                    if (numArgs == 2) {
                        if (StringUtils.countMatches(sel, ":") != 2) {
                            logSkip(resolvedClass, m, "bad selector, expected two arguments in selector");
                            return;
                        }
                        if (!sel.endsWith(":")) {
                            logSkip(resolvedClass, m, "malformed selector, selector must end in ':'");
                            return;
                        }
                        final int begin = builder.length();
                        builder.append("- (IBAction)").append(sel.substring(0, sel.indexOf(':') + 1)).append("(");
                        builder.append(arg0InternalType.getBindingType());
                        final String selSuffix = sel.substring(sel.indexOf(':') + 1);
                        builder.append(")sender ").append(selSuffix).append("(UIEvent *)event;\n");
                        return;
                    }

                    logSkip(resolvedClass, m, "malformed selector, expected zero, one or two arguments in selector");
                    return;
                }

                LOG.debug("    Skipping " + m.getName() + m.getDesc() + "\n");
            });

            // Generate close interface
            builder.append("@end\n\n");
        });

        // Generate footer
        builder.append("#endif\n");

        // Combine head and body
        headBuilder.append("\n");

        // Generate imports
        allLibraries.removeAll(options.getExcludeLibraries());
        allLibraries.forEach(x -> headBuilder.append("@import ").append(x).append(";\n"));
        headBuilder.append("\n");
        options.getAdditionalCodes().forEach(x -> headBuilder.append(x).append("\n"));
        headBuilder.append("\n");

        headBuilder.append(builder);
        return headBuilder.toString();
    }

    private void logSkip(ResolvedClass resolvedClass, NatJMethod m, String msg) {
        LOG.warn("Skipping " + getPrettyName(resolvedClass, m) + ": " + msg);
    }

    private String getPrettyName(ResolvedClass resolvedClass, NatJMethod method) {
        final String c = resolvedClass.getPrettyName();
        final String m = method.getName();
        final Type mt = Type.getMethodType(method.getDesc());
        final String r = mt.getReturnType().getClassName();
        final String a = Arrays.stream(mt.getArgumentTypes()).map(Type::getClassName).collect(Collectors.joining(", "));
        return r + " " + c + "." + m + "(" + a + ")";
    }

    static class ClassVisitor extends NatJClass<ClassVisitor> {

        private final List<NatJMethod> methods = new ArrayList<>();

        @Override
        public org.objectweb.asm.ClassVisitor getInitializingVisitor(int api, Consumer<ClassVisitor> consumer) {
            return new org.objectweb.asm.ClassVisitor(api, super.getInitializingVisitor(api, consumer)) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                                                 String[] exceptions) {
                    if (!hasObjcClassName() && !hasObjcClassBinding()) {
                        return null;
                    }
                    return new NatJMethod(name, desc, (access & ACC_STATIC) > 0).getInitializingVisitor(api, method -> {
                        if (method.hasSel() && (method.isProperty() || method.isIBAction() || method.isIBOutlet())) {
                            methods.add(method);
                        }
                    });
                }
            };
        }
    }
}

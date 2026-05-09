package dev.nekoobfuscator.transforms.util;

import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.HashSet;
import java.util.Set;

public final class TransformGuards {
    private static final String RUNTIME_PREFIX = "dev/nekoobfuscator/runtime/";
    private static final String GENERATED_PREFIX = "__neko_";
    private static final String REFLECTION_SHAPE_SENSITIVE_KEY = "transformGuards.reflectionShapeSensitiveClasses";

    private TransformGuards() {}

    public static boolean isRuntimeClass(String internalName) {
        return internalName != null && internalName.startsWith(RUNTIME_PREFIX);
    }

    public static boolean isRuntimeClass(L1Class clazz) {
        return clazz != null && isRuntimeClass(clazz.name());
    }

    public static boolean isGeneratedName(String name) {
        return name != null && name.startsWith(GENERATED_PREFIX);
    }

    public static boolean isGeneratedMethod(MethodNode method) {
        return method != null && isGeneratedName(method.name);
    }

    public static boolean isGeneratedMethod(L1Method method) {
        return method != null && isGeneratedName(method.name());
    }

    public static boolean isSupportMethod(L1Method method) {
        return method == null
            || isRuntimeClass(method.owner().name())
            || isGeneratedMethod(method);
    }

    public static boolean isSupportCall(MethodInsnNode call) {
        return call == null
            || isRuntimeClass(call.owner)
            || isGeneratedName(call.name)
            || call.getOpcode() == Opcodes.INVOKEDYNAMIC;
    }


    public static boolean isReflectionShapeSensitive(PipelineContext pctx, L1Class clazz) {
        return clazz != null && isReflectionShapeSensitive(pctx, clazz.name());
    }

    public static boolean isReflectionShapeSensitive(PipelineContext pctx, String internalName) {
        if (pctx == null || internalName == null) return false;
        Set<String> sensitive = pctx.getPassData(REFLECTION_SHAPE_SENSITIVE_KEY);
        if (sensitive == null) {
            sensitive = computeReflectionShapeSensitiveClasses(pctx);
            pctx.putPassData(REFLECTION_SHAPE_SENSITIVE_KEY, sensitive);
        }
        return sensitive.contains(internalName);
    }

    public static boolean hasStackIntrospection(L1Method method) {
        if (method == null || !method.hasCode()) return false;
        for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi
                    && ((("java/lang/Throwable".equals(mi.owner) || "java/lang/Thread".equals(mi.owner))
                        && "getStackTrace".equals(mi.name))
                        || "java/lang/StackWalker".equals(mi.owner))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> computeReflectionShapeSensitiveClasses(PipelineContext pctx) {
        Set<String> sensitive = new HashSet<>();
        for (L1Class clazz : pctx.classMap().values()) {
            for (L1Method method : clazz.methods()) {
                if (!method.hasCode()) continue;
                Type lastClassLiteral = null;
                for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type
                            && type.getSort() == Type.OBJECT) {
                        lastClassLiteral = type;
                        continue;
                    }
                    if (insn instanceof MethodInsnNode mi && "java/lang/Class".equals(mi.owner)) {
                        if (lastClassLiteral != null && observesDeclaredShape(mi.name, mi.desc)) {
                            sensitive.add(lastClassLiteral.getInternalName());
                        }
                        if (!"getName".equals(mi.name)) {
                            lastClassLiteral = null;
                        }
                    } else if (insn.getOpcode() >= 0) {
                        lastClassLiteral = null;
                    }
                }
            }
        }
        return sensitive;
    }

    private static boolean observesDeclaredShape(String name, String desc) {
        return ("getFields".equals(name) && "()[Ljava/lang/reflect/Field;".equals(desc))
            || ("getDeclaredFields".equals(name) && "()[Ljava/lang/reflect/Field;".equals(desc))
            || ("getMethods".equals(name) && "()[Ljava/lang/reflect/Method;".equals(desc))
            || ("getDeclaredMethods".equals(name) && "()[Ljava/lang/reflect/Method;".equals(desc))
            || ("getConstructors".equals(name) && "()[Ljava/lang/reflect/Constructor;".equals(desc))
            || ("getDeclaredConstructors".equals(name) && "()[Ljava/lang/reflect/Constructor;".equals(desc))
            || ("getClasses".equals(name) && "()[Ljava/lang/Class;".equals(desc))
            || ("getDeclaredClasses".equals(name) && "()[Ljava/lang/Class;".equals(desc));
    }
}

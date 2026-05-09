package dev.nekoobfuscator.transforms.structure;

import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.util.TransformGuards;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

import java.util.Set;

/**
 * Stack Obfuscation: inserts verifier-safe stack-neutral arithmetic noise at
 * method entry. The value is consumed immediately, so max stack/frame
 * computation remains straightforward while the bytecode still carries a real
 * stack expression rather than a no-op marker.
 */
public final class StackObfuscationPass implements TransformPass {
    public static final String HARDEN_GENERATED_HELPERS_KEY = "stackObfuscation.hardenGeneratedHelpers";

    @Override public String id() { return "stackObfuscation"; }
    @Override public String name() { return "Stack Obfuscation"; }
    @Override public TransformPhase phase() { return TransformPhase.TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }
    @Override public Set<String> dependsOn() { return Set.of("outliner"); }

    @Override public void transformClass(TransformContext ctx) {}

    @Override
    public void transformMethod(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Class clazz = pctx.currentL1Class();
        L1Method method = pctx.currentL1Method();
        if (method == null || !method.hasCode()) return;
        boolean hardenGeneratedHelpers = Boolean.TRUE.equals(pctx.getPassData(HARDEN_GENERATED_HELPERS_KEY));
        if (TransformGuards.isRuntimeClass(clazz)
                || (TransformGuards.isGeneratedMethod(method) && !hardenGeneratedHelpers)) {
            JvmObfuscationCoverage.get(pctx).notApplicable(id(), clazz.name(), method.name(), method.descriptor(),
                "guarded-runtime-or-generated");
            return;
        }

        int mask = pctx.random().nextInt() | 1;
        int value = pctx.random().nextInt();
        InsnList noise = new InsnList();
        noise.add(pushInt(value ^ mask));
        noise.add(pushInt(mask));
        noise.add(new InsnNode(Opcodes.IXOR));
        noise.add(new InsnNode(Opcodes.POP));
        method.instructions().insert(noise);
        method.asmNode().maxStack = Math.max(method.asmNode().maxStack, 2);
        clazz.markDirty();
        JvmObfuscationCoverage.get(pctx).safe(id(), clazz.name(), method.name(), method.descriptor(),
            "entry-stack-noise");
        pctx.invalidate(method);
    }

    private org.objectweb.asm.tree.AbstractInsnNode pushInt(int value) {
        if (value >= -1 && value <= 5) {
            return new InsnNode(Opcodes.ICONST_0 + value);
        }
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new IntInsnNode(Opcodes.BIPUSH, value);
        }
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return new IntInsnNode(Opcodes.SIPUSH, value);
        }
        return new LdcInsnNode(value);
    }
}

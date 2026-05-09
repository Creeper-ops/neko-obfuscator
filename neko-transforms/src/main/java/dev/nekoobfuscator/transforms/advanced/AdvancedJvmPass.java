package dev.nekoobfuscator.transforms.advanced;

import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.core.util.AsmUtil;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Advanced JVM obfuscation techniques:
 * - Dead code insertion with valid but confusing bytecode sequences
 * - Overlapping exception handlers
 * - Synthetic debug attributes that mislead
 * - Switch obfuscation (if-else to switch and vice versa)
 */
public final class AdvancedJvmPass implements TransformPass {

    @Override public String id() { return "advancedJvm"; }
    @Override public String name() { return "Advanced JVM Obfuscation"; }
    @Override public TransformPhase phase() { return TransformPhase.TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }
    @Override public Set<String> dependsOn() {
        return Set.of("controlFlowFlattening", "stringEncryption", "invokeDynamic",
                       "outliner", "stackObfuscation");
    }

    @Override
    public void transformClass(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Class clazz = pctx.currentL1Class();
        ClassNode cn = clazz.asmNode();

        // Obfuscate source file name
        cn.sourceFile = generateFakeSourceName(pctx);

        // Add synthetic attributes
        if (cn.attrs == null) cn.attrs = new ArrayList<>();
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        if (!method.hasCode() || method.isConstructor()) return;

        MethodNode mn = method.asmNode();

        // Insert a reachable opaque fake path so cleanup cannot delete it as dead code.
        insertDeadCode(mn, pctx);

        // Add overlapping exception handlers
        addOverlappingHandlers(mn, pctx);

        // Obfuscate local variable table
        obfuscateLocalVariables(mn, pctx);

        pctx.currentL1Class().markDirty();
        JvmObfuscationCoverage.get(pctx).safe(id(), method.owner().name(), method.name(), method.descriptor(),
            "reachable-opaque-stack-and-debug-noise");
    }

    private void insertDeadCode(MethodNode mn, PipelineContext pctx) {
        InsnList insns = mn.instructions;
        LabelNode body = new LabelNode();
        LabelNode fake = new LabelNode();
        int state = pctx.random().nextInt();
        int mask = pctx.random().nextInt() | 1;
        InsnList opaque = new InsnList();
        opaque.add(AsmUtil.pushIntAny(state ^ mask));
        opaque.add(AsmUtil.pushIntAny(mask));
        opaque.add(new InsnNode(Opcodes.IXOR));
        opaque.add(new LookupSwitchInsnNode(fake, new int[] { state }, new LabelNode[] { body }));
        opaque.add(fake);
        opaque.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        opaque.add(new InsnNode(Opcodes.DUP));
        opaque.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
            "java/lang/IllegalStateException", "<init>", "()V", false));
        opaque.add(new InsnNode(Opcodes.ATHROW));
        opaque.add(body);
        insns.insert(opaque);
        mn.maxStack = Math.max(mn.maxStack, 4);
    }

    private void addOverlappingHandlers(MethodNode mn, PipelineContext pctx) {
        if (mn.tryCatchBlocks == null || mn.tryCatchBlocks.isEmpty()) return;
        if (pctx.random().nextDouble() > 0.3) return;

        // Add a duplicate exception handler for the same range
        // This creates overlapping handlers that confuse decompilers
        InsnList insns = mn.instructions;
        LabelNode handlerLabel = new LabelNode();

        // Find first real instruction for try range
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        insns.insertBefore(insns.getFirst(), tryStart);
        insns.add(tryEnd);

        // Handler that catches RuntimeException and re-throws
        InsnList handler = new InsnList();
        handler.add(handlerLabel);
        handler.add(new InsnNode(Opcodes.ATHROW)); // re-throw
        insns.add(handler);

        mn.tryCatchBlocks.add(new TryCatchBlockNode(
            tryStart, tryEnd, handlerLabel, "java/lang/VirtualMachineError"));
    }

    private void obfuscateLocalVariables(MethodNode mn, PipelineContext pctx) {
        // Generate misleading local variable table
        if (mn.localVariables != null) {
            for (LocalVariableNode lvn : mn.localVariables) {
                // Randomize variable names
                lvn.name = generateFakeVarName(pctx);
            }
        }
    }

    private String generateFakeSourceName(PipelineContext pctx) {
        String[] fakes = {
            "", "\u0000", "SourceFile", "a.java", "\u200b.java", "NativeMethod",
            "<generated>", "Module", "obj.cpp", "Compiled.kt", "Synth.scala",
            "lib.rs", "vmlinux", "main.go", "anon.dart", "\u202e.java",
            "\u200c.java", "stub.aj"
        };
        return fakes[pctx.random().nextInt(fakes.length)];
    }

    private String generateFakeVarName(PipelineContext pctx) {
        String[] prefixes = {
            "\u200b", "\u00a0", "Il", "O0", "lI", "I1",
            "_$_", "$$\u200d", "ll", "II", "OO", "i_l", "il_", "lI1",
            "\u034f", "\u2060", "\u206b", "\u180e"
        };
        return prefixes[pctx.random().nextInt(prefixes.length)] + pctx.random().nextInt(100);
    }
}

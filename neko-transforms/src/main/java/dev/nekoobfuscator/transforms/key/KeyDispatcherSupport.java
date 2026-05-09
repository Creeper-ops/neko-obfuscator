package dev.nekoobfuscator.transforms.key;

import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.core.util.AsmUtil;
import dev.nekoobfuscator.transforms.data.NumberEncryptionPass;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Emits one class-local key dispatcher with per-class constants and switch shape.
 */
public final class KeyDispatcherSupport {
    public static final String ENABLE_OPTION = "keyDispatcher";
    public static final String DEPTH_OPTION = "keyDispatcherDepth";
    private static final String PASS_DATA_KEY = "keyDispatcherSupport.profiles";
    private static final String RUNTIME_OWNER = "dev/nekoobfuscator/runtime/NekoKeyDerivation";

    private KeyDispatcherSupport() {}

    public static boolean enabled(TransformConfig config) {
        if (config == null) return false;
        Object value = config.options().get(ENABLE_OPTION);
        return value instanceof Boolean bool && bool;
    }

    public static Profile getOrCreate(PipelineContext pctx, L1Class clazz, TransformConfig config) {
        return getOrCreate(pctx, clazz, config, "default", 0);
    }

    public static Profile getOrCreate(PipelineContext pctx, L1Class clazz, TransformConfig config,
            String namespace, int variantComponent) {
        Map<String, Profile> profiles = pctx.getPassData(PASS_DATA_KEY);
        if (profiles == null) {
            profiles = new HashMap<>();
            pctx.putPassData(PASS_DATA_KEY, profiles);
        }
        int variants = dispatcherVariants(config);
        int variant = Math.floorMod(variantComponent, variants);
        String profileKey = clazz.name() + "|" + namespace + "|" + variant;
        Profile existing = profiles.get(profileKey);
        if (existing != null) return existing;

        int depth = dispatcherDepth(config);
        String ns = namespace == null || namespace.isBlank() ? "k" : namespace.substring(0, 1);
        String methodName = uniqueMethodName(clazz,
            "__neko_k" + ns + Integer.toUnsignedString(pctx.random().nextInt(), 36));
        long preXor = pctx.random().nextLong();
        long postXor = pctx.random().nextLong();
        long[] addends = new long[depth];
        long[] multipliers = new long[depth];
        int[] rotates = new int[depth];
        int[] modes = new int[depth];
        for (int i = 0; i < depth; i++) {
            addends[i] = pctx.random().nextLong();
            multipliers[i] = pctx.random().nextLong() | 1L;
            rotates[i] = 7 + Math.floorMod(pctx.random().nextInt(), 47);
            modes[i] = Math.floorMod(pctx.random().nextInt(), 4);
        }

        Profile profile = new Profile(methodName, preXor, postXor, addends, multipliers, rotates, modes);
        emitDispatcher(clazz, profile);
        profiles.put(profileKey, profile);
        clazz.markDirty();
        return profile;
    }

    public static void emitDispatchCall(InsnList insns, L1Class clazz, Profile profile, int component) {
        emitDispatchCall(insns, clazz, profile, component, false);
    }

    public static void emitDispatchCall(InsnList insns, L1Class clazz, Profile profile,
            int component, boolean generatedNumeric) {
        insns.add(generatedNumeric ? NumberEncryptionPass.generatedInt(component) : AsmUtil.pushIntAny(component));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, clazz.name(), profile.methodName(), "(JI)J",
            (clazz.access() & Opcodes.ACC_INTERFACE) != 0));
    }

    public static long dispatch(Profile profile, long state, int component) {
        long value = state ^ profile.preXor();
        int bucket = Math.floorMod(component ^ (int) profile.postXor(), profile.depth());
        value = dispatchBucket(profile, bucket, value, component);
        return DynamicKeyDerivationEngine.finalize_(value ^ profile.postXor());
    }

    private static void emitDispatcher(L1Class clazz, Profile profile) {
        int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        if ((clazz.access() & Opcodes.ACC_INTERFACE) != 0) {
            access |= Opcodes.ACC_PUBLIC;
        } else {
            access |= Opcodes.ACC_PRIVATE;
        }
        MethodNode method = new MethodNode(access, profile.methodName(), "(JI)J", null, null);
        InsnList insns = method.instructions;
        int stateLocal = 0;
        int componentLocal = 2;
        int valueLocal = 3;

        insns.add(new VarInsnNode(Opcodes.LLOAD, stateLocal));
        insns.add(new LdcInsnNode(profile.preXor()));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, valueLocal));

        int[] keys = new int[profile.depth()];
        LabelNode[] labels = new LabelNode[profile.depth()];
        for (int i = 0; i < profile.depth(); i++) {
            keys[i] = i;
            labels[i] = new LabelNode();
        }
        LabelNode defaultLabel = labels[0];
        insns.add(new VarInsnNode(Opcodes.ILOAD, componentLocal));
        insns.add(AsmUtil.pushIntAny((int) profile.postXor()));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(AsmUtil.pushIntAny(profile.depth()));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "floorMod", "(II)I", false));
        insns.add(new LookupSwitchInsnNode(defaultLabel, keys, labels));

        for (int i = 0; i < profile.depth(); i++) {
            insns.add(labels[i]);
            emitBucket(insns, profile, i, valueLocal, componentLocal);
        }

        method.maxLocals = 5;
        method.maxStack = 8;
        clazz.asmNode().methods.add(method);
    }

    private static void emitBucket(InsnList insns, Profile profile, int bucket, int valueLocal, int componentLocal) {
        switch (profile.modes()[bucket]) {
            case 1 -> emitModeOne(insns, profile, bucket, valueLocal, componentLocal);
            case 2 -> emitModeTwo(insns, profile, bucket, valueLocal, componentLocal);
            case 3 -> emitModeThree(insns, profile, bucket, valueLocal, componentLocal);
            default -> emitModeZero(insns, profile, bucket, valueLocal, componentLocal);
        }
        insns.add(new LdcInsnNode(profile.postXor()));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME_OWNER, "finalize_", "(J)J", false));
        insns.add(new InsnNode(Opcodes.LRETURN));
    }

    private static long dispatchBucket(Profile profile, int bucket, long value, int component) {
        long addend = profile.addends()[bucket];
        long multiplier = profile.multipliers()[bucket];
        int rotate = profile.rotates()[bucket];
        return switch (profile.modes()[bucket]) {
            case 1 -> Long.rotateRight((value ^ addend) + (multiplier * (long) (component | 1)), rotate);
            case 2 -> Long.rotateLeft(
                (value + (multiplier ^ (long) component)) ^ Long.rotateLeft(addend, component & 31), rotate);
            case 3 -> Long.rotateRight((value - addend) ^ (multiplier + (long) component), rotate);
            default -> Long.rotateLeft((value + addend) ^ (multiplier * (long) component), rotate);
        };
    }

    private static void emitModeZero(InsnList insns, Profile profile, int bucket, int valueLocal, int componentLocal) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, valueLocal));
        insns.add(new LdcInsnNode(profile.addends()[bucket]));
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new LdcInsnNode(profile.multipliers()[bucket]));
        insns.add(new VarInsnNode(Opcodes.ILOAD, componentLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LMUL));
        insns.add(new InsnNode(Opcodes.LXOR));
        emitRotate(insns, "rotateLeft", profile.rotates()[bucket]);
    }

    private static void emitModeOne(InsnList insns, Profile profile, int bucket, int valueLocal, int componentLocal) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, valueLocal));
        insns.add(new LdcInsnNode(profile.addends()[bucket]));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new LdcInsnNode(profile.multipliers()[bucket]));
        insns.add(new VarInsnNode(Opcodes.ILOAD, componentLocal));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IOR));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LMUL));
        insns.add(new InsnNode(Opcodes.LADD));
        emitRotate(insns, "rotateRight", profile.rotates()[bucket]);
    }

    private static void emitModeTwo(InsnList insns, Profile profile, int bucket, int valueLocal, int componentLocal) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, valueLocal));
        insns.add(new LdcInsnNode(profile.multipliers()[bucket]));
        insns.add(new VarInsnNode(Opcodes.ILOAD, componentLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new LdcInsnNode(profile.addends()[bucket]));
        insns.add(new VarInsnNode(Opcodes.ILOAD, componentLocal));
        insns.add(AsmUtil.pushIntAny(31));
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "rotateLeft", "(JI)J", false));
        insns.add(new InsnNode(Opcodes.LXOR));
        emitRotate(insns, "rotateLeft", profile.rotates()[bucket]);
    }

    private static void emitModeThree(InsnList insns, Profile profile, int bucket, int valueLocal, int componentLocal) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, valueLocal));
        insns.add(new LdcInsnNode(profile.addends()[bucket]));
        insns.add(new InsnNode(Opcodes.LSUB));
        insns.add(new LdcInsnNode(profile.multipliers()[bucket]));
        insns.add(new VarInsnNode(Opcodes.ILOAD, componentLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new InsnNode(Opcodes.LXOR));
        emitRotate(insns, "rotateRight", profile.rotates()[bucket]);
    }

    private static void emitRotate(InsnList insns, String methodName, int rotate) {
        insns.add(AsmUtil.pushIntAny(rotate));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", methodName, "(JI)J", false));
    }

    private static int dispatcherDepth(TransformConfig config) {
        if (config == null) return 4;
        Object value = config.options().get(DEPTH_OPTION);
        int requested = value instanceof Number number ? number.intValue() : 4;
        return Math.max(1, Math.min(16, requested));
    }

    private static int dispatcherVariants(TransformConfig config) {
        if (config == null) return 2;
        Object value = config.options().get("keyDispatcherVariants");
        int requested = value instanceof Number number ? number.intValue() : 2;
        return Math.max(1, Math.min(4, requested));
    }

    private static String uniqueMethodName(L1Class clazz, String base) {
        String name = base;
        int suffix = 0;
        while (hasMethod(clazz, name)) {
            name = base + "_" + (++suffix);
        }
        return name;
    }

    private static boolean hasMethod(L1Class clazz, String name) {
        for (MethodNode method : clazz.asmNode().methods) {
            if (method.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    public record Profile(String methodName, long preXor, long postXor, long[] addends,
                          long[] multipliers, int[] rotates, int[] modes) {
        public int depth() {
            return addends.length;
        }
    }
}

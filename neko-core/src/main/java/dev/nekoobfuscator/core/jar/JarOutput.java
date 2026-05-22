package dev.nekoobfuscator.core.jar;

import dev.nekoobfuscator.core.ir.l1.L1Class;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.util.CheckClassAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.*;

/**
 * Writes L1 IR classes and resource entries back to a JAR file.
 */
public final class JarOutput {
    private static final Logger log = LoggerFactory.getLogger(JarOutput.class);

    private final ClassHierarchy hierarchy;

    public JarOutput(ClassHierarchy hierarchy) {
        this.hierarchy = hierarchy;
    }

    public void write(Path outputPath, List<L1Class> classes, List<ResourceEntry> resources,
                      Manifest manifest) throws IOException {
        write(outputPath.toFile(), classes, resources, manifest);
    }

    public void write(File outputFile, List<L1Class> classes, List<ResourceEntry> resources,
                      Manifest manifest) throws IOException {
        try (JarOutputStream jos = manifest != null
                ? new JarOutputStream(new BufferedOutputStream(new FileOutputStream(outputFile)), manifest)
                : new JarOutputStream(new BufferedOutputStream(new FileOutputStream(outputFile)))) {

            Set<String> written = new HashSet<>();

            for (L1Class l1 : classes) {
                String entryName = l1.asmNode().name + ".class";
                if (written.add(entryName)) {
                    byte[] bytecode = writeClass(l1);
                    JarEntry je = new JarEntry(entryName);
                    jos.putNextEntry(je);
                    jos.write(bytecode);
                    jos.closeEntry();
                    log.debug("Wrote class: {}", l1.name());
                }
            }

            for (ResourceEntry re : resources) {
                if (re.name().equals("META-INF/MANIFEST.MF")) continue;
                if (written.add(re.name())) {
                    JarEntry je = new JarEntry(re.name());
                    jos.putNextEntry(je);
                    jos.write(re.data());
                    jos.closeEntry();
                    log.debug("Wrote resource: {}", re.name());
                }
            }
        }
        log.info("Wrote {} classes and {} resources to {}", classes.size(), resources.size(), outputFile);
    }

    public static byte[] previewClassBytes(ClassHierarchy hierarchy, L1Class l1) {
        HierarchyClassWriter cw = new HierarchyClassWriter(hierarchy);
        l1.asmNode().accept(cw);
        return cw.toByteArray();
    }

    private byte[] writeClass(L1Class l1) {
        try {
            stripExistingFrames(l1);
            HierarchyClassWriter cw = new HierarchyClassWriter(hierarchy);
            l1.asmNode().accept(cw);
            byte[] bytecode = cw.toByteArray();
            validateWrittenClass(l1, bytecode);
            return bytecode;
        } catch (Throwable e) {
            log.error("Class write failure for {}", l1.name(), e);
            String verifierDetails = diagnoseFrameFailure(l1);
            if (!verifierDetails.isBlank()) {
                log.error("Verifier details for {}:{}{}", l1.name(), System.lineSeparator(), verifierDetails);
            }
            String methodDetails = diagnoseMethods(l1);
            if (!methodDetails.isBlank()) {
                log.error("Method frame details for {}:{}{}", l1.name(), System.lineSeparator(), methodDetails);
            }
            String sizeDetails = methodSizeSummary(l1);
            if (!sizeDetails.isBlank()) {
                log.error("Largest methods for {}:{}{}", l1.name(), System.lineSeparator(), sizeDetails);
            }
            throw new RuntimeException("Cannot write verifiable class " + l1.name()
                + " with COMPUTE_FRAMES; refusing COMPUTE_MAXS/-noverify fallback. Largest methods:"
                + System.lineSeparator() + sizeDetails, e);
        }
    }

    private String methodSizeSummary(L1Class l1) {
        List<MethodSize> sizes = new ArrayList<>();
        for (MethodNode method : l1.asmNode().methods) {
            if (method.instructions == null || method.instructions.size() == 0) continue;
            sizes.add(new MethodSize(method.name, method.desc, estimateMethodBytes(method)));
        }
        sizes.sort(Comparator.comparingInt(MethodSize::bytes).reversed());
        StringBuilder out = new StringBuilder();
        int limit = Math.min(8, sizes.size());
        for (int i = 0; i < limit; i++) {
            MethodSize size = sizes.get(i);
            out.append("  ")
                .append(size.bytes())
                .append(" bytes estimated  ")
                .append(size.name())
                .append(size.desc())
                .append(System.lineSeparator());
        }
        return out.toString().trim();
    }

    private String diagnoseMethods(L1Class l1) {
        StringBuilder out = new StringBuilder();
        for (MethodNode method : l1.asmNode().methods) {
            if (method.instructions == null || method.instructions.size() == 0) continue;
            try {
                Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
                analyzer.analyze(l1.name(), method);
            } catch (Throwable t) {
                out.append("  analyzer ")
                    .append(method.name)
                    .append(method.desc)
                    .append(": ")
                    .append(t.getClass().getSimpleName())
                    .append(": ")
                    .append(t.getMessage())
                    .append(System.lineSeparator());
                if (t instanceof AnalyzerException analyzerException) {
                    appendInstructionWindow(out, method, analyzerException.node);
                }
            }
            try {
                ClassWriter cw = new HierarchyClassWriter(hierarchy);
                cw.visit(
                    l1.asmNode().version,
                    l1.asmNode().access,
                    l1.asmNode().name,
                    l1.asmNode().signature,
                    l1.asmNode().superName,
                    l1.asmNode().interfaces == null
                        ? null
                        : l1.asmNode().interfaces.toArray(String[]::new)
                );
                method.accept(cw);
                cw.visitEnd();
                cw.toByteArray();
            } catch (Throwable t) {
                out.append("  writer ")
                    .append(method.name)
                    .append(method.desc)
                    .append(": ")
                    .append(t.getClass().getSimpleName())
                    .append(": ")
                    .append(t.getMessage())
                    .append(System.lineSeparator());
            }
        }
        return out.toString().trim();
    }

    private void appendInstructionWindow(
        StringBuilder out,
        MethodNode method,
        AbstractInsnNode target
    ) {
        if (target == null) return;
        AbstractInsnNode[] insns = method.instructions.toArray();
        int index = -1;
        for (int i = 0; i < insns.length; i++) {
            if (insns[i] == target) {
                index = i;
                break;
            }
        }
        if (index < 0) return;
        int from = Math.max(0, index - 6);
        int to = Math.min(insns.length, index + 7);
        for (int i = from; i < to; i++) {
            out.append("    ")
                .append(i == index ? ">" : " ")
                .append(i)
                .append(": ")
                .append(insnSummary(insns[i]))
                .append(System.lineSeparator());
        }
        appendHandlerAndIncomingEvidence(out, method, target, insns);
    }

    private void appendHandlerAndIncomingEvidence(
        StringBuilder out,
        MethodNode method,
        AbstractInsnNode target,
        AbstractInsnNode[] insns
    ) {
        Set<org.objectweb.asm.tree.LabelNode> labels = labelsImmediatelyBefore(target);
        if (labels.isEmpty()) return;
        out.append("    labels-before-target:");
        for (org.objectweb.asm.tree.LabelNode label : labels) {
            out.append(' ').append(labelId(label));
        }
        out.append(System.lineSeparator());
        if (method.tryCatchBlocks != null) {
            for (org.objectweb.asm.tree.TryCatchBlockNode tcb : method.tryCatchBlocks) {
                if (labels.contains(tcb.handler)) {
                    out.append("    try-catch-handler-entry: handler=")
                        .append(labelId(tcb.handler))
                        .append(" start=")
                        .append(labelId(tcb.start))
                        .append(" end=")
                        .append(labelId(tcb.end))
                        .append(" type=")
                        .append(tcb.type == null ? "any" : tcb.type)
                        .append(System.lineSeparator());
                }
                if (labels.contains(tcb.start)) {
                    out.append("    try-catch-range-start: start=")
                        .append(labelId(tcb.start))
                        .append(" end=")
                        .append(labelId(tcb.end))
                        .append(" handler=")
                        .append(labelId(tcb.handler))
                        .append(" type=")
                        .append(tcb.type == null ? "any" : tcb.type)
                        .append(System.lineSeparator());
                }
                if (labels.contains(tcb.end)) {
                    out.append("    try-catch-range-end: end=")
                        .append(labelId(tcb.end))
                        .append(" start=")
                        .append(labelId(tcb.start))
                        .append(" handler=")
                        .append(labelId(tcb.handler))
                        .append(" type=")
                        .append(tcb.type == null ? "any" : tcb.type)
                        .append(System.lineSeparator());
                }
            }
        }
        for (int i = 0; i < insns.length; i++) {
            AbstractInsnNode insn = insns[i];
            if (insn instanceof org.objectweb.asm.tree.JumpInsnNode jump && labels.contains(jump.label)) {
                out.append("    incoming-jump: ")
                    .append(i)
                    .append(": ")
                    .append(insnSummary(insn))
                    .append(System.lineSeparator());
                appendInstructionWindowAt(out, insns, i, 4, 3);
            } else if (insn instanceof org.objectweb.asm.tree.LookupSwitchInsnNode ls) {
                if (labels.contains(ls.dflt)) {
                    out.append("    incoming-lookup-default: ")
                        .append(i)
                        .append(System.lineSeparator());
                }
                for (int k = 0; k < ls.labels.size(); k++) {
                    if (labels.contains(ls.labels.get(k))) {
                        out.append("    incoming-lookup-case: ")
                            .append(i)
                            .append(" key=")
                            .append(ls.keys.get(k))
                            .append(System.lineSeparator());
                    }
                }
            } else if (insn instanceof org.objectweb.asm.tree.TableSwitchInsnNode ts) {
                if (labels.contains(ts.dflt)) {
                    out.append("    incoming-table-default: ")
                        .append(i)
                        .append(System.lineSeparator());
                }
                for (int k = 0; k < ts.labels.size(); k++) {
                    if (labels.contains(ts.labels.get(k))) {
                        out.append("    incoming-table-case: ")
                            .append(i)
                            .append(" key=")
                            .append(ts.min + k)
                            .append(System.lineSeparator());
                    }
                }
            }
        }
    }

    private void appendInstructionWindowAt(
        StringBuilder out,
        AbstractInsnNode[] insns,
        int index,
        int before,
        int after
    ) {
        int from = Math.max(0, index - before);
        int to = Math.min(insns.length, index + after + 1);
        for (int i = from; i < to; i++) {
            out.append("      ")
                .append(i == index ? ">" : " ")
                .append(i)
                .append(": ")
                .append(insnSummary(insns[i]))
                .append(System.lineSeparator());
        }
    }

    private Set<org.objectweb.asm.tree.LabelNode> labelsImmediatelyBefore(AbstractInsnNode target) {
        Set<org.objectweb.asm.tree.LabelNode> labels = Collections.newSetFromMap(new IdentityHashMap<>());
        for (
            AbstractInsnNode scan = target.getPrevious();
            scan != null && scan.getOpcode() < 0;
            scan = scan.getPrevious()
        ) {
            if (scan instanceof org.objectweb.asm.tree.LabelNode label) {
                labels.add(label);
            }
        }
        return labels;
    }

    private String labelId(org.objectweb.asm.tree.LabelNode label) {
        return "Label@" + Integer.toHexString(System.identityHashCode(label));
    }

    private String insnSummary(AbstractInsnNode insn) {
        if (insn == null) return "<null>";
        String name = opcodeName(insn.getOpcode());
        if (insn instanceof org.objectweb.asm.tree.LabelNode label) {
            return labelId(label);
        }
        if (insn instanceof org.objectweb.asm.tree.JumpInsnNode jump) {
            return name + " -> " + labelId(jump.label);
        }
        if (insn instanceof org.objectweb.asm.tree.VarInsnNode var) {
            return name + " " + var.var;
        }
        if (insn instanceof org.objectweb.asm.tree.MethodInsnNode method) {
            return name + " " + method.owner + "." + method.name + method.desc;
        }
        if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field) {
            return name + " " + field.owner + "." + field.name + ":" + field.desc;
        }
        if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc) {
            return name + " " + String.valueOf(ldc.cst);
        }
        if (insn instanceof org.objectweb.asm.tree.IntInsnNode intInsn) {
            return name + " " + intInsn.operand;
        }
        if (insn instanceof org.objectweb.asm.tree.IincInsnNode iinc) {
            return "IINC " + iinc.var + " " + iinc.incr;
        }
        if (insn instanceof org.objectweb.asm.tree.LookupSwitchInsnNode ls) {
            return "LOOKUPSWITCH keys=" + ls.keys.size();
        }
        if (insn instanceof org.objectweb.asm.tree.TableSwitchInsnNode ts) {
            return "TABLESWITCH " + ts.min + ".." + ts.max;
        }
        return name;
    }

    private String opcodeName(int opcode) {
        if (opcode < 0) return "META";
        String[] names = org.objectweb.asm.util.Printer.OPCODES;
        return opcode < names.length ? names[opcode] : "OP" + opcode;
    }

    private void stripExistingFrames(L1Class l1) {
        for (MethodNode method : l1.asmNode().methods) {
            if (method.instructions == null || method.instructions.size() == 0) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof FrameNode) {
                    method.instructions.remove(insn);
                }
            }
        }
    }

    private int estimateMethodBytes(MethodNode method) {
        int offset = 0;
        for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            offset += estimateInstructionBytes(insn, offset);
        }
        return offset;
    }

    private int estimateInstructionBytes(org.objectweb.asm.tree.AbstractInsnNode insn, int offset) {
        int opcode = insn.getOpcode();
        if (opcode < 0) return 0;
        if (insn instanceof org.objectweb.asm.tree.VarInsnNode var) {
            return var.var > 255 ? 4 : 2;
        }
        if (insn instanceof org.objectweb.asm.tree.IincInsnNode iinc) {
            return iinc.var > 255 || iinc.incr < Byte.MIN_VALUE || iinc.incr > Byte.MAX_VALUE ? 6 : 3;
        }
        if (insn instanceof org.objectweb.asm.tree.IntInsnNode) {
            return opcode == org.objectweb.asm.Opcodes.SIPUSH ? 3 : 2;
        }
        if (insn instanceof org.objectweb.asm.tree.LdcInsnNode) {
            return 3;
        }
        if (insn instanceof org.objectweb.asm.tree.JumpInsnNode) {
            return opcode == 200 || opcode == 201 ? 5 : 3;
        }
        if (insn instanceof org.objectweb.asm.tree.MethodInsnNode) {
            return opcode == org.objectweb.asm.Opcodes.INVOKEINTERFACE ? 5 : 3;
        }
        if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode) {
            return 5;
        }
        if (insn instanceof org.objectweb.asm.tree.TypeInsnNode) {
            return 3;
        }
        if (insn instanceof org.objectweb.asm.tree.MultiANewArrayInsnNode) {
            return 4;
        }
        if (insn instanceof org.objectweb.asm.tree.TableSwitchInsnNode tableSwitch) {
            return 1 + switchPadding(offset) + 12 + tableSwitch.labels.size() * 4;
        }
        if (insn instanceof org.objectweb.asm.tree.LookupSwitchInsnNode lookupSwitch) {
            return 1 + switchPadding(offset) + 8 + lookupSwitch.labels.size() * 8;
        }
        return switch (opcode) {
            case org.objectweb.asm.Opcodes.GETSTATIC, org.objectweb.asm.Opcodes.PUTSTATIC,
                org.objectweb.asm.Opcodes.GETFIELD, org.objectweb.asm.Opcodes.PUTFIELD,
                org.objectweb.asm.Opcodes.NEW, org.objectweb.asm.Opcodes.ANEWARRAY,
                org.objectweb.asm.Opcodes.CHECKCAST, org.objectweb.asm.Opcodes.INSTANCEOF -> 3;
            case org.objectweb.asm.Opcodes.NEWARRAY, org.objectweb.asm.Opcodes.BIPUSH -> 2;
            default -> 1;
        };
    }

    private int switchPadding(int offset) {
        return (4 - ((offset + 1) & 3)) & 3;
    }

    private record MethodSize(String name, String desc, int bytes) {
    }

    private String diagnoseFrameFailure(L1Class l1) {
        try {
            ClassWriter rawWriter = new ClassWriter(0);
            l1.asmNode().accept(rawWriter);

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            CheckClassAdapter.verify(new ClassReader(rawWriter.toByteArray()), null, false, pw);
            pw.flush();
            return sw.toString().trim();
        } catch (Throwable diagnosticError) {
            return "<failed to run verifier diagnostics: " + diagnosticError.getMessage() + ">";
        }
    }

    private void validateWrittenClass(L1Class l1, byte[] bytecode) {
        try {
            ClassReader reader = new ClassReader(bytecode);
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            CheckClassAdapter.verify(reader, null, false, pw);
            pw.flush();
            String diagnostics = sw.toString().trim();
            if (!diagnostics.isBlank() && !containsOnlyClassNotFoundErrors(diagnostics)) {
                throw new IllegalStateException("ASM verifier rejected " + l1.name() + ":" + System.lineSeparator() + diagnostics);
            }
            if (scanRawBytecode(bytecode)) {
                throw new IllegalStateException("Raw bytecode scan found an illegal opcode in " + l1.name());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to validate written class " + l1.name(), t);
        }
    }

    private static boolean containsOnlyClassNotFoundErrors(String diagnostics) {
        if (diagnostics.isBlank()) return true;
        String lower = diagnostics.toLowerCase(Locale.ROOT);
        if (lower.contains("bad instruction")
                || lower.contains("bad type on operand stack")
                || lower.contains("inconsistent stackmap")
                || lower.contains("expecting a stackmap frame")
                || lower.contains("expecting type")
                || lower.contains("get long/double overflows")
                || lower.contains("bad local variable type")
                || lower.contains("invalid opcode")) {
            return false;
        }
        if (diagnostics.contains("ClassNotFoundException")
                || diagnostics.contains("TypeNotPresentException")) {
            return true;
        }
        for (String line : diagnostics.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.contains("AnalyzerException")
                    && (trimmed.contains("ClassNotFoundException") || trimmed.contains("not present"))) continue;
            if (trimmed.contains("TypeNotPresentException")) continue;
            if (trimmed.contains("ClassNotFoundException")) continue;
            if (trimmed.startsWith("at ")) continue;
            if (trimmed.startsWith("...")) continue;
            if (trimmed.startsWith("Caused by")
                    && (trimmed.contains("ClassNotFoundException") || trimmed.contains("TypeNotPresentException"))) continue;
            return false;
        }
        return true;
    }

    private static boolean scanRawBytecode(byte[] bytes) {
        try {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes);
            buf.getInt();
            buf.getShort();
            buf.getShort();
            int cpCount = buf.getShort() & 0xFFFF;
            String[] utf8 = new String[cpCount];
            int i = 1;
            while (i < cpCount) {
                int tag = buf.get() & 0xFF;
                switch (tag) {
                    case 1 -> {
                        int len = buf.getShort() & 0xFFFF;
                        byte[] str = new byte[len];
                        buf.get(str);
                        utf8[i] = new String(str, java.nio.charset.StandardCharsets.UTF_8);
                    }
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> buf.position(buf.position() + 4);
                    case 5, 6 -> {
                        buf.position(buf.position() + 8);
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> buf.position(buf.position() + 2);
                    case 15 -> buf.position(buf.position() + 3);
                    default -> {
                        return true;
                    }
                }
                i++;
            }
            buf.position(buf.position() + 6);
            int ifaceCount = buf.getShort() & 0xFFFF;
            buf.position(buf.position() + ifaceCount * 2);
            skipMembers(buf);
            int methodCount = buf.getShort() & 0xFFFF;
            for (int m = 0; m < methodCount; m++) {
                buf.position(buf.position() + 6);
                int attrs = buf.getShort() & 0xFFFF;
                for (int a = 0; a < attrs; a++) {
                    int nameIndex = buf.getShort() & 0xFFFF;
                    int attrLen = buf.getInt();
                    String name = nameIndex < utf8.length ? utf8[nameIndex] : null;
                    if ("Code".equals(name)) {
                        int codeStart = buf.position();
                        buf.position(buf.position() + 4);
                        int codeLen = buf.getInt();
                        if (hasIllegalOpcode(bytes, buf.position(), codeLen)) return true;
                        buf.position(codeStart + attrLen);
                    } else {
                        buf.position(buf.position() + attrLen);
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    private static void skipMembers(java.nio.ByteBuffer buf) {
        int fieldCount = buf.getShort() & 0xFFFF;
        for (int f = 0; f < fieldCount; f++) {
            buf.position(buf.position() + 6);
            int attrs = buf.getShort() & 0xFFFF;
            for (int a = 0; a < attrs; a++) {
                buf.position(buf.position() + 2);
                int attrLen = buf.getInt();
                buf.position(buf.position() + attrLen);
            }
        }
    }

    private static boolean hasIllegalOpcode(byte[] bytes, int start, int len) {
        int end = start + len;
        int p = start;
        while (p < end) {
            int op = bytes[p] & 0xFF;
            if (op > 201 && op != 196) return true;
            p += opcodeLength(bytes, p, end, start);
        }
        return p != end;
    }

    private static int opcodeLength(byte[] bytes, int p, int end, int codeStart) {
        int op = bytes[p] & 0xFF;
        return switch (op) {
            case 16, 18, 21, 22, 23, 24, 25, 54, 55, 56, 57, 58, 169, 188 -> 2;
            case 17, 19, 20, 132, 153, 154, 155, 156, 157, 158, 159, 160, 161, 162, 163, 164,
                 165, 166, 167, 168, 178, 179, 180, 181, 182, 183, 184, 187, 189, 192, 193,
                 198, 199 -> 3;
            case 185, 186, 200, 201 -> 5;
            case 197 -> 4;
            case 196 -> wideLength(bytes, p, end);
            case 170 -> tableSwitchLength(bytes, p, end, codeStart);
            case 171 -> lookupSwitchLength(bytes, p, end, codeStart);
            default -> 1;
        };
    }

    private static int wideLength(byte[] bytes, int p, int end) {
        if (p + 1 >= end) return end - p + 1;
        int widened = bytes[p + 1] & 0xFF;
        return widened == 132 ? 6 : 4;
    }

    private static int tableSwitchLength(byte[] bytes, int p, int end, int codeStart) {
        int rel = p - codeStart;
        int q = p + 1 + ((4 - ((rel + 1) & 3)) & 3);
        if (q + 12 > end) return end - p + 1;
        int low = readInt(bytes, q + 4);
        int high = readInt(bytes, q + 8);
        long count = (long) high - low + 1L;
        if (count < 0 || count > 1_000_000L) return end - p + 1;
        return (q - p) + 12 + (int) count * 4;
    }

    private static int lookupSwitchLength(byte[] bytes, int p, int end, int codeStart) {
        int rel = p - codeStart;
        int q = p + 1 + ((4 - ((rel + 1) & 3)) & 3);
        if (q + 8 > end) return end - p + 1;
        int pairs = readInt(bytes, q + 4);
        if (pairs < 0 || pairs > 1_000_000) return end - p + 1;
        return (q - p) + 8 + pairs * 8;
    }

    private static int readInt(byte[] bytes, int p) {
        return ((bytes[p] & 0xFF) << 24)
            | ((bytes[p + 1] & 0xFF) << 16)
            | ((bytes[p + 2] & 0xFF) << 8)
            | (bytes[p + 3] & 0xFF);
    }
    /**
     * ClassWriter that uses ClassHierarchy for common superclass computation.
     */
    private static final class HierarchyClassWriter extends ClassWriter {
        private final ClassHierarchy hierarchy;
        HierarchyClassWriter(ClassHierarchy hierarchy) {
            super(COMPUTE_FRAMES);
            this.hierarchy = hierarchy;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (hierarchy != null) {
                String result = hierarchy.getCommonSuperClass(type1, type2);
                if (result != null && !result.equals("java/lang/Object")) {
                    return result;
                }
            }
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (Throwable t) {
                return "java/lang/Object";
            }
        }
    }
}

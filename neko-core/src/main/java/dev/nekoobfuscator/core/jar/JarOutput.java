package dev.nekoobfuscator.core.jar;

import dev.nekoobfuscator.core.ir.l1.L1Class;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
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

    private byte[] writeClass(L1Class l1) {
        try {
            HierarchyClassWriter cw = new HierarchyClassWriter(hierarchy);
            l1.asmNode().accept(cw);
            byte[] bytecode = cw.toByteArray();
            validateWrittenClass(l1, bytecode);
            return bytecode;
        } catch (Throwable e) {
            String verifierDetails = diagnoseFrameFailure(l1);
            if (!verifierDetails.isBlank()) {
                log.error("Verifier details for {}:{}{}", l1.name(), System.lineSeparator(), verifierDetails);
            }
            throw new RuntimeException("Cannot write verifiable class " + l1.name()
                + " with COMPUTE_FRAMES; refusing COMPUTE_MAXS/-noverify fallback", e);
        }
    }

    private String diagnoseFrameFailure(L1Class l1) {
        try {
            ClassWriter rawWriter = new ClassWriter(0);
            l1.asmNode().accept(rawWriter);

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            CheckClassAdapter.verify(new ClassReader(rawWriter.toByteArray()), JarOutput.class.getClassLoader(), false, pw);
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
            CheckClassAdapter.verify(reader, JarOutput.class.getClassLoader(), false, pw);
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

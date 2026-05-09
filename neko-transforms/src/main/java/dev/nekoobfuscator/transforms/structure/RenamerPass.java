package dev.nekoobfuscator.transforms.structure;

import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.api.config.ClassRule;
import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Whole-program renamer for application classes and their internal references.
 */
public final class RenamerPass implements TransformPass {
    public static final String CLASS_MAP_KEY = "renamer.classMap";
    public static final String MAP_LINES_KEY = "renamer.mapLines";
    private static final String MEMBER_MAP_KEY = "renamer.memberMap";
    private static final String REFLECTION_STRING_MAP_KEY = "renamer.reflectionStringMap";
    private static final String INITIALIZED_KEY = "renamer.initialized";
    private static final String DO_NOT_OBFUSCATE_DESC = "Ldev/nekoobfuscator/api/annotation/DoNotObfuscate;";
    private static final String NATIVE_TRANSLATE_DESC = "Ldev/nekoobfuscator/api/annotation/NativeTranslate;";

    @Override public String id() { return "renamer"; }
    @Override public String name() { return "Renamer"; }
    @Override public TransformPhase phase() { return TransformPhase.PRE_TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }

    @Override
    public void transformClass(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        initialize(pctx);
        L1Class clazz = pctx.currentL1Class();
        Map<String, String> classMap = pctx.getPassData(CLASS_MAP_KEY);
        Map<MemberKey, String> memberMap = pctx.getPassData(MEMBER_MAP_KEY);
        Map<String, String> reflectionStringMap = pctx.getPassData(REFLECTION_STRING_MAP_KEY);
        if (classMap == null || memberMap == null || reflectionStringMap == null) return;

        ClassNode remapped = new ClassNode();
        clazz.asmNode().accept(new ClassRemapper(remapped, new NekoRemapper(classMap, memberMap)));
        rewriteReflectionStrings(remapped, reflectionStringMap);
        rewriteClassResourceStrings(clazz.name(), remapped.name, remapped);
        copyInto(clazz.asmNode(), remapped);
        clazz.markDirty();
    }

    @Override
    public void transformMethod(TransformContext ctx) {
    }

    private void initialize(PipelineContext pctx) {
        Boolean initialized = pctx.getPassData(INITIALIZED_KEY);
        if (Boolean.TRUE.equals(initialized)) return;

        TransformConfig config = pctx.config().transforms().get("renamer");
        boolean renameClasses = booleanOption(config, "renameClasses", true);
        boolean renameMembers = booleanOption(config, "renameMembers", true);
        String classPrefix = stringOption(config, "classPrefix", "");
        boolean preserveReflectionStrings = booleanOption(config, "preserveReflectionStrings", true);

        Map<String, String> classMap = new LinkedHashMap<>();
        Map<MemberKey, String> memberMap = new LinkedHashMap<>();
        Map<String, String> reflectionStringMap = new LinkedHashMap<>();
        NameSource classNames = new NameSource(classPrefix);
        NameSource methodNames = new NameSource("");
        NameSource fieldNames = new NameSource("");
        Map<String, String> globalMethodNames = new LinkedHashMap<>();
        Map<String, String> globalFieldNames = new LinkedHashMap<>();

        List<L1Class> classes = new ArrayList<>(pctx.classMap().values());
        classes.sort(Comparator.comparing(L1Class::name));
        Set<String> applicationClasses = new HashSet<>();
        Map<String, L1Class> classByName = new HashMap<>();
        for (L1Class clazz : classes) {
            applicationClasses.add(clazz.name());
            classByName.put(clazz.name(), clazz);
        }
        Set<String> protectedClasses = Set.of();

        for (L1Class clazz : classes) {
            if (renameClasses && canRenameClass(pctx, clazz, protectedClasses)) {
                classMap.put(clazz.name(), classNames.nextInternalName());
            }
        }

        for (L1Class clazz : classes) {
            for (MethodNode method : clazz.asmNode().methods) {
                if (renameMembers && canRenameMethod(pctx, clazz, method, classByName, protectedClasses)) {
                    memberMap.put(MemberKey.method(clazz.name(), method.name, method.desc),
                        globalMethodNames.computeIfAbsent(method.name, ignored -> methodNames.nextSimpleName()));
                }
            }
            for (FieldNode field : clazz.asmNode().fields) {
                if (renameMembers && canRenameField(pctx, clazz, field, protectedClasses)) {
                    memberMap.put(MemberKey.field(clazz.name(), field.name, field.desc),
                        globalFieldNames.computeIfAbsent(field.name, ignored -> fieldNames.nextSimpleName()));
                }
            }
        }

        if (preserveReflectionStrings) {
            buildReflectionStringMap(classMap, memberMap, reflectionStringMap);
        }
        pctx.putPassData(MAP_LINES_KEY, buildMapLines(classMap, memberMap));

        pctx.putPassData(CLASS_MAP_KEY, classMap);
        pctx.putPassData(MEMBER_MAP_KEY, memberMap);
        pctx.putPassData(REFLECTION_STRING_MAP_KEY, reflectionStringMap);
        pctx.putPassData(INITIALIZED_KEY, Boolean.TRUE);
    }

    private List<String> buildMapLines(Map<String, String> classMap, Map<MemberKey, String> memberMap) {
        List<String> lines = new ArrayList<>();
        lines.add("# NekoObfuscator mapping");
        for (Map.Entry<String, String> entry : classMap.entrySet()) {
            lines.add("CLASS " + entry.getKey() + " -> " + entry.getValue());
        }
        for (Map.Entry<MemberKey, String> entry : memberMap.entrySet()) {
            MemberKey key = entry.getKey();
            String newOwner = classMap.getOrDefault(key.owner(), key.owner());
            String kind = key.method() ? "METHOD " : "FIELD ";
            lines.add(kind + key.owner() + "." + key.name() + " " + key.desc()
                + " -> " + newOwner + "." + entry.getValue() + " " + key.desc());
        }
        return lines;
    }

    private boolean canRenameClass(PipelineContext pctx, L1Class clazz, Set<String> protectedClasses) {
        String name = clazz.name();
        if (name.startsWith("dev/nekoobfuscator/runtime/")) return false;
        if (name.startsWith("dev/nekoobfuscator/api/annotation/")) return false;
        if ("module-info".equals(name) || "package-info".equals(name)) return false;
        if (protectedClasses.contains(name)) return false;
        if (excludedByRules(pctx, name)) return false;
        if (hasAnnotation(clazz.asmNode().visibleAnnotations, DO_NOT_OBFUSCATE_DESC)
                || hasAnnotation(clazz.asmNode().invisibleAnnotations, DO_NOT_OBFUSCATE_DESC)) {
            return false;
        }
        return true;
    }

    private boolean canRenameMethod(PipelineContext pctx, L1Class clazz, MethodNode method,
            Map<String, L1Class> classByName, Set<String> protectedClasses) {
        if (protectedClasses.contains(clazz.name()) || excludedByRules(pctx, clazz.name())) return false;
        if ("<init>".equals(method.name) || "<clinit>".equals(method.name)) return false;
        if ("main".equals(method.name) && "([Ljava/lang/String;)V".equals(method.desc)
                && (method.access & Opcodes.ACC_STATIC) != 0) return false;
        if ((method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) return false;
        if (hasAnnotation(method.visibleAnnotations, DO_NOT_OBFUSCATE_DESC)
                || hasAnnotation(method.invisibleAnnotations, DO_NOT_OBFUSCATE_DESC)
                || hasAnnotation(method.visibleAnnotations, NATIVE_TRANSLATE_DESC)
                || hasAnnotation(method.invisibleAnnotations, NATIVE_TRANSLATE_DESC)) {
            return false;
        }
        if ((clazz.access() & Opcodes.ACC_ENUM) != 0
                && (("values".equals(method.name) && method.desc.startsWith("()["))
                    || ("valueOf".equals(method.name) && method.desc.startsWith("(Ljava/lang/String;)")))) {
            return false;
        }
        if (isInterfaceContractMethod(clazz, method, classByName)) {
            return false;
        }
        return true;
    }

    private boolean isInterfaceContractMethod(L1Class clazz, MethodNode method,
            Map<String, L1Class> classByName) {
        if ((method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0) {
            return false;
        }
        if ((clazz.access() & Opcodes.ACC_INTERFACE) != 0) {
            return true;
        }
        return declaresInheritedMethod(clazz.asmNode().superName, method.name, method.desc, classByName, new HashSet<>())
            || declaresInheritedMethodInInterfaces(clazz.asmNode().interfaces, method.name, method.desc, classByName, new HashSet<>());
    }

    private boolean declaresInheritedMethodInInterfaces(List<String> interfaces, String name, String desc,
            Map<String, L1Class> classByName, Set<String> visited) {
        if (interfaces == null) return false;
        for (String iface : interfaces) {
            if (declaresInheritedMethod(iface, name, desc, classByName, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean declaresInheritedMethod(String owner, String name, String desc,
            Map<String, L1Class> classByName, Set<String> visited) {
        if (owner == null || !visited.add(owner)) {
            return false;
        }
        L1Class appClass = classByName.get(owner);
        if (appClass != null) {
            for (MethodNode candidate : appClass.asmNode().methods) {
                if (name.equals(candidate.name) && desc.equals(candidate.desc)) {
                    return true;
                }
            }
            return declaresInheritedMethod(appClass.asmNode().superName, name, desc, classByName, visited)
                || declaresInheritedMethodInInterfaces(appClass.asmNode().interfaces, name, desc, classByName, visited);
        }
        return declaresLibraryMethod(owner, name, desc);
    }

    private boolean declaresLibraryMethod(String owner, String name, String desc) {
        try {
            Class<?> type = Class.forName(owner.replace('/', '.'), false, RenamerPass.class.getClassLoader());
            for (java.lang.reflect.Method method : type.getMethods()) {
                if (name.equals(method.getName()) && desc.equals(Type.getMethodDescriptor(method))) {
                    return true;
                }
            }
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (name.equals(method.getName()) && desc.equals(Type.getMethodDescriptor(method))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return true;
        }
        return false;
    }

    private boolean canRenameField(PipelineContext pctx, L1Class clazz, FieldNode field,
            Set<String> protectedClasses) {
        if (protectedClasses.contains(clazz.name()) || excludedByRules(pctx, clazz.name())) return false;
        if ("serialVersionUID".equals(field.name)) return false;
        if (hasAnnotation(field.visibleAnnotations, DO_NOT_OBFUSCATE_DESC)
                || hasAnnotation(field.invisibleAnnotations, DO_NOT_OBFUSCATE_DESC)
                || hasAnnotation(field.visibleAnnotations, NATIVE_TRANSLATE_DESC)
                || hasAnnotation(field.invisibleAnnotations, NATIVE_TRANSLATE_DESC)) {
            return false;
        }
        if ((clazz.access() & Opcodes.ACC_ENUM) != 0 && "$VALUES".equals(field.name)) return false;
        if ((field.access & Opcodes.ACC_ENUM) != 0) return false;
        return true;
    }

    private void buildReflectionStringMap(Map<String, String> classMap, Map<MemberKey, String> memberMap,
            Map<String, String> reflectionStringMap) {
        for (Map.Entry<String, String> entry : classMap.entrySet()) {
            reflectionStringMap.put(entry.getKey(), entry.getValue());
            reflectionStringMap.put(entry.getKey().replace('/', '.'), entry.getValue().replace('/', '.'));
            reflectionStringMap.put(entry.getKey() + ".class", entry.getValue() + ".class");
            reflectionStringMap.put('/' + entry.getKey() + ".class", '/' + entry.getValue() + ".class");
        }
        Map<String, String> memberNames = new LinkedHashMap<>();
        Set<String> conflicts = new HashSet<>();
        for (Map.Entry<MemberKey, String> entry : memberMap.entrySet()) {
            String oldName = entry.getKey().name();
            String newName = entry.getValue();
            String previous = memberNames.putIfAbsent(oldName, newName);
            if (previous != null && !previous.equals(newName)) {
                conflicts.add(oldName);
            }
        }
        for (Map.Entry<String, String> entry : memberNames.entrySet()) {
            if (!conflicts.contains(entry.getKey())) {
                reflectionStringMap.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private void rewriteReflectionStrings(ClassNode clazz, Map<String, String> reflectionStringMap) {
        if (reflectionStringMap.isEmpty()) return;
        for (MethodNode method : clazz.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text) {
                    String mapped = reflectionStringMap.get(text);
                    if (mapped != null) {
                        ldc.cst = mapped;
                    }
                }
            }
        }
    }

    private void rewriteClassResourceStrings(String originalOwner, String remappedOwner, ClassNode clazz) {
        String originalPackage = packageName(originalOwner);
        String remappedPackage = packageName(remappedOwner);
        if (Objects.equals(originalPackage, remappedPackage)) return;
        for (MethodNode method : clazz.methods) {
            if (method.instructions == null) continue;
            LdcInsnNode pendingString = null;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text) {
                    pendingString = text.startsWith("/") ? null : ldc;
                    continue;
                }
                if (insn instanceof MethodInsnNode mi
                        && pendingString != null
                        && "java/lang/Class".equals(mi.owner)
                        && "getResourceAsStream".equals(mi.name)
                        && "(Ljava/lang/String;)Ljava/io/InputStream;".equals(mi.desc)) {
                    pendingString.cst = originalPackage.isEmpty()
                        ? "/" + pendingString.cst
                        : "/" + originalPackage + "/" + pendingString.cst;
                    pendingString = null;
                } else if (insn.getOpcode() >= 0) {
                    pendingString = null;
                }
            }
        }
    }

    private String packageName(String internalName) {
        int slash = internalName == null ? -1 : internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    private boolean excludedByRules(PipelineContext pctx, String internalName) {
        for (ClassRule rule : pctx.config().rules()) {
            if (!matchesClass(rule.match(), internalName)) continue;
            if (rule.exclude()) return true;
            TransformConfig renamerConfig = rule.transforms().get(id());
            if (renamerConfig != null && !renamerConfig.enabled()) return true;
        }
        return false;
    }

    private boolean matchesClass(String pattern, String internalName) {
        if (pattern == null || pattern.isBlank()) return false;
        String regex = pattern.replace(".", "\\.")
            .replace("**", "\u0000")
            .replace("*", "[^.]*")
            .replace("\u0000", ".*");
        return internalName.replace('/', '.').matches("^" + regex + "$");
    }

    private boolean hasAnnotation(List<AnnotationNode> annotations, String desc) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (desc.equals(annotation.desc)) return true;
        }
        return false;
    }

    private boolean booleanOption(TransformConfig config, String key, boolean defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    private String stringOption(TransformConfig config, String key, String defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof String text ? text : defaultValue;
    }

    private void copyInto(ClassNode target, ClassNode source) {
        target.version = source.version;
        target.access = source.access;
        target.name = source.name;
        target.signature = source.signature;
        target.superName = source.superName;
        target.interfaces = source.interfaces;
        target.sourceFile = source.sourceFile;
        target.sourceDebug = source.sourceDebug;
        target.module = source.module;
        target.outerClass = source.outerClass;
        target.outerMethod = source.outerMethod;
        target.outerMethodDesc = source.outerMethodDesc;
        target.visibleAnnotations = source.visibleAnnotations;
        target.invisibleAnnotations = source.invisibleAnnotations;
        target.visibleTypeAnnotations = source.visibleTypeAnnotations;
        target.invisibleTypeAnnotations = source.invisibleTypeAnnotations;
        target.attrs = source.attrs;
        target.innerClasses = source.innerClasses;
        target.nestHostClass = source.nestHostClass;
        target.nestMembers = source.nestMembers;
        target.permittedSubclasses = source.permittedSubclasses;
        target.recordComponents = source.recordComponents;
        target.fields = source.fields;
        target.methods = source.methods;
    }

    private static final class NekoRemapper extends Remapper {
        private final Map<String, String> classMap;
        private final Map<MemberKey, String> memberMap;

        private NekoRemapper(Map<String, String> classMap, Map<MemberKey, String> memberMap) {
            this.classMap = classMap;
            this.memberMap = memberMap;
        }

        @Override
        public String map(String internalName) {
            return classMap.getOrDefault(internalName, internalName);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            return memberMap.getOrDefault(MemberKey.method(owner, name, descriptor), name);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            return memberMap.getOrDefault(MemberKey.field(owner, name, descriptor), name);
        }
    }

    private record MemberKey(String owner, String name, String desc, boolean method) {
        static MemberKey method(String owner, String name, String desc) {
            return new MemberKey(owner, name, desc, true);
        }

        static MemberKey field(String owner, String name, String desc) {
            return new MemberKey(owner, name, desc, false);
        }
    }

    private static final class NameSource {
        private final String prefix;
        private int index;

        private NameSource(String prefix) {
            this.prefix = prefix == null ? "" : prefix;
        }

        String nextInternalName() {
            return prefix + nextSimpleName();
        }

        String nextSimpleName() {
            int value = index++;
            StringBuilder name = new StringBuilder();
            do {
                name.append((char) ('a' + (value % 26)));
                value = value / 26 - 1;
            } while (value >= 0);
            return name.toString();
        }
    }
}

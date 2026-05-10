package dev.nekoobfuscator.transforms.jvm;

import dev.nekoobfuscator.api.transform.IRLevel;
import dev.nekoobfuscator.api.transform.TransformContext;
import dev.nekoobfuscator.api.transform.TransformPass;
import dev.nekoobfuscator.api.transform.TransformPhase;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.util.TransformGuards;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic ZKM-style application class/member renaming.
 */
public final class JvmRenamerPass implements TransformPass {
    public static final String ID = "renamer";
    private static final String PREPARED = "renamer.prepared";
    private static final String CLASS_MAP = "renamer.classMap";
    private static final String MEMBER_MAP = "renamer.memberMap";
    private static final String MAP_LINES = "renamer.mapLines";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "JVM Renamer";
    }

    @Override
    public TransformPhase phase() {
        return TransformPhase.PRE_TRANSFORM;
    }

    @Override
    public IRLevel requiredLevel() {
        return IRLevel.L1;
    }

    @Override
    public void transformClass(TransformContext ctx) {
        if (!(ctx instanceof PipelineContext pctx)) return;
        if (Boolean.TRUE.equals(ctx.getPassData(PREPARED))) return;
        renameAll(pctx);
        ctx.putPassData(PREPARED, Boolean.TRUE);
    }

    @Override
    public void transformMethod(TransformContext ctx) {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> classMap(TransformContext ctx) {
        Map<String, String> map = ctx.getPassData(CLASS_MAP);
        if (map == null) {
            map = new LinkedHashMap<>();
            ctx.putPassData(CLASS_MAP, map);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<MemberKey, String> memberMap(TransformContext ctx) {
        Map<MemberKey, String> map = ctx.getPassData(MEMBER_MAP);
        if (map == null) {
            map = new LinkedHashMap<>();
            ctx.putPassData(MEMBER_MAP, map);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<String> mapLines(TransformContext ctx) {
        List<String> lines = ctx.getPassData(MAP_LINES);
        if (lines == null) {
            lines = new ArrayList<>();
            ctx.putPassData(MAP_LINES, lines);
        }
        return lines;
    }

    private void renameAll(PipelineContext pctx) {
        List<L1Class> classes = pctx.classMap().values().stream()
            .filter(this::canRenameClass)
            .sorted(Comparator.comparing(L1Class::name))
            .toList();
        if (classes.isEmpty()) return;

        Map<String, String> classesByOldName = buildClassMap(pctx, classes);
        Map<MemberKey, String> membersByOldKey = buildMemberMap(pctx, classes);
        Map<String, Map<String, String>> methodNameMap = methodNameMap(membersByOldKey);
        Map<String, Map<String, String>> fieldNameMap = fieldNameMap(membersByOldKey);

        Renamer remapper = new Renamer(pctx.classMap(), classesByOldName, membersByOldKey);
        var coverage = dev.nekoobfuscator.transforms.util.JvmObfuscationCoverage.get(pctx);
        Map<String, L1Class> renamedClassEntries = new LinkedHashMap<>();
        for (L1Class clazz : classes) {
            rewriteReflectiveStrings(clazz.asmNode(), classesByOldName, methodNameMap, fieldNameMap);
            ClassNode remapped = new ClassNode();
            clazz.asmNode().accept(new ClassRemapper(remapped, remapper));
            stripDebugMetadata(remapped);
            copyInto(clazz.asmNode(), remapped);
            clazz.markDirty();
            renamedClassEntries.put(clazz.name(), clazz);
            for (L1Method method : clazz.methods()) {
                if (method.hasCode()) {
                    coverage.full(ID, clazz.name(), method.name(), method.descriptor(), "renamed-symbols");
                }
            }
        }

        for (L1Class clazz : classes) {
            pctx.classMap().remove(classNameBeforeRename(clazz, classesByOldName));
        }
        pctx.classMap().putAll(renamedClassEntries);
        classMap(pctx).putAll(classesByOldName);
        memberMap(pctx).putAll(membersByOldKey);
        writeMapLines(pctx, classesByOldName, membersByOldKey);
    }

    private boolean canRenameClass(L1Class clazz) {
        if (TransformGuards.isRuntimeClass(clazz)) return false;
        String name = clazz.name();
        return !"module-info".equals(name) && !name.endsWith("/module-info");
    }

    private Map<String, String> buildClassMap(PipelineContext pctx, List<L1Class> classes) {
        String prefix = stringOption(pctx, "packagePrefix", "a/");
        Set<String> occupied = new HashSet<>(pctx.classMap().keySet());
        NameSource names = new NameSource(prefix);
        Map<String, String> out = new LinkedHashMap<>();
        for (L1Class clazz : classes) {
            String newName;
            do {
                newName = names.nextInternalName();
            } while (occupied.contains(newName));
            out.put(clazz.name(), newName);
            occupied.add(newName);
        }
        return out;
    }

    private Map<MemberKey, String> buildMemberMap(PipelineContext pctx, List<L1Class> classes) {
        Map<MemberKey, String> out = new LinkedHashMap<>();
        Map<String, NameNode> methodNodes = new LinkedHashMap<>();
        DisjointSet<NameNode> methodGroups = new DisjointSet<>();

        for (L1Class clazz : classes) {
            for (MethodNode method : clazz.asmNode().methods) {
                if (!canRenameMethod(pctx, clazz, method)) continue;
                NameNode node = methodNode(methodNodes, clazz.name(), method.name);
                methodGroups.add(node);
            }
        }
        for (L1Class clazz : classes) {
            for (MethodNode method : clazz.asmNode().methods) {
                if (!canRenameMethod(pctx, clazz, method)) continue;
                NameNode current = methodNode(methodNodes, clazz.name(), method.name);
                unionAppAncestors(pctx, methodGroups, methodNodes, clazz, method);
                if (overridesExternalMethod(pctx, clazz, method, method.desc)) {
                    methodGroups.markFixed(current, method.name);
                }
            }
        }
        for (L1Class clazz : classes) {
            unionImplementedInterfaceMethods(pctx, methodGroups, methodNodes, clazz);
        }

        Map<NameNode, Set<String>> ownersByGroup = new LinkedHashMap<>();
        for (NameNode node : methodNodes.values()) {
            ownersByGroup.computeIfAbsent(methodGroups.find(node), ignored -> new LinkedHashSet<>()).add(node.owner());
        }
        Map<NameNode, String> methodGroupNames = assignGroupNames(ownersByGroup, methodGroups, pctx.classMap(), true);
        for (L1Class clazz : classes) {
            for (MethodNode method : clazz.asmNode().methods) {
                if (!canRenameMethod(pctx, clazz, method)) continue;
                NameNode node = methodGroups.find(methodNode(methodNodes, clazz.name(), method.name));
                String newName = methodGroupNames.get(node);
                if (newName != null && !newName.equals(method.name)) {
                    out.put(MemberKey.method(clazz.name(), method.name, method.desc), newName);
                }
            }
        }

        for (L1Class clazz : classes) {
            Set<String> occupied = new HashSet<>();
            for (FieldNode field : clazz.asmNode().fields) {
                if (!canRenameField(field)) {
                    occupied.add(field.name);
                }
            }
            NameSource fieldNames = new NameSource("");
            List<FieldNode> fields = new ArrayList<>(clazz.asmNode().fields);
            fields.sort(Comparator.comparing((FieldNode f) -> f.name).thenComparing(f -> f.desc));
            for (FieldNode field : fields) {
                if (!canRenameField(field)) continue;
                String newName;
                do {
                    newName = fieldNames.nextSimpleName();
                } while (!occupied.add(newName));
                if (!newName.equals(field.name)) {
                    out.put(MemberKey.field(clazz.name(), field.name, field.desc), newName);
                }
            }
        }
        return out;
    }

    private NameNode methodNode(Map<String, NameNode> nodes, String owner, String name) {
        return nodes.computeIfAbsent(owner + "." + name, ignored -> new NameNode(owner, name));
    }

    private void unionAppAncestors(
        PipelineContext pctx,
        DisjointSet<NameNode> groups,
        Map<String, NameNode> nodes,
        L1Class clazz,
        MethodNode method
    ) {
        NameNode current = methodNode(nodes, clazz.name(), method.name);
        L1Class superClass = pctx.classMap().get(clazz.superName());
        if (superClass != null && declaresMethod(superClass, method.name, method.desc)) {
            groups.union(current, methodNode(nodes, superClass.name(), method.name));
        }
        for (String ifaceName : clazz.interfaces()) {
            unionAppInterface(pctx, groups, nodes, current, ifaceName, method.name, method.desc);
        }
    }

    private void unionAppInterface(
        PipelineContext pctx,
        DisjointSet<NameNode> groups,
        Map<String, NameNode> nodes,
        NameNode current,
        String ifaceName,
        String name,
        String desc
    ) {
        L1Class iface = pctx.classMap().get(ifaceName);
        if (iface == null) return;
        if (declaresMethod(iface, name, desc)) {
            groups.union(current, methodNode(nodes, iface.name(), name));
        }
        for (String parent : iface.interfaces()) {
            unionAppInterface(pctx, groups, nodes, current, parent, name, desc);
        }
    }

    private void unionImplementedInterfaceMethods(
        PipelineContext pctx,
        DisjointSet<NameNode> groups,
        Map<String, NameNode> nodes,
        L1Class clazz
    ) {
        for (String ifaceName : clazz.interfaces()) {
            unionImplementedInterfaceMethods(pctx, groups, nodes, clazz, ifaceName);
        }
    }

    private void unionImplementedInterfaceMethods(
        PipelineContext pctx,
        DisjointSet<NameNode> groups,
        Map<String, NameNode> nodes,
        L1Class clazz,
        String ifaceName
    ) {
        L1Class iface = pctx.classMap().get(ifaceName);
        if (iface == null) return;
        for (MethodNode ifaceMethod : iface.asmNode().methods) {
            if (!canRenameMethod(pctx, iface, ifaceMethod)) continue;
            L1Class implementation = findImplementation(pctx, clazz, ifaceMethod.name, ifaceMethod.desc);
            if (implementation != null) {
                groups.union(
                    methodNode(nodes, implementation.name(), ifaceMethod.name),
                    methodNode(nodes, iface.name(), ifaceMethod.name)
                );
            }
        }
        for (String parent : iface.interfaces()) {
            unionImplementedInterfaceMethods(pctx, groups, nodes, clazz, parent);
        }
    }

    private L1Class findImplementation(PipelineContext pctx, L1Class clazz, String name, String desc) {
        for (L1Class current = clazz; current != null; current = pctx.classMap().get(current.superName())) {
            if (current.findMethod(name, desc) != null) return current;
        }
        return null;
    }

    private boolean declaresMethod(L1Class clazz, String name, String desc) {
        return clazz.findMethod(name, desc) != null;
    }

    private Map<NameNode, String> assignGroupNames(
        Map<NameNode, Set<String>> ownersByGroup,
        DisjointSet<NameNode> groups,
        Map<String, L1Class> classMap,
        boolean methods
    ) {
        Map<String, Set<String>> occupiedByOwner = new HashMap<>();
        for (Map.Entry<String, L1Class> entry : classMap.entrySet()) {
            Set<String> occupied = new HashSet<>();
            if (methods) {
                for (MethodNode method : entry.getValue().asmNode().methods) {
                    if ("<init>".equals(method.name) || "<clinit>".equals(method.name)) {
                        occupied.add(method.name);
                    }
                }
            }
            occupiedByOwner.put(entry.getKey(), occupied);
        }
        Map<NameNode, String> out = new LinkedHashMap<>();
        Map<String, String> preferredByOriginalName = new LinkedHashMap<>();
        NameSource names = new NameSource("");
        List<NameNode> roots = new ArrayList<>(ownersByGroup.keySet());
        roots.sort(Comparator.comparing(NameNode::owner).thenComparing(NameNode::name));
        for (NameNode root : roots) {
            String fixed = groups.fixedName(root);
            if (fixed != null) {
                out.put(root, fixed);
                markOccupied(occupiedByOwner, ownersByGroup.get(root), fixed);
                continue;
            }
            String candidate = preferredByOriginalName.get(root.name());
            if (candidate == null || isOccupied(occupiedByOwner, ownersByGroup.get(root), candidate)) {
                do {
                    candidate = names.nextSimpleName();
                } while (isOccupied(occupiedByOwner, ownersByGroup.get(root), candidate));
                preferredByOriginalName.putIfAbsent(root.name(), candidate);
            }
            out.put(root, candidate);
            markOccupied(occupiedByOwner, ownersByGroup.get(root), candidate);
        }
        return out;
    }

    private boolean isOccupied(Map<String, Set<String>> occupiedByOwner, Set<String> owners, String name) {
        for (String owner : owners) {
            Set<String> occupied = occupiedByOwner.get(owner);
            if (occupied != null && occupied.contains(name)) return true;
        }
        return false;
    }

    private void markOccupied(Map<String, Set<String>> occupiedByOwner, Set<String> owners, String name) {
        for (String owner : owners) {
            occupiedByOwner.computeIfAbsent(owner, ignored -> new HashSet<>()).add(name);
        }
    }

    private boolean canRenameMethod(PipelineContext pctx, L1Class clazz, MethodNode method) {
        if ("<init>".equals(method.name) || "<clinit>".equals(method.name)) return false;
        if ((method.access & Opcodes.ACC_NATIVE) != 0) return false;
        if ("main".equals(method.name)
            && "([Ljava/lang/String;)V".equals(method.desc)
            && (method.access & Opcodes.ACC_STATIC) != 0) {
            return false;
        }
        return !overridesExternalMethod(pctx, clazz, method, method.desc);
    }

    private boolean canRenameField(FieldNode field) {
        return true;
    }

    private Map<String, Map<String, String>> methodNameMap(Map<MemberKey, String> members) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<MemberKey, String> entry : members.entrySet()) {
            MemberKey key = entry.getKey();
            if (!key.method()) continue;
            out.computeIfAbsent(key.owner(), ignored -> new LinkedHashMap<>())
                .putIfAbsent(key.name(), entry.getValue());
        }
        return out;
    }

    private Map<String, Map<String, String>> fieldNameMap(Map<MemberKey, String> members) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<MemberKey, String> entry : members.entrySet()) {
            MemberKey key = entry.getKey();
            if (key.method()) continue;
            out.computeIfAbsent(key.owner(), ignored -> new LinkedHashMap<>())
                .putIfAbsent(key.name(), entry.getValue());
        }
        return out;
    }

    private void rewriteReflectiveStrings(
        ClassNode node,
        Map<String, String> classes,
        Map<String, Map<String, String>> methodNames,
        Map<String, Map<String, String>> fieldNames
    ) {
        Map<String, String> classStrings = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : classes.entrySet()) {
            classStrings.put(entry.getKey(), entry.getValue());
            classStrings.put(entry.getKey().replace('/', '.'), entry.getValue().replace('/', '.'));
            classStrings.put(entry.getKey() + ".class", entry.getValue() + ".class");
            classStrings.put("/" + entry.getKey() + ".class", "/" + entry.getValue() + ".class");
        }
        List<Map.Entry<String, String>> packageStrings = packageMap(classes);
        for (MethodNode method : node.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                    String mapped = classStrings.get(value);
                    if (mapped == null) mapped = remapResourcePath(value, packageStrings);
                    if (mapped != null) {
                        ldc.cst = mapped;
                    }
                }
                if (!(insn instanceof MethodInsnNode call)) continue;
                if (isMethodReflectionLookup(call)) {
                    rewritePreviousNameLiteral(call, methodNames);
                } else if (isFieldReflectionLookup(call)) {
                    rewritePreviousNameLiteral(call, fieldNames);
                }
            }
        }
    }

    private List<Map.Entry<String, String>> packageMap(Map<String, String> classes) {
        Map<String, String> packages = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : classes.entrySet()) {
            String oldPackage = packageName(entry.getKey());
            String newPackage = packageName(entry.getValue());
            if (!oldPackage.isEmpty() && !newPackage.isEmpty()) {
                packages.putIfAbsent(oldPackage, newPackage);
            }
        }
        List<Map.Entry<String, String>> out = new ArrayList<>(packages.entrySet());
        out.sort((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()));
        return out;
    }

    private String remapResourcePath(String value, List<Map.Entry<String, String>> packages) {
        for (Map.Entry<String, String> entry : packages) {
            String oldPackage = entry.getKey();
            String newPackage = entry.getValue();
            if (value.startsWith(oldPackage + "/")) {
                return newPackage + value.substring(oldPackage.length());
            }
            if (value.startsWith("/" + oldPackage + "/")) {
                return "/" + newPackage + value.substring(oldPackage.length() + 1);
            }
        }
        return null;
    }

    private String packageName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    private boolean isMethodReflectionLookup(MethodInsnNode call) {
        return "java/lang/Class".equals(call.owner)
            && ("getMethod".equals(call.name) || "getDeclaredMethod".equals(call.name))
            && "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(call.desc);
    }

    private boolean isFieldReflectionLookup(MethodInsnNode call) {
        return "java/lang/Class".equals(call.owner)
            && ("getField".equals(call.name) || "getDeclaredField".equals(call.name))
            && "(Ljava/lang/String;)Ljava/lang/reflect/Field;".equals(call.desc);
    }

    private void rewritePreviousNameLiteral(MethodInsnNode call, Map<String, Map<String, String>> namesByOwner) {
        LdcInsnNode nameInsn = null;
        String owner = null;
        for (AbstractInsnNode scan = call.getPrevious(); scan != null; scan = scan.getPrevious()) {
            if (scan instanceof MethodInsnNode) break;
            if (scan instanceof LdcInsnNode ldc) {
                if (nameInsn == null && ldc.cst instanceof String) {
                    nameInsn = ldc;
                    continue;
                }
                if (ldc.cst instanceof Type type && type.getSort() == Type.OBJECT) {
                    owner = type.getInternalName();
                    break;
                }
            }
        }
        if (nameInsn == null || !(nameInsn.cst instanceof String oldName)) return;
        String mapped = null;
        if (owner != null) {
            Map<String, String> ownerNames = namesByOwner.get(owner);
            if (ownerNames != null) mapped = ownerNames.get(oldName);
        }
        if (mapped == null) mapped = uniqueGlobalMemberName(namesByOwner, oldName);
        if (mapped != null) nameInsn.cst = mapped;
    }

    private String uniqueGlobalMemberName(Map<String, Map<String, String>> namesByOwner, String oldName) {
        String mapped = null;
        for (Map<String, String> names : namesByOwner.values()) {
            String candidate = names.get(oldName);
            if (candidate == null) continue;
            if (mapped != null && !mapped.equals(candidate)) return null;
            mapped = candidate;
        }
        return mapped;
    }

    private boolean overridesExternalMethod(PipelineContext pctx, L1Class clazz, MethodNode mn, String desc) {
        return overridesExternalIn(pctx, clazz.superName(), mn.name, desc)
            || overridesExternalInInterfaces(pctx, clazz.interfaces(), mn.name, desc);
    }

    private boolean overridesExternalIn(PipelineContext pctx, String owner, String name, String desc) {
        if (owner == null) return false;
        L1Class appClass = pctx.classMap().get(owner);
        if (appClass != null) {
            if (appClass.findMethod(name, desc) != null) return false;
            return overridesExternalIn(pctx, appClass.superName(), name, desc)
                || overridesExternalInInterfaces(pctx, appClass.interfaces(), name, desc);
        }
        return externalClassDeclares(owner, name, desc);
    }

    private boolean overridesExternalInInterfaces(PipelineContext pctx, List<String> interfaces, String name, String desc) {
        for (String iface : interfaces) {
            L1Class appInterface = pctx.classMap().get(iface);
            if (appInterface != null) {
                if (appInterface.findMethod(name, desc) != null) return false;
                if (overridesExternalInInterfaces(pctx, appInterface.interfaces(), name, desc)) return true;
                continue;
            }
            if (externalClassDeclares(iface, name, desc)) return true;
        }
        return false;
    }

    private boolean externalClassDeclares(String owner, String name, String desc) {
        try {
            Class<?> type = Class.forName(owner.replace('/', '.'), false, JvmRenamerPass.class.getClassLoader());
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && Type.getMethodDescriptor(method).equals(desc)) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String classNameBeforeRename(L1Class clazz, Map<String, String> classMap) {
        for (Map.Entry<String, String> entry : classMap.entrySet()) {
            if (entry.getValue().equals(clazz.name())) return entry.getKey();
        }
        return clazz.name();
    }

    private String stringOption(PipelineContext pctx, String name, String defaultValue) {
        var transform = pctx.config().transforms().get(ID);
        if (transform == null) return defaultValue;
        Object value = transform.options().get(name);
        return value instanceof String text ? text : defaultValue;
    }

    private void writeMapLines(
        PipelineContext pctx,
        Map<String, String> classes,
        Map<MemberKey, String> members
    ) {
        List<String> lines = mapLines(pctx);
        for (Map.Entry<String, String> entry : classes.entrySet()) {
            lines.add("CLASS " + entry.getKey() + " -> " + entry.getValue());
        }
        for (Map.Entry<MemberKey, String> entry : members.entrySet()) {
            MemberKey key = entry.getKey();
            lines.add((key.method() ? "METHOD " : "FIELD ") +
                key.owner() + "." + key.name() + key.desc() + " -> " + entry.getValue());
        }
    }

    private void stripDebugMetadata(ClassNode node) {
        node.sourceFile = null;
        node.sourceDebug = null;
        for (MethodNode method : node.methods) {
            method.localVariables = null;
            method.visibleLocalVariableAnnotations = null;
            method.invisibleLocalVariableAnnotations = null;
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn instanceof LineNumberNode) {
                    method.instructions.remove(insn);
                }
                insn = next;
            }
        }
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

    private static final class Renamer extends Remapper {
        private final Map<String, L1Class> classes;
        private final Map<String, String> classMap;
        private final Map<MemberKey, String> memberMap;

        private Renamer(
            Map<String, L1Class> classes,
            Map<String, String> classMap,
            Map<MemberKey, String> memberMap
        ) {
            this.classes = classes;
            this.classMap = classMap;
            this.memberMap = memberMap;
        }

        @Override
        public String map(String internalName) {
            return classMap.getOrDefault(internalName, internalName);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            String direct = memberMap.get(MemberKey.method(owner, name, descriptor));
            if (direct != null) return direct;
            String inherited = resolveMethod(owner, name, descriptor, new HashSet<>());
            return inherited == null ? name : inherited;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            String direct = memberMap.get(MemberKey.field(owner, name, descriptor));
            if (direct != null) return direct;
            String inherited = resolveField(owner, name, descriptor, new HashSet<>());
            return inherited == null ? name : inherited;
        }

        @Override
        public String mapAnnotationAttributeName(String descriptor, String name) {
            Type type = Type.getType(descriptor);
            if (type.getSort() != Type.OBJECT) return name;
            String mapped = uniqueMethodName(type.getInternalName(), name);
            return mapped == null ? name : mapped;
        }

        @Override
        public String mapRecordComponentName(String owner, String name, String descriptor) {
            String mapped = memberMap.get(MemberKey.field(owner, name, descriptor));
            return mapped == null ? name : mapped;
        }

        private String resolveMethod(String owner, String name, String desc, Set<String> seen) {
            if (owner == null || !seen.add(owner)) return null;
            L1Class clazz = classes.get(owner);
            if (clazz == null) return null;
            L1Method method = clazz.findMethod(name, desc);
            if (method != null) {
                return memberMap.get(MemberKey.method(owner, name, desc));
            }
            String fromSuper = resolveMethod(clazz.superName(), name, desc, seen);
            if (fromSuper != null) return fromSuper;
            for (String iface : clazz.interfaces()) {
                String fromIface = resolveMethod(iface, name, desc, seen);
                if (fromIface != null) return fromIface;
            }
            return null;
        }

        private String resolveField(String owner, String name, String desc, Set<String> seen) {
            if (owner == null || !seen.add(owner)) return null;
            L1Class clazz = classes.get(owner);
            if (clazz == null) return null;
            if (clazz.findField(name, desc) != null) {
                return memberMap.get(MemberKey.field(owner, name, desc));
            }
            String fromSuper = resolveField(clazz.superName(), name, desc, seen);
            if (fromSuper != null) return fromSuper;
            for (String iface : clazz.interfaces()) {
                String fromIface = resolveField(iface, name, desc, seen);
                if (fromIface != null) return fromIface;
            }
            return null;
        }

        private String uniqueMethodName(String owner, String name) {
            String mapped = null;
            for (Map.Entry<MemberKey, String> entry : memberMap.entrySet()) {
                MemberKey key = entry.getKey();
                if (!key.method() || !key.owner().equals(owner) || !key.name().equals(name)) continue;
                if (mapped != null && !mapped.equals(entry.getValue())) return null;
                mapped = entry.getValue();
            }
            return mapped;
        }
    }

    private static final class DisjointSet<T> {
        private final Map<T, T> parent = new LinkedHashMap<>();
        private final Map<T, String> fixed = new LinkedHashMap<>();

        void add(T value) {
            parent.putIfAbsent(value, value);
        }

        T find(T value) {
            add(value);
            T p = parent.get(value);
            if (Objects.equals(p, value)) return value;
            T root = find(p);
            parent.put(value, root);
            return root;
        }

        void union(T a, T b) {
            T ra = find(a);
            T rb = find(b);
            if (Objects.equals(ra, rb)) return;
            parent.put(rb, ra);
            String fixedA = fixed.get(ra);
            String fixedB = fixed.remove(rb);
            if (fixedA == null && fixedB != null) {
                fixed.put(ra, fixedB);
            }
        }

        void markFixed(T value, String name) {
            fixed.putIfAbsent(find(value), name);
        }

        String fixedName(T value) {
            return fixed.get(find(value));
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

    private record NameNode(String owner, String name) {}

    private record MemberKey(String owner, String name, String desc, boolean method) {
        static MemberKey method(String owner, String name, String desc) {
            return new MemberKey(owner, name, desc, true);
        }

        static MemberKey field(String owner, String name, String desc) {
            return new MemberKey(owner, name, desc, false);
        }
    }
}

package dev.nekoobfuscator.runtime;

import java.lang.invoke.*;

/**
 * InvokeDynamic bootstrap methods for runtime decryption.
 * This class is embedded into obfuscated output JARs.
 * Compiled to Java 8 bytecode for maximum compatibility.
 */
public final class NekoBootstrap {
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, MethodHandle> KEY_DISPATCHERS =
        new java.util.concurrent.ConcurrentHashMap<Class<?>, MethodHandle>();

    private NekoBootstrap() {}

    /**
     * Bootstrap method for string decryption.
     */
    /**
     * Bootstrap for string decryption with full multi-layer key derivation.
     * Key chain: classKey -> methodKey (via nameHash + descHash) -> insnKey (via fieldIdx + salt)
     */
    public static CallSite bsmString(MethodHandles.Lookup lookup, String name,
            MethodType type, int fieldIdx, int methodNameHash, int methodDescHash,
            int insnSalt, int flowMode, int keyMode, int keyComponent) throws Throwable {
        // Layer 1: class key from class structure
        long classKey = NekoKeyDerivation.classKey(lookup.lookupClass());
        // Layer 2: method key from method identity (name + descriptor hashes)
        long methodKey = NekoKeyDerivation.mix(
            NekoKeyDerivation.mix(classKey, methodNameHash), methodDescHash);
        // Layer 3: instruction key from position + salt
        long insnKey = NekoKeyDerivation.mix(
            NekoKeyDerivation.mix(methodKey, fieldIdx), insnSalt);
        if (flowMode != 0) {
            insnKey = NekoKeyDerivation.mix(insnKey, NekoContext.flowKey());
        }
        if (keyMode != 0) {
            insnKey = dispatchClassKey(lookup, insnKey, keyComponent);
        }

        byte[] enc = NekoKeyDerivation.getEncField(lookup.lookupClass(), fieldIdx);
        String result = NekoStringDecryptor.decrypt(enc, insnKey);
        return new ConstantCallSite(MethodHandles.constant(String.class, result));
    }

    public static String decryptString(Class<?> callerClass, int fieldIdx, long key) {
        byte[] enc = NekoKeyDerivation.getEncField(callerClass, fieldIdx);
        return NekoStringDecryptor.decrypt(enc, key);
    }

    /**
     * Bootstrap method for method invocation indirection.
     * Resolves the actual target method from encrypted bootstrap args.
     */
    public static CallSite bsmInvoke(MethodHandles.Lookup lookup, String name,
            MethodType type, int siteId, int methodNameHash, int methodDescHash,
            int siteSalt, int invokeType, int targetId, int flowMode,
            int keyMode, int keyComponent) throws Throwable {
        Class<?> callerClass = lookup.lookupClass();
        long classKey = NekoKeyDerivation.classKey(callerClass);
        long methodKey = NekoKeyDerivation.mix(
            NekoKeyDerivation.mix(classKey, methodNameHash), methodDescHash);
        long siteKey = NekoKeyDerivation.mix(methodKey, siteSalt);
        siteKey = NekoKeyDerivation.mix(siteKey, targetId);
        siteKey = NekoKeyDerivation.mix(siteKey, invokeType);
        if (flowMode != 0) {
            siteKey = NekoKeyDerivation.mix(siteKey, NekoContext.flowKey());
        }
        if (keyMode != 0) {
            siteKey = dispatchClassKey(lookup, siteKey, keyComponent);
        }

        String owner = decryptInvokeMetadata(callerClass, siteId, 0, siteKey, 1);
        String methodName = decryptInvokeMetadata(callerClass, siteId, 1, siteKey, 2);
        String methodDesc = decryptInvokeMetadata(callerClass, siteId, 2, siteKey, 3);
        Class<?> ownerClass = Class.forName(owner.replace('/', '.'), true,
            callerClass.getClassLoader());
        MethodType targetType = MethodType.fromMethodDescriptorString(methodDesc, ownerClass.getClassLoader());

        // Use privateLookupIn for cross-class access
        MethodHandles.Lookup targetLookup;
        try {
            targetLookup = MethodHandles.privateLookupIn(ownerClass, lookup);
        } catch (IllegalAccessException e) {
            targetLookup = lookup;
        }

        MethodHandle mh;
        switch (invokeType) {
            case 184: // INVOKESTATIC
                mh = targetLookup.findStatic(ownerClass, methodName, targetType);
                break;
            case 182: // INVOKEVIRTUAL
            case 185: // INVOKEINTERFACE
                mh = targetLookup.findVirtual(ownerClass, methodName, targetType);
                break;
            default:
                mh = targetLookup.findVirtual(ownerClass, methodName, targetType);
                break;
        }
        if (mh.isVarargsCollector()) {
            mh = mh.asFixedArity();
        }
        return new ConstantCallSite(mh.asType(type));
    }

    /**
     * Bootstrap method for number decryption.
     */
    public static CallSite bsmNumber(MethodHandles.Lookup lookup, String name,
            MethodType type, long encValue, int contextKey) throws Throwable {
        long key = NekoKeyDerivation.classKey(lookup.lookupClass()) ^ (long) contextKey;
        long decrypted = encValue ^ key;

        Class<?> rt = type.returnType();
        Object value;
        if (rt == int.class) value = (int) decrypted;
        else if (rt == long.class) value = decrypted;
        else if (rt == float.class) value = Float.intBitsToFloat((int) decrypted);
        else if (rt == double.class) value = Double.longBitsToDouble(decrypted);
        else value = (int) decrypted;

        return new ConstantCallSite(MethodHandles.constant(rt, value));
    }

    private static String decryptInvokeMetadata(Class<?> callerClass, int siteId, int component,
            long siteKey, int componentId) {
        String fieldName = "__i" + siteId + "_" + component;
        byte[] enc = NekoKeyDerivation.getBytesField(callerClass, fieldName);
        long key = NekoKeyDerivation.finalize_(NekoKeyDerivation.mix(siteKey, componentId));
        return NekoStringDecryptor.decrypt(enc, key);
    }

    private static long dispatchClassKey(MethodHandles.Lookup lookup, long state, int component) throws Throwable {
        Class<?> callerClass = lookup.lookupClass();
        MethodHandle dispatcher = KEY_DISPATCHERS.get(callerClass);
        if (dispatcher == null) {
            dispatcher = findClassKeyDispatcher(lookup, callerClass);
            if (dispatcher != null) {
                KEY_DISPATCHERS.put(callerClass, dispatcher);
            }
        }
        if (dispatcher == null) {
            return state;
        }
        return (long) dispatcher.invokeExact(state, component);
    }

    private static MethodHandle findClassKeyDispatcher(MethodHandles.Lookup lookup, Class<?> callerClass)
            throws IllegalAccessException {
        java.lang.reflect.Method[] methods = callerClass.getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            java.lang.reflect.Method method = methods[i];
            int mods = method.getModifiers();
            Class<?>[] params = method.getParameterTypes();
            if (!java.lang.reflect.Modifier.isStatic(mods)
                    || method.getReturnType() != long.class
                    || params.length != 2
                    || params[0] != long.class
                    || params[1] != int.class
                    || !method.getName().startsWith("__neko_k")) {
                continue;
            }
            method.setAccessible(true);
            return MethodHandles.lookup().unreflect(method).asType(
                MethodType.methodType(long.class, long.class, int.class));
        }
        return null;
    }

}

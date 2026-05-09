package dev.nekoobfuscator.runtime;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Runtime key derivation functions. Mirrors compile-time derivation exactly.
 * The MASTER_SEED is split into four parts that the obfuscator patches with
 * random values whose combination yields the chosen build seed; no single
 * LDC literal exposes the seed.
 */
public final class NekoKeyDerivation {

    // Four 64-bit slots patched independently at obfuscation time. The patcher
    // picks (A, B, C) at random and computes D so that the combination below
    // yields the chosen build seed. None of these literals equal the seed.
    private static long MASTER_SEED_A = 0x4E454B4F0001A001L;
    private static long MASTER_SEED_B = 0x4F424653424B4D4FL;
    private static long MASTER_SEED_C = 0x4D6173746572CFE3L;
    private static long MASTER_SEED_D = 0x5365656431374D69L;
    private static long MASTER_SEED = combineSeed(
        MASTER_SEED_A, MASTER_SEED_B, MASTER_SEED_C, MASTER_SEED_D);

    private static long combineSeed(long a, long b, long c, long d) {
        return a ^ Long.rotateLeft(b, 13) ^ Long.rotateRight(c, 7) ^ d;
    }

    // ThreadLocal Cipher cache for the AES number path. Without this, every
    // decryptNumberAes call walks the JCE provider chain and reinitializes a
    // fresh Cipher instance, which dominates <clinit> startup latency in jars
    // with many AES-encrypted numeric constants. Uses ThreadLocal.withInitial
    // lambda (LambdaMetafactory) so no anonymous inner class is generated —
    // the pipeline injects this class as a single resource.
    private static final ThreadLocal<Cipher> AES_CIPHER = ThreadLocal.withInitial(() -> {
        try {
            return Cipher.getInstance("AES/ECB/NoPadding");
        } catch (Exception e) {
            throw new RuntimeException("AES not available", e);
        }
    });

    private NekoKeyDerivation() {}

    public static long classKey(Class<?> clazz) {
        long h = MASTER_SEED;
        h = mix(h, clazz.getName().replace('.', '/').hashCode());
        // Use internal name format (slashes) to match compile-time derivation
        Class<?> sup = clazz.getSuperclass();
        h = mix(h, sup != null ? sup.getName().replace('.', '/').hashCode() : 0);
        for (Class<?> iface : clazz.getInterfaces()) {
            h = mix(h, iface.getName().replace('.', '/').hashCode());
        }
        return finalize_(h);
    }

    public static long methodKey(long classKey, int methodContext) {
        return mix(classKey, methodContext);
    }

    public static long mix(long state, long input) {
        state ^= input;
        state *= 0x9E3779B97F4A7C15L;
        state = Long.rotateLeft(state, 31);
        state *= 0xBF58476D1CE4E5B9L;
        return state;
    }

    public static long finalize_(long h) {
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }

    public static byte[] longToBytes(long value) {
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return bytes;
    }

    public static byte[] getEncField(Class<?> clazz, int fieldIdx) {
        return getBytesField(clazz, "__e" + fieldIdx);
    }

    public static byte[] getBytesField(Class<?> clazz, String fieldName) {
        try {
            Field f = clazz.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object value = f.get(null);
            if (value instanceof byte[] bytes) {
                return bytes;
            }
            if (value instanceof String text) {
                return text.getBytes(StandardCharsets.ISO_8859_1);
            }
            throw new IllegalStateException("Unsupported encrypted field type: " + f.getType().getName());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static long decryptNumberAes(long c0, long c1, long key, int bits) {
        try {
            Cipher cipher = AES_CIPHER.get();
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(expandNumberAesKey(key), "AES"));
            byte[] plain = cipher.doFinal(ByteBuffer.allocate(16).putLong(c0).putLong(c1).array());
            long value = ByteBuffer.wrap(plain).getLong();
            return bits == 32 ? (value & 0xFFFFFFFFL) : value;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] expandNumberAesKey(long key) {
        ByteBuffer bytes = ByteBuffer.allocate(16);
        bytes.putLong(key);
        bytes.putLong(finalize_(mix(key, 0xC2FBA1B5E84F7233L)));
        return bytes.array();
    }
}

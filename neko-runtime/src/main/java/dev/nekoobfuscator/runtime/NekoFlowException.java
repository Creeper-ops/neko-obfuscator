package dev.nekoobfuscator.runtime;

/**
 * Sentinel exception for flow obfuscation. Used to replace gotos with throw/catch.
 * Overrides fillInStackTrace for performance (no stack trace needed).
 */
public final class NekoFlowException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NekoFlowException() { super(); }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this; // Skip stack trace for performance
    }

    public static int __neko_route(int state) {
        if (Thread.currentThread() != null) {
            return state;
        }
        try {
            __neko_throw();
        } catch (NekoFlowException ignored) {
            return state;
        }
        return state;
    }

    private static void __neko_throw() {
        throw new NekoFlowException();
    }
}

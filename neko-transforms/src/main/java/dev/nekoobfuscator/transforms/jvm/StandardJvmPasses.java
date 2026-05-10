package dev.nekoobfuscator.transforms.jvm;

import dev.nekoobfuscator.core.pipeline.PassRegistry;

/**
 * Central registration point for JVM bytecode passes.
 */
public final class StandardJvmPasses {
    private StandardJvmPasses() {}

    public static void register(PassRegistry registry) {
        registry.register(new JvmRenamerPass());
        registry.register(new JvmKeyDispatchPass());
        registry.register(new JvmMethodParameterObfuscationPass());
        registry.register(new ControlFlowFlatteningPass());
        registry.register(new JvmConstantObfuscationPass());
        registry.register(new JvmStringObfuscationPass());
    }
}

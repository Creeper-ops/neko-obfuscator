package dev.nekoobfuscator.transforms.jvm;

import dev.nekoobfuscator.core.pipeline.PassRegistry;
import dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningPass;
import dev.nekoobfuscator.transforms.jvm.constants.JvmConstantObfuscationPass;
import dev.nekoobfuscator.transforms.jvm.invoke.JvmInvokeDynamicObfuscationPass;
import dev.nekoobfuscator.transforms.jvm.key.JvmKeyDispatchPass;
import dev.nekoobfuscator.transforms.jvm.parameters.JvmMethodParameterObfuscationPass;
import dev.nekoobfuscator.transforms.jvm.strings.JvmStringObfuscationPass;
import dev.nekoobfuscator.transforms.jvm.renamer.JvmRenamerPass;

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
        registry.register(new JvmInvokeDynamicObfuscationPass());
        registry.register(new JvmConstantObfuscationPass());
        registry.register(new JvmStringObfuscationPass());
    }
}

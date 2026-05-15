package dev.nekoobfuscator.transforms.jvm.cff;

import dev.nekoobfuscator.api.transform.TransformPass;

/**
 * Public registration boundary for the control-flow flattening transform.
 *
 * <p>The implementation is intentionally split into package-private CFF units so
 * this pass entrypoint stays small and stable for StandardJvmPasses.</p>
 */
public final class ControlFlowFlatteningPass extends CffTransformEngine implements TransformPass {
}

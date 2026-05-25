<div align="center">

# NekoObfuscator

Advanced Java bytecode and native obfuscation toolkit.

[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](./LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-red.svg)](https://adoptium.net/)
[![Build: Gradle](https://img.shields.io/badge/build-Gradle-02303A.svg)](./gradle)

[Chinese version](./README.zh-CN.md)

</div>

## Status

NekoObfuscator is early-stage research software. Internal APIs, configuration keys, bytecode shapes, and native runtime assumptions can change without compatibility guarantees. Do not treat the current output as production-ready DRM or as a security boundary.

The current CLI registers the rebuilt JVM obfuscation pipeline:

- `renamer`: ZKM-style class/member renaming with mapping output and reflection/resource string rewriting.
- `keyDispatch`: in-place hidden long-key dispatch for application methods.
- `methodParameterObfuscation`: replaces eligible method parameter lists with one `Object[]` carrier after key dispatch.
- `controlFlowFlattening`: direct keyed island dispatchers over the original method body.
- `runtimeVariableObfuscation`: CFF-live keyed shadow storage for protected primitive locals without a runtime reference warehouse.
- `invokeDynamic`: CFF-state keyed invokedynamic indirection for method and field references.
- `constantObfuscation`: CFF-state keyed numeric constant decoding.
- `stringObfuscation`: CFF-state keyed AES/DES plus XOR string literal decoding with per-class cipher caches.

The native pipeline is a HotSpot-oriented translation and method-entry patching system. It is not a portable JVM extension layer, and it requires fresh runtime validation for each target JDK, platform, and GC mode.

## Goals

While the recent emergence of various high-quality open-source obfuscators has greatly democratized basic bytecode protection, the industry's absolute top-tier solutions—such as ZKM and JNIC—remain strictly closed-source and carry prohibitively expensive price tags, creating an immense barrier for independent developers and standard users. 

Furthermore, traditional native obfuscators that rely heavily on standard JNI boundaries suffer from massive runtime overhead, severely crippling application performance in exchange for security.

The ultimate objective of neko-obfuscator is to disrupt this landscape by providing:
- **Ultimate JVM Hardening:** Deliver an open bytecode transformation pipeline whose structural strength, control-flow complexity, and optimization rival commercial giants like ZKM.
- **Zero-Overhead Native Metamorphism:** Reach the absolute pinnacle of native execution security. By engineering deep HotSpot overrides and bypassing traditional JNI friction, NekoObfuscator achieves full multi-platform **JDK 21+** compatibility while maintaining native execution performance that is virtually indistinguishable from raw, un-obfuscated speeds.
- **Autonomous AI Engineering:** Prove that complex, low-level software can be engineered entirely by autonomous AI agents while maintaining high availability and top-tier quality. The codebase is fully synthesized via a multi-agent harness framework powered by **Codex(GPT 5.5) & Claude Code(Opus 4.7)**.

## Features

| Area | Current behavior |
|---|---|
| JVM renaming | Renames application classes, fields, and methods, preserves JVM entry/override constraints, rewrites common reflection strings and text resources, and emits a `.map` file. |
| JVM key dispatch | Adds hidden long key material to eligible method signatures, rewrites application calls to carry the key, and seeds boundary methods locally. |
| Method parameter obfuscation | Packs eligible method parameters into a single `Object[]` argument, preserves hidden key propagation, and rewrites call sites. |
| Control-flow flattening | Splits verifier-safe basic blocks, groups dispatchers by frame shape, encodes state/domain values with per-edge evolving keys, and keeps constructors' initialization prefix intact. |
| Runtime variable obfuscation | Stores protected primitive locals in CFF-live keyed shadows with transient masks, poisons original primitive slots, and leaves references on the JVM local path without a `ThreadLocal`/`Object[]` warehouse. |
| Numeric constants | Rewrites numeric pushes, numeric `LDC`, `IINC`, and numeric `ConstantValue` fields. Runtime decodes depend on CFF live locals and the class key table. |
| String literals | Encrypts direct string `LDC`, string `ConstantValue` fields lowered into `<clinit>`, and string concat recipe constants. Runtime decoding uses AES or DES, XOR stream mixing, live CFF state, and class-local caches without helper classes. |
| Native translation | Selects annotated or glob-matched methods, closes over application callees, lowers supported invokedynamic forms, translates JVM bytecode to generated C, builds native libraries with Zig, and patches HotSpot method entries during `JNI_OnLoad`. |
| Runtime loader | Injects the minimal `NekoNativeLoader` only when native mode is enabled, then loads `/neko/native/libneko_<platform>_<arch>.<ext>`. |
| Resource encryption | Optional AES-256-GCM resource encryption in the native stage. |

## Repository Layout

| Module | Purpose |
|---|---|
| `neko-api` | Public annotations, transform interfaces, and configuration model. |
| `neko-config` | YAML parser and validator. |
| `neko-core` | JAR I/O, L1/L2/L3 IR, pass scheduling, cleanup, runtime injection, mappings. |
| `neko-transforms` | Rebuilt JVM passes: renamer, key dispatch, method parameter packing, keyed CFF, numeric constants, and string literals. |
| `neko-native` | Native selection, safety checking, bytecode lowering, C emission, Zig build, HotSpot patching. |
| `neko-runtime` | Runtime classes injected into output JARs. Currently only `NekoNativeLoader`. |
| `neko-cli` | picocli command line entry point. |
| `neko-test` | Integration and native validation tests. |

## Quick Start

Prerequisites:

- JDK 17 or newer.
- The repository Gradle wrapper.
- Zig, only when `native.enabled: true`.

Build the CLI:

```bash
./gradlew :neko-cli:shadowJar
```

Run obfuscation:

```bash
java -jar neko-cli/build/libs/neko-cli-*-all.jar obfuscate \
  --config config.yml \
  --input path/to/input.jar \
  --output path/to/output.jar
```

Inspect a JAR:

```bash
java -jar neko-cli/build/libs/neko-cli-*-all.jar info path/to/input.jar
```

Run tests:

```bash
./gradlew :neko-test:test
```

## Minimal Configuration

```yaml
input: path/to/input.jar
output: path/to/output.jar

transforms:
  renamer: { enabled: true, packagePrefix: a/ }
  keyDispatch: { enabled: true }
  methodParameterObfuscation: { enabled: true }
  controlFlowFlattening: { enabled: true, intensity: 1.0 }
  runtimeVariableObfuscation: { enabled: true }
  invokeDynamic: { enabled: true, intensity: 1.0 }
  constantObfuscation: { enabled: true, intensity: 1.0 }
  stringObfuscation: { enabled: true, intensity: 1.0 }

native:
  enabled: false

keys:
  masterSeed: auto
```

Native translation example:

```yaml
input: app.jar
output: app-native.jar

transforms:
  renamer: { enabled: true, packagePrefix: a/ }
  keyDispatch: { enabled: true }
  methodParameterObfuscation: { enabled: true }
  controlFlowFlattening: { enabled: true, intensity: 1.0 }
  runtimeVariableObfuscation: { enabled: true }
  invokeDynamic: { enabled: true, intensity: 1.0 }
  constantObfuscation: { enabled: true, intensity: 1.0 }
  stringObfuscation: { enabled: true, intensity: 1.0 }

native:
  enabled: true
  targets: [LINUX_X64]
  zigPath: zig
  methods: ["com/example/**"]
  excludePatterns: []
  includeAnnotated: true
  skipOnError: false
  resourceEncryption: true
```

For strict native validation, prefer `skipOnError: false`. A fallback build can hide missing native coverage and is not acceptable as proof that native translation works.

## Documentation

| Document | Content |
|---|---|
| [Architecture](./docs/ARCHITECTURE.md) | Current module topology, pipeline, runtime injection, and native patching model. |
| [Configuration](./docs/CONFIG.md) | YAML fields that are parsed by the current code. |
| [Whitepaper](./docs/WHITEPAPER.md) | Current CFF formulas, key dispatch logic, and native implementation model. |

Chinese documentation:

- [README.zh-CN.md](./README.zh-CN.md)
- [docs/zh-CN/ARCHITECTURE.md](./docs/zh-CN/ARCHITECTURE.md)
- [docs/zh-CN/CONFIG.md](./docs/zh-CN/CONFIG.md)
- [docs/zh-CN/WHITEPAPER.md](./docs/zh-CN/WHITEPAPER.md)

## Validation Notes

Documentation changes do not regenerate native artifacts. Any implementation change to CFF or native translation must be proven with fresh runtime artifacts and should reject verifier errors, native crashes, `translated=0`, missing libraries, JNI fallback, and original-bytecode fallback.

## License

NekoObfuscator is released under the GNU General Public License v3.0. See [LICENSE](./LICENSE).

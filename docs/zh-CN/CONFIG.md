# 配置参考

[English](../CONFIG.md)

## 当前解析的顶层字段

```yaml
input: path/to/input.jar
output: path/to/output.jar
classpath:
  - libs/dependency.jar

transforms:
  renamer: { enabled: true, packagePrefix: a/ }
  keyDispatch: { enabled: true }
  methodParameterObfuscation: { enabled: true }
  controlFlowFlattening: { enabled: true, intensity: 1.0 }
  constantObfuscation: { enabled: true, intensity: 1.0 }
  stringObfuscation: { enabled: true, intensity: 1.0 }

native:
  enabled: false

keys:
  masterSeed: auto
  layers: [CLASS, METHOD, INSTRUCTION, CONTROL_FLOW]
  mixing: SIP_HASH

rules: []
```

当前 parser 没有启用 preset resolver。旧 YAML 里可以有 `preset` 字段，但当前 `ConfigParser` 不会根据它填充默认 transform。需要的 transform 必须显式启用。

## CLI 覆盖

```bash
java -jar neko-cli/build/libs/neko-cli-*-all.jar obfuscate \
  -c config.yml \
  -i path/to/input.jar \
  -o path/to/output.jar \
  -v
```

| CLI 选项 | 作用 |
|---|---|
| `-c`, `--config` | 必填 YAML 文件。 |
| `-i`, `--input` | 覆盖 `input`。 |
| `-o`, `--output` | 覆盖 `output`。 |
| `-v`, `--verbose` | 失败时打印完整栈。 |

## 校验

`ConfigValidator` 当前检查：

- `input` 存在且文件存在。
- `output` 存在。
- 每个 classpath entry 存在。
- 每个 transform intensity 在 `[0.0, 1.0]`。
- `native.enabled: true` 时 `native.targets` 非空。

## Transform 配置

每个 transform 接受：

```yaml
transforms:
  <id>:
    enabled: true
    intensity: 1.0
    optionName: optionValue
```

也支持布尔简写：

```yaml
transforms:
  keyDispatch: true
  controlFlowFlattening: false
```

当前 CLI 注册：

| Transform ID | Phase | 作用 | 常用配置 |
|---|---|---|---|
| `renamer` | `PRE_TRANSFORM` | 重命名应用类、字段和方法；写出 mapping；重写常见反射/资源字符串。 | `{ enabled: true, packagePrefix: a/ }` |
| `keyDispatch` | `PRE_TRANSFORM` | 添加并传播隐藏方法 key。 | `{ enabled: true }` |
| `methodParameterObfuscation` | `PRE_TRANSFORM` | 在 key dispatch 后将可处理方法参数打包为一个 `Object[]` carrier。 | `{ enabled: true }` |
| `controlFlowFlattening` | `TRANSFORM` | 将可处理方法重写为 keyed island dispatcher，并发布 CFF metadata。 | `{ enabled: true, intensity: 1.0 }` |
| `constantObfuscation` | `TRANSFORM` | 使用 CFF live state 和类 key table 重写数值常量。 | `{ enabled: true, intensity: 1.0 }` |
| `stringObfuscation` | `TRANSFORM` | 使用 CFF live state、AES/DES、XOR 和类内 cache 加密字符串字面量与 concat recipe 常量。 | `{ enabled: true, intensity: 1.0 }` |

未知或未注册 transform ID 可以被解析，但除非 CLI 注册了同名 pass，否则不会生效。

### JVM Transform 选项

`renamer` 识别：

| 选项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `packagePrefix` | string | `a/` | 应用类 internal name 前缀；使用 `/` 分隔包名。 |
| `renameRuntime` | bool | `true` | native/runtime 阶段后混淆注入的 runtime API 类。 |
| `runtimeClassPrefix` | string | `r/` | `renameRuntime` 为 true 时 runtime 类 internal name 前缀。 |

任意 transform 都可以携带 `strictCoverage: true`。只要有一个 transform 设置该选项，pipeline 就会对当前硬编码的 JVM bytecode coverage 集合校验每个有代码的应用方法。在当前代码中它主要覆盖 `renamer`、`controlFlowFlattening` 和 `constantObfuscation`；key-dispatch、method-parameter 和 string-specific 保证仍应使用针对性集成测试/静态审计。

当前 JVM 依赖关系：

```text
renamer
keyDispatch
methodParameterObfuscation -> keyDispatch
controlFlowFlattening      -> keyDispatch
constantObfuscation        -> controlFlowFlattening
stringObfuscation          -> controlFlowFlattening
```

## Native 节

```yaml
native:
  enabled: false
  targets: [LINUX_X64, WINDOWS_X64]
  zigPath: zig
  resourceEncryption: true
  encryptionAlgorithm: AES_256_GCM
  methods: ["**/*"]
  excludePatterns: []
  includeAnnotated: true
  skipOnError: false
  outputPrefix: neko_impl_
  obfuscateJniSlotDispatch: false
  cacheJniIds: false
```

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `enabled` | bool | `false` | 开启 native 翻译。 |
| `targets` | list | `[LINUX_X64, WINDOWS_X64]` | native 开启时不能为空；目标名由 `NativeBuildEngine` 处理。 |
| `zigPath` | string | `zig` | Zig 可执行文件路径或命令名。 |
| `resourceEncryption` | bool | `true` | 添加 native library 后加密非 class 资源。 |
| `encryptionAlgorithm` | string | `AES_256_GCM` | 当前资源加密算法。 |
| `methods` | list | `["**/*"]` | 类或 `classInternalName#methodName` glob 选择。 |
| `excludePatterns` | list | `[]` | 从 glob 选择中排除类或方法。 |
| `includeAnnotated` | bool | `true` | 选择带 `@NativeTranslate` 的类/方法。 |
| `skipOnError` | bool | API 默认 `true` | 为 true 时 native 失败可回退到非 native 输出；严格验证使用 `false`。 |
| `outputPrefix` | string | `neko_impl_` | 兼容字段；当前 translator 生成固定 `neko_native_*` 名称。 |
| `obfuscateJniSlotDispatch` | bool | `false` | 兼容字段；当前构造器接收但不用于命名。 |
| `cacheJniIds` | bool | `false` | 兼容字段；当前主路径是直接 HotSpot 元数据。 |

旧版嵌套资源字段仍会解析：

```yaml
native:
  resources:
    encrypt: true
    algorithm: AES_256_GCM
```

优先使用顶层 `resourceEncryption` 和 `encryptionAlgorithm`。

## Native 选择

选择顺序：

1. 永远不翻译 `dev/nekoobfuscator/runtime/` 运行时包。
2. `includeAnnotated: true` 时，带 `@NativeTranslate` 的类或方法会被选中。
3. `excludePatterns` 先于 `methods` 检查。
4. 包含 `#` 的 pattern 匹配 `classInternalName#methodName`。
5. 不包含 `#` 的 pattern 匹配类 internal name。

构造器、类初始化器、abstract 方法、native 方法和 bridge 方法不会被翻译。

## Keys 节

```yaml
keys:
  masterSeed: auto
  layers: [CLASS, METHOD, INSTRUCTION, CONTROL_FLOW]
  mixing: SIP_HASH
```

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `masterSeed` | long、`auto` 或 null | `0` | `0`、`auto`、null 表示自动生成构建 seed；具体 long 用于可复现 key。 |
| `layers` | list | `[CLASS, METHOD, INSTRUCTION, CONTROL_FLOW]` | 会解析进模型；当前重构 JVM pass 使用自己基于 master seed 的方法级派生。 |
| `mixing` | string | `SIP_HASH` | 解析为 `mixingAlgorithm`；当前 JVM pass mix 是 SplitMix64 风格。 |

当前 JVM pass mix：

```text
mix(state, value):
  z = state + value + 0x9E3779B97F4A7C15
  z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9
  z = (z ^ (z >>> 27)) * 0x94D049BB133111EB
  return z ^ (z >>> 31)
```

这是混淆 key material，不是密码学原语。

## Rules 节

Rules 会解析为 `ClassRule`：

```yaml
rules:
  - match: "com.example.**"
    exclude: false
    transforms:
      controlFlowFlattening: { enabled: true, intensity: 1.0 }
```

当前 pipeline 对 rules 的支持有限；可靠主控制面是显式 transform 和 native pattern 配置。

## 最小 JVM-only 示例

```yaml
input: app.jar
output: app-obf.jar

transforms:
  renamer: { enabled: true, packagePrefix: a/ }
  keyDispatch: { enabled: true }
  methodParameterObfuscation: { enabled: true }
  controlFlowFlattening: { enabled: true, intensity: 1.0 }
  constantObfuscation: { enabled: true, intensity: 1.0 }
  stringObfuscation: { enabled: true, intensity: 1.0 }

native:
  enabled: false

keys:
  masterSeed: auto
```

## 严格 native 示例

```yaml
input: app.jar
output: app-native.jar

transforms:
  renamer: { enabled: true, packagePrefix: a/ }
  keyDispatch: { enabled: true }
  methodParameterObfuscation: { enabled: true }
  controlFlowFlattening: { enabled: true, intensity: 1.0 }
  constantObfuscation: { enabled: true, intensity: 1.0 }
  stringObfuscation: { enabled: true, intensity: 1.0 }

native:
  enabled: true
  targets: [LINUX_X64]
  zigPath: zig
  methods:
    - "com/example/**"
  excludePatterns:
    - "com/example/debug/**"
  includeAnnotated: true
  skipOnError: false
  resourceEncryption: true
  encryptionAlgorithm: AES_256_GCM

keys:
  masterSeed: auto
```

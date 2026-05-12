# TOOLPKG_MANIFEST.zh.md

> 为 Operit 分发的 `.toolpkg`、MCP server、Skill 捆绑包的插件清单格式。配套
> [`AUDIT_PLAN.md § 1.1`](./AUDIT_PLAN.md)（信任根决策）与
> [`THREAT_MODEL.md § 4.3`](./THREAT_MODEL.md)（插件市场）。英文镜像见
> [`TOOLPKG_MANIFEST.md`](./TOOLPKG_MANIFEST.md)。

## 插件随带什么

设备安装的每个插件包都在其负载旁随带两个文件：

```
plugin-bundle/
├── manifest.json          # 被签名的字节
├── manifest.sig           # 对 manifest.json 规范化形式的分离 Ed25519 签名
└── … 插件负载 …          # 脚本、资源、插件所需的任何内容
```

设备侧信任管道（`app/src/main/java/com/ai/assistance/operit/core/plugintrust/`）在
负载被加载入运行时之前读取两个文件。无清单、无验证、无安装。该检查独立于逐次能力
门控（`THREAT_MODEL.md § 4.2`）—— 签名验证回答"此次更新是否仍是我信任的发行方？"；
能力门控回答"该插件被允许做什么？"。

## `manifest.json` schema

```json
{
  "pluginId":            "io.example.weather",
  "version":             "1.2.0",
  "publisherName":       "Example Weather Co.",
  "publisherKeyPem":     "-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEA…\n-----END PUBLIC KEY-----\n",
  "declaredCapabilities": ["NETWORK", "FILE_READ", "METADATA"]
}
```

字段参考：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `pluginId` | string | 是 | 稳定标识。TOFU 存储以此为键。鼓励反向 DNS 模式但不强制。版本间不可变更；变更则被视为另一插件。|
| `version` | string | 是 | 自由格式语义版本。平台不比较或排序版本。|
| `publisherName` | string | 是 | 安装提示的显示文本。不唯一。不是信任根 —— 公钥指纹才是。|
| `publisherKeyPem` | string | 是 | Ed25519 公钥的 X.509 SubjectPublicKeyInfo（PEM 包装）。分离的 `manifest.sig` 对此公钥验证。|
| `declaredCapabilities` | string[] | 是 | 插件意图使用的能力类别，取自 [`JsCapabilityClass`](../app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsCapabilityClassifier.kt)。信息性 —— 声明不等于授权。调用时门控仍然权威。|

### 允许的能力类别名

下列是 `declaredCapabilities` 的全部合法条目。其他一切都解析失败：

`METADATA`、`FILE_READ`、`FILE_WRITE`、`SHELL`、`NETWORK`、`SYSTEM_READ`、`SYSTEM_WRITE`、
`UI_AUTOMATION`、`CHAT_READ`、`CHAT_WRITE`。

`UNCLASSIFIED` **不可**声明 —— 它是对未知工具名的运行时回退；清单中声明它则直接被拒。

## 签名所覆盖的规范化字节

签名覆盖清单的**规范化字节**，而非原始文件。规范化形式由应用代码
`PluginManifest.canonicalBytes()` 产生：

- JSON 对象，键按字母序输出：
  `declaredCapabilities`、`pluginId`、`publisherKeyPem`、`publisherName`、`version`。
- `declaredCapabilities` 数组按字母序排序。
- 令牌间无多余空白（紧凑的 `JSONObject.toString()` 输出）。
- UTF-8 编码。

同一逻辑清单的两次构建产生字节相同的规范化字节，因此签名可重现。

### CI 签名

openssl 参考流程：

```bash
# 计算规范化 JSON。最简的路径是将清单通过应用所用的同一规范化器；一次性签名
# 可用 `jq -S -c` 近似。
jq -S -c '{declaredCapabilities: (.declaredCapabilities | sort), pluginId, publisherKeyPem, publisherName, version}' manifest.json > manifest.canonical.json

# Ed25519 签名（openssl 3.0+）。
openssl pkeyutl \
    -sign \
    -inkey publisher-private.pem \
    -rawin -in manifest.canonical.json \
    -out manifest.sig

# 发布前本地验证。
openssl pkeyutl \
    -verify \
    -pubin -inkey publisher-public.pem \
    -rawin -in manifest.canonical.json \
    -sigfile manifest.sig
```

随插件分发 `manifest.json`（非规范化形式）+ `manifest.sig`。设备自行计算规范化字节。

## 首次见到即信任

对某个 `pluginId` 的首次安装，设备记录：

```
(pluginId, SHA-256(publisherKeyPem.encoded), publisherName, recordedAtMillis)
```

用户在 TOFU 提示中看到发行方名称与密钥指纹，接受或拒绝。接受时记录被写入。

对同一 `pluginId` 的后续安装：

- **发行方密钥指纹相同** → 静默安装（无需提示；前次安装的能力授权仍生效）。
- **发行方密钥指纹不同** → 拒绝安装。告知用户这看起来像另一个发行方声称同一插件 id。
  唯一允许的方法是显式遗忘现有 TOFU 记录（同时遗忘该 pluginId 上的每条能力授权）——
  这是通过 Plugin trust 设置屏的有意动作。
- **签名无效** → 无条件拒绝安装。
- **清单格式错误** → 无条件拒绝安装。

用户不能在流程中途"批准不匹配"；流程仅以带外动作的方式提供遗忘并重新 TOFU，永远不
提供一键绕过。

## 能力声明

`declaredCapabilities` 是信息性的。它告诉安装提示插件意图请求什么 —— 对用户在初次
决定是否安装时有用。它**不是**授权。

运行时，插件发起的每次工具调用由 `JsCapabilityClassifier.classify()` 按工具名分类；
门控（`JsPluginGate.shouldAllow()`）随后检查用户是否已对该 (pluginId × capability)
对授权。首次调用触发逐次确认浮层；用户授权或拒绝，决策持久化。

声明多于实际使用的插件无害。使用多于声明的插件与他人无别地遭遇门控 —— 声明不是免检牌。

## MCP server

MCP server 不携带形态相同的清单；它们以 `npm` / `uvx` 可运行物包装，由各自包生态管理。
信任形态相似但由不同信号构建 —— 见 [`AUDIT_PLAN.md § 1.2`](./AUDIT_PLAN.md) 中
按包白名单 + 发行方指纹钉定，镜像本清单对 `.toolpkg` 捆绑包所做的事。

## 格式变更时

对此清单格式的任何会破坏现有签名的变更需在清单 schema 自身上做版本升级（不是个别
插件）。Android 侧需要在过渡窗口内同时接受新旧两种形态 —— 至少两个发布的应用版本 ——
然后才能拒绝旧形态。按 `AGENTS.md`，项目不维护永久兼容的解析器，但也不静默破坏已经
安装的插件。

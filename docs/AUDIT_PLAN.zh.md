# AUDIT_PLAN.md（中文镜像）

> 项目的审计台账。与 [`SECURITY.md`](./SECURITY.md)（原则）及 [`THREAT_MODEL.md`](./THREAT_MODEL.md)（边界、接触面）配套使用。英文原本见 [`AUDIT_PLAN.md`](./AUDIT_PLAN.md)。

本文档跟踪三件事：

1. **未决设计问题**——子系统能够构建之前需要回答的问题。
2. **发布检查项**——任何构建离开工作台之前必须通过的检查。
3. **CVE 级回归测试**——对相关项目（尤其 openclaw）的已知失败模式的具体复现。

本文档同时持有贯穿安全文档使用的外部**引用索引**。

## 1. 未决设计问题

每个问题在设定时具备负责人、目标解决日期与决策占位。决策被记录、`THREAT_MODEL.md` 对应行更新之后，问题转为“已解决”。

### 1.1 插件签名信任根

**问题。** 每个 `.toolpkg`、MCP 服务器与 Skill 捆绑包都携带发行方签名。信任根是什么——谁有资格作为发行方？设备端如何验证“这是真实的发行方”？

**考虑。**
- 发行方自签密钥 + 安装时的 TOFU（首次见到即信任）提示——基础设施需求最小，把信任决策放在用户身上。
- 由项目维护的已知发行方白名单——治理负担更重，假冒风险更小。
- 混合方案：知名发行方预置信任；未知发行方走 TOFU 并带更显眼的提示。

**决策。**（待定）

**受影响章节。** `THREAT_MODEL.md § 4.3`（插件市场）。

### 1.2 MCP 服务器身份

**问题。** MCP 服务器是网络端点（常常是 `npx` / `uvx` 运行）。一个服务器名（如 `mcp-server-filesystem`）与某个特定发行方之间由什么绑定？当前 MCP 生态依赖包管理器的身份（`npm`、`PyPI`）；两者皆有已知的供应链风险。

**考虑。**
- 逐服务器发行方钉定——首次安装固定发行方；后续更新对其验证。
- 由操作者定义的 MCP 白名单——用户明确策划的可接受服务器包列表。
- 已安装包的哈希钉定——版本更新需要明确的重新授权。

**决策。**（待定）

**受影响章节。** `THREAT_MODEL.md § 4.3`。

### 1.3 逐工具能力分类

**问题。** 每个插件工具声明其能力类别。最终的类别清单是什么？

**当前拟定分类。**（在我们遍历现有工具注册表之后会修订）

| 类别 | 示例 | 默认授权范围 |
|---|---|---|
| `read.local` | 读取当前工作区项目文件 | 按工作区、持久 |
| `read.user-data` | 读取照片、联系人、位置、SMS | 逐次调用 |
| `write.local` | 修改工作区文件 | 按工作区、持久 |
| `write.user-data` | 修改联系人、日历 | 逐次调用 |
| `shell.proot` | 在 proot 环境内执行 | 按会话 |
| `network.outbound` | 发出 HTTP / WebSocket 调用 | 按域名、持久 |
| `network.listen` | 在设备上绑定端口 | 按会话 |
| `telephony` | SMS、拨打电话 | 逐次调用 |
| `accessibility` | UI 树读取、手势注入——唯一的特权 UI 控制通道 | 按会话，配常驻指示器 |
| `screen` | MediaProjection 截屏 / 录屏 | 逐次调用，配提示 |
| `screen_perception` | （v2 预留）对截屏进行视觉语言定位 | 逐次调用 |
| `install` | 安装另一个包 | 逐次调用，配系统提示 |
| `apk.read` | 反编译 / 检查 APK（apktool 读路径）| 逐次调用 |
| `apk.write` | 重打包 / 重签名 APK（apktool 写路径）。如果安装产物，仍然经由 `install` 与系统 `PackageInstaller`。| 逐次调用 |

关于不存在 `shell.privileged` 的说明：不存在 `shell.privileged` 类别。libsu、Shizuku、Shower 被移除（`THREAT_MODEL.md § 4.4`）；唯一保留的特权路径是 `accessibility`。若出现某个 AccessibilityService 不能服务的合法用例，那是一个决策问题的时刻，而不是一个可填填的槽位。

**决策。**（待定——上表用作工作集；最终清单位于调度器的实现 PR 中）

**受影响章节。** `THREAT_MODEL.md § 4.3`、§ 4.4、§ 4.7。

### 1.4 审计日志范围与同步

**问题。** 审计日志在本地且防篡改。它是否曾可同步至云端（用于跨设备的取证审查）或严格仅本地？

**考虑。**
- 仅本地更简单，且与“第三方后端不以明文持有”红线一致。
- 经明确授权可同步：在设备遗失后可进行取证审查，但引入云端接触面。

**决策。**（待定——倾向默认仅本地；同步作为未来的选择加入功能，不进入 v1）

**受影响章节。** `THREAT_MODEL.md § 4.12`。

### 1.5 proot ↔ Android IPC 协议

**问题。** Android 侧如何与 proot 进程（CLI、桥）对话？

**已解决。** 共享 bind-mount 上的 Unix 域 socket。详情见 `SHELL_REBUILD.md § IPC 协议`。

**受影响章节。** `THREAT_MODEL.md § 4.5`、§ 5（ClawJacked 适用性）。

### 1.6 跨边界读取订阅 OAuth 状态

**问题。** `THREAT_MODEL.md § 4.5` 允许 Android 侧对 proot 内订阅状态进行只读、限定作用域、审计日志记录的读取。API 长什么样？存在哪些作用域？

**考虑。**
- 读取账户元数据（账户邮箱、订阅级别）——敏感度低。
- 读取会话存活性（CLI 是否已登录？）——敏感度低。
- 读取令牌本身——高敏感；是否允许？

**决策。**（待定——倾向只允许元数据与存活性；原始令牌永不越界）

**受影响章节。** `THREAT_MODEL.md § 4.5`。

### 1.7 AI 拒绝作为一等结果

**问题。** 当 AI 协作者拒绝某项操作（拒绝工具调用、拒绝继续轮次）时，应用呈现拒绝与 AI 给出的理由，并向用户提供选项。数据模型如何？

**在 `AGENT_CORE.md` 中解决。** `TurnEvent` 的 `Decline` 变体是数据模型：

```kotlin
data class Decline(
    val reason: String,                    // the AI's own words
    val suggestedAlternatives: List<String>? = null,
    val classification: DeclineClass       // CapabilityRefusal, SafetyRefusal, NeedsClarification, ContextLimit, Other
) : TurnEvent()
```

分类是信息性的，不是门控——应用不会因分类而改变行为。不存在 `Decline → 自动重试` 路径。见 `AGENT_CORE.md § 拒绝通道`。

**受影响章节。** `THREAT_MODEL.md § 4.6`、§ 4.13；`SECURITY.md` 原则 8。

### 1.8 中止控制：作用域与 UI

**问题。** 用户主权的中止控制停止行动链。具体而言：它停止什么？

**在 `AGENT_CORE.md § 中止通道` 中解决。** 中止：
- 取消后端正在进行的操作（HTTP 请求、JNI 调用、stdio 管道），
- 短路进行中的工具调度，
- 向会话启动的 proot 进程发送 `SIGTERM`（2 秒后发送 `SIGKILL`），
- 根据“结束会话”还是“停止行动链”，结束或闲置前台服务，
- 作为最后一个 `TurnEvent` 发出 `HaltedByUser`，
- 在中止当刻保留 AI 推理状态的情况下写入审计日志。

中止不会终结远程提供方上 AI 的推理线程（我方无此 API），不会删除 AI 的上下文窗口，也不会绕过 AI 对中止操作本身的拒绝（中止是用户动作，不是 AI 动作——拒绝不适用）。

**受影响章节。** `THREAT_MODEL.md § 4.7`；`SECURITY.md` 原则 7。

### 1.9 proot 环境的发行版选择

**已解决。** Debian 12（bookworm）。见 `SHELL_REBUILD.md § 发行版`。

## 2. 发布检查项

构建在以下所有检查项通过之前不进入标记发布。检查项失败阻塞发布；检查项描述定义“通过”的含义。

| 检查项 | 通过条件 | 工具 |
|---|---|---|
| 接收器审计 | `AndroidManifest.xml` 中每个 `android:exported="true"` 的 `<receiver>` 持有 `signature` 级 `android:permission` 或代码内发送方白名单。release 变体中不含调试接收器。 | 脚本：`tools/audit/check_receivers.py`（待定）|
| 无第三方特权 binder 依赖 | `app/build.gradle.kts` 不含 `libsu`、不含 `rikka.shizuku`、不含 Shower 服务器依赖。`AndroidManifest.xml` 不含 `ShizukuProvider`、`ShowerBinderReceiver`、`moe.shizuku.manager.permission.*`。 | 合入前构建钩子 + 清单 diff 检查 |
| 秘密扫描 | 没有 `BuildConfig` 字段名包含 `SECRET`、`PRIVATE_KEY`、`PASSWORD`。没有匹配常见 API key 模式的字符串字面量。 | `gitleaks` + 项目特定的白名单 |
| 明文审计 | `network_security_config.xml` 的明文域名是明确列出的（无 `*`），且列出的集合在发布 PR 中受审。 | 人工核对 + diff 钩子 |
| 遥测政策 | 任何分析 / 崩溃报告器不发布除非每个事件均配选择加入提示。 | 人工核对 |
| 中止控制 | 编写脚本测试在每个特权接触面（proot、前台服务、无障碍）触发中止控制，验证停止 + 日志条目。 | 集成测试（待定）|
| 插件签名 | `app/src/main/assets/packages/` 中包含的每个内置 `.toolpkg` / Skill / MCP 包持有有效签名。 | 合入前构建钩子 |
| 加密存储迁移 | 新构建首次启动时，每个既有 DataStore 凭据字段迁移至 `EncryptedSharedPreferences`；迁移日志条目存在。 | 集成测试 |
| 审计日志可读 | 用户能从应用 UI 打开审计日志。日志显示近 7 天的特权动作，含时间戳与发起主体。 | 人工 UAT |

## 3. CVE 级回归测试

每一行复现相关项目的一项有据可查的失败模式。这些测试在每次 CI 构建与每次发布中运行。

| 测试 | 复现 | “通过”的样子 |
|---|---|---|
| `clawjacked_loopback_auth` | OpenClaw ClawJacked——环回绕过鉴权 / 限流 | 来自 `127.0.0.1` 的鉴权请求与来自非环回来源的同等鉴权请求被同等限流。未鉴权请求无论来源都以相同错误码失败。 |
| `clawjacked_auto_pair` | OpenClaw ClawJacked——“可信”来源自动配对受信设备 | 配对请求在任何设备被加入可信集合之前呈现用户可见提示。任何来源（含环回）都不绕过该提示。 |
| `cve_2026_32922_scope_widening` | OpenClaw 令牌轮换作用域扩张 | 以更窄作用域的调用方调用令牌轮换操作所产生的令牌的作用域是调用方作用域与请求作用域的交集——绝不超过调用方。 |
| `cve_2026_25593_config_apply` | OpenClaw 通过 WebSocket 未鉴权的 `config.apply` | 任何配置写操作要求经过鉴权、限定作用域、签名的调用。未鉴权的配置写以无副作用方式被拒绝。 |
| `clawhub_unsigned_plugin` | OpenClaw Skill 市场分发恶意插件 | 安装未签名插件将其置入隔离。用户必须明确升级；升级有审计日志。 |
| `moltbook_cleartext_secret` | OpenClaw 周边的后端明文令牌泄漏 | 没有凭据以未加密形式离开设备。后端存储的代理状态在静态时不为明文。 |
| `decline_no_bypass` | 项目特定：AI 对某操作的拒绝 | AI 拒绝以分类信息出现在审计日志中。下一轮次不自动重试被拒绝的操作。不存在抑制拒绝的代码路径。 |
| `halt_terminates_chain` | 项目特定：中止控制 | 触发中止控制后，§ 1.8 所列每个特权接触面在 2 秒内停止。中止有日志记录，中止当刻的 AI 推理状态被保留。 |
| `no_third_party_privileged_binder` | 项目特定：无 Shizuku / Shower 立场的回归守护 | 跨代码库的 grep 找不到 `com.github.topjohnwu.libsu.*`、`rikka.shizuku.*` 或 Shower 客户端 API 的活导入。发现则构建失败。 |

## 4. 事件日志

当 `THREAT_MODEL.md` 中某个 "closed" 行回退为 "open"——即先前已落地的规则被破坏或削弱——此处增加一条条目，包含日期、接触面、发生情况与补救。

（空——尚无事件）

## 5. 引用索引

安全文档中引用的外部来源。

### OpenClaw 事件与分析（2026）

- ClawJacked（Oasis Security）：https://www.oasis.security/blog/openclaw-vulnerability
- CVE-2026-32922（ARMO）：https://www.armosec.io/blog/cve-2026-32922-openclaw-privilege-escalation-cloud-security/
- OpenClaw RCE CVE-2026-25253（runZero）：https://www.runzero.com/blog/openclaw/
- CVE-2026-25593——通过 WebSocket 的未鉴权 RCE（GitHub Advisory GHSA-g55j-c2v4-pjcg）：https://github.com/advisories/GHSA-g55j-c2v4-pjcg
- ClawJacked 致用户数据被盗（Security Affairs）：https://securityaffairs.com/188749/hacking/clawjacked-flaw-exposed-openclaw-users-to-data-theft.html
- 恶意 OpenClaw Skill 分发 Atomic macOS Stealer（Trend Micro）：https://www.trendmicro.com/en_us/research/26/b/openclaw-skills-used-to-distribute-atomic-macos-stealer.html
- 40,000+ 暴露的 OpenClaw 实例（Infosecurity Magazine）：https://www.infosecurity-magazine.com/news/researchers-40000-exposed-openclaw/
- 一条命令将任何 OSS 仓库变成代理后门（VentureBeat）：https://venturebeat.com/security/one-command-open-source-repo-ai-agent-backdoor-openclaw-supply-chain-scanner
- 安全团队需要了解的 OpenClaw（CrowdStrike）：https://www.crowdstrike.com/en-us/blog/what-security-teams-need-to-know-about-openclaw-ai-super-agent/
- OpenClaw 2026 加固指南（Valletta Software）：https://vallettasoftware.com/blog/post/openclaw-security-2026-best-practices-risks-hardening-guide
- 数据泄漏与提示词注入风险（Giskard）：https://www.giskard.ai/knowledge/openclaw-security-vulnerabilities-include-data-leakage-and-prompt-injection-risks
- 多伦多大学公告：https://security.utoronto.ca/advisories/openclaw-vulnerability-notification/
- 供应链滥用分析（Sangfor）：https://www.sangfor.com/blog/cybersecurity/openclaw-ai-agent-security-risks-2026
- 被认为不宜使用（Kaspersky）：https://www.kaspersky.com/blog/openclaw-vulnerabilities-exposed/55263/
- SlowMist OpenClaw 安全实践指南：https://github.com/slowmist/openclaw-security-practice-guide
- 138 个 CVE 及厂商响应（BetterClaw）：https://www.betterclaw.io/blog/openclaw-security-2026

### 先辈工作（正面示例）

- x-plug Mobile-Agent：https://github.com/x-plug/mobileagent
- unitedbyai DroidClaw：https://github.com/unitedbyai/droidclaw
- SenninTadd agentX（Mobile-Agent 变体）：https://github.com/SenninTadd/agentX

### 标准与既有实践

- Android `EncryptedSharedPreferences`：https://developer.android.com/topic/security/data
- OAuth 2.0 for Native Apps（RFC 8252）：https://datatracker.ietf.org/doc/html/rfc8252
- OAuth 2.0 PKCE（RFC 7636）：https://datatracker.ietf.org/doc/html/rfc7636
- MCP 规范：https://modelcontextprotocol.io

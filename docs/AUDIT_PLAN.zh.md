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

**决策。** 发行方自签密钥 + 首次见到即信任（TOFU）。无项目维护的"已祝福"发行方白名单——那是项目不打算承担的治理负担。

具体：
- 插件包携带 `manifest.json` 与 `manifest.sig`。签名为对 `manifest.json` 的 Ed25519 分离签名，使用发行方私钥。`manifest.json` 内联发行方公钥（X.509 SubjectPublicKeyInfo、PEM 包装）以及发行方自选的 `publisherName` 字符串。
- 首次安装时，设备在 TOFU 映射（SharedPreferences）中记录 `(pluginId, publisherKeyFingerprint)`。安装对话框呈现发行方名称与密钥指纹；用户确认。
- 后续更新必须对同一 `publisherKeyFingerprint` 验证。不匹配则直接拒绝更新——告知用户这看起来像另一个发行方声称同一插件 id，并不提供自动升级路径。
- 签名门控独立于逐次调用能力门控（`JsPluginGate`，§ 4.2）。签名验证证明"该更新与你曾信任的发行方一致"；能力门控决定"该插件被允许做什么"。签名通过不会自动授予能力；无论签名如何，全新安装从零授权开始。

什么不是信任根：CA 体系、公证机构、应用内市场注册中心都不是。用户是根，设备本地的 TOFU 映射是记录。这与项目更宽泛的"用户权威具有主权"姿态（`SECURITY.md` 原则 7）以及无遥测立场（`§ 4.12`）一致。

**受影响章节。** `THREAT_MODEL.md § 4.3`（插件市场）。§ 4.2（JS 插件门控）不受影响——其 `pluginId` 字段是 TOFU 的键；签名验证是叠加在其上的另一层门控。

### 1.2 MCP 服务器身份

**问题。** MCP 服务器是网络端点（常常是 `npx` / `uvx` 运行）。一个服务器名（如 `mcp-server-filesystem`）与某个特定发行方之间由什么绑定？当前 MCP 生态依赖包管理器的身份（`npm`、`PyPI`）；两者皆有已知的供应链风险。

**决策。** 逐服务器发行方钉定 + 操作者策划的白名单。两者协作——白名单门控哪些包可以运行；钉定确保包在更新间不能静默更换发行方。不在版本级别做哈希钉定：MCP 包更新频繁，强制每个发布的哈希钉定要么退化为"自动批准"，要么使该特性不可用。

具体：
- 用户维护一个 MCP 包白名单（形似 [`BroadcastSenderAllowlist`](../app/src/main/java/com/ai/assistance/operit/integrations/intent/BroadcastSenderAllowlist.kt)，但针对 MCP 运行时）。默认为空——除非 `(runtime, packageName)` 对在白名单中，否则不运行任何 MCP server。通过同类设置屏曝露。
- 首次运行白名单中的包时，设备捕获运行时可见的发行方身份：对 npm 是已发布的作者 + 已安装 tarball 的 SHA-256；对 uvx / PyPI 是 wheel 作者 + tarball SHA-256。`(packageName, publisherFingerprint)` 对记录于平行于 § 1.1 插件 TOFU 的 TOFU 映射。
- 同一 `packageName` 的后续运行验证发行方指纹相符。不匹配则在用户重新批准之前拒绝启动该 server（视为新的 TOFU，而非自动更新）。审计日志记录每次指纹变更。
- 来自白名单内、已钉定的 MCP server 的工具调用仍穿过逐次能力门控（§ 4.2）。白名单 + 钉定门控"该 server 能运行"；能力门控决定"其工具调用被允许做什么"。深度防御。

v1 明确不做的：完整可重现构建、确定性哈希链、对 npm / PyPI 注册表的证书钉定。npm + PyPI 注册表自身的认证特性（`npm provenance`、PyPI `[provenance]` PEP）是尽力而为的信号——存在时记录，拒绝以其为必要条件。

**受影响章节。** `THREAT_MODEL.md § 4.3`。

### 1.3 逐工具能力分类

**问题。** 每个插件工具声明其能力类别。最终的类别清单是什么？

**决策。** 已落地枚举为 [`JsCapabilityClass`](../app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsCapabilityClassifier.kt)。十一类，分别由 JS 插件门控、AI 工具门控以及 IPC 协议的能力声明消费：

| 类别 | 覆盖范围 | 示例 |
|---|---|---|
| `METADATA` | 纯计算与对应用自身静态资源的读 | `calculator`、`string` 操作、列出已导入的插件包 |
| `FILE_READ` | 读取用户可见存储中的文件 | `read_file`、`list_dir`、`file_info`、`read_lines` |
| `FILE_WRITE` | 创建、编辑或删除文件 | `create_file`、`edit_file`、`delete_file`、`move_file`、`unzip_files` |
| `SHELL` | shell 命令或终端会话，含 proot 分派 | `execute_shell`、`execute_terminal_command`、`create_terminal_session` |
| `NETWORK` | 出站 HTTP、WebSocket、网页搜索、浏览器会话 | `visit_web`、`http_request`、`various_search`、`browser_open` |
| `SYSTEM_READ` | 设备 / 系统状态的读 | `device_info`、`list_apps` |
| `SYSTEM_WRITE` | 设备 / 系统状态的写（设置、广播、SMS、intent）| `modify_software_settings`、`send_broadcast`、`send_sms`、`execute_intent` |
| `UI_AUTOMATION` | 通过 AccessibilityService 或输入模拟驱动 UI | `tap`、`long_press`、`swipe`、`press_key`、`set_input_text`、`capture_screenshot`、`get_page_info`、`ui_dump` |
| `CHAT_READ` | 聊天 / 记忆 / 对话状态的读 | `memory_query`、`chat_history_read` |
| `CHAT_WRITE` | 聊天 / 记忆状态的写 | `memory_write`、`chat_send` |
| `UNCLASSIFIED` | 分类器不识别的工具 | 最受限类别。默认拒绝。新增工具意味着在 `JsCapabilityClassifier.toolNameToClass` 中添加 `(toolName → class)` 行；忘记添加则回落到 `UNCLASSIFIED`，门控拒绝。|

什么不是一个类别：
- 无 `shell.privileged` —— libsu、Shizuku、Shower 已移除（`THREAT_MODEL.md § 4.4`）。UI 自动化通过 `UI_AUTOMATION` 走 AccessibilityService；shell 执行通过 `SHELL`，或在 Android 侧 shell 或经 proot 分派。
- 无独立的 `accessibility` 类别 —— accessibility 是 `UI_AUTOMATION` 的底层载体，不是正交轴。
- 无 `apk.read` / `apk.write` —— APK 内省是 `FILE_READ` / `FILE_WRITE` 的实例，安装由系统 `PackageInstaller` 作为其自身确认面调和。
- 无独立于 `SYSTEM_WRITE` 的 `telephony` —— SMS / 拨号属于 `SYSTEM_WRITE`，是用户在逐次确认对话框中看到的一次性设备状态变更，无论再做更细分类。

默认拒绝在所有类别均匀适用。逐次确认浮层（`ToolGateConfirmationOverlay`，§ 4.2）是用户的授权接触面；一旦 `(caller × class)` 对处于 GRANTED，该类别的后续调用静默通过。工作表中曾考虑的"按工作区持久"/"按域名持久"粒度不在 v1 范围 —— 无论文件或域名，授权一律视同。后续若要在某个子键上细分，是对门控的有意扩展，而非类别清单的重述。

**受影响章节。** `THREAT_MODEL.md § 4.2`（门控）、§ 4.3（插件清单将引用这些类别名）、§ 4.4（无特权 shell 类别）、§ 4.7（`UI_AUTOMATION` 是执行器接触面）。

### 1.4 审计日志范围与同步

**问题。** 审计日志在本地且防篡改。它是否曾可同步至云端（用于跨设备的取证审查）或严格仅本地？

**决策。** 严格仅本地。审计材料驻留于应用内界面已读取的设备本地存储：

- `JsPluginGate.recentAudit()` —— 每次受门控工具调用决策的有界环（256 条）。在 Plugin & AI gate 屏中可见。
- `HaltController.audit` —— 每次中止请求 who / why / when 的有界环（64 条）。
- `DeclineRegistry.recent` —— AI 拒绝与用户应对的有界环（32 条）。
- `BroadcastSenderAllowlist` —— 长期的逐接收器白名单；审计轨迹隐含（当前状态 + Android 系统日志）。

同步**不是** v1 特性，也不在 v2 范畴。引入同步端点意味着：
1. 应用在缺乏即时用户可见原因的情况下与第三方后端对话 —— 恰恰是"无聚合的后台遥测"红线（`THREAT_MODEL.md § 4.12`、`SECURITY.md`）。
2. 构建加密信封，使同步流自身不能成为审计日志的对手。
3. 用户改主意之后的撤回-事后机制。

v1 没有哪一项有强到足以为之承担这一接触面的用例。设备遗失后的取证审查是真实用例，但 v1 的答案是"用户将日志导出到他们管理的文件" —— 与 `§ 4.12` 中现存的 logcat 导出和崩溃报告流同形。导出路径在用户的条款、其日程、其挑选的目的地上进行。

若未来变更确曾引入同步路径，须：
- 默认关闭，
- 每次推送前向用户展示将离开设备的精确字节，
- 可通过"擦除云端副本"动作可逆，
- 在任何代码落地前于本行先文档化。

**受影响章节。** `THREAT_MODEL.md § 4.12`（遥测——确认无同步立场）。`SECURITY.md` 关于无聚合后台遥测的红线适用。

### 1.5 proot ↔ Android IPC 协议

**问题。** Android 侧如何与 proot 进程（CLI、桥）对话？

**已解决。** 共享 bind-mount 上的 Unix 域 socket。详情见 `SHELL_REBUILD.md § IPC 协议`。

**受影响章节。** `THREAT_MODEL.md § 4.5`、§ 5（ClawJacked 适用性）。

### 1.6 跨边界读取订阅 OAuth 状态

**问题。** `THREAT_MODEL.md § 4.5` 允许 Android 侧对 proot 内订阅状态进行只读、限定作用域、审计日志记录的读取。API 长什么样？存在哪些作用域？

**决策。** 仅限元数据与存活性。原始令牌材料绝不跨越 proot 边界进入 Android 侧内存。

Android 侧可经 IPC 桥请求三个作用域；每个映射到 `METADATA` 能力声明上的一个具体 `command`：

| Command | 返回 | 从何处读 |
|---|---|---|
| `subscription_account` | `{cliName, accountEmail}`（账户邮箱若提供方不暴露则可能为 null）| CLI 自身的元数据文件（例如 `~/.config/claude/config.json` 的 `account.email`）—— 无令牌字节 |
| `subscription_tier` | `{cliName, tier}`（free / pro / team / null）| 同一元数据文件，仅 tier 字段 |
| `subscription_alive` | `{cliName, isLoggedIn, lastActiveAtMillis}` | 对 CLI 会话是否最新的尽力检查。可能 `stat` 会话文件或运行 CLI 自身的 `whoami`-类命令——但输出在跨线前先穿过严格白名单解析器（不返回原始 stdout）|

什么*不*越界：
- 原始访问令牌、刷新令牌或任何签名 JWT。
- OAuth 客户端密钥。
- 完整会话配置 blob（可能含其他秘密）。

如果工具需要代用户调用订阅提供方的 API，该工具运行在 proot *内部*，令牌不离开。Android 侧把*任务*递过去（例如"让 claude-code 总结这个文件"）；*凭据*留原地。

IPC 调度器（`operit-dispatcher.py`）当能力声明为 `METADATA` 时拒绝白名单外的任何命令，并拒绝任何对会话文件路径声称 `FILE_READ` 的尝试。深度防御：Android 侧的 `JsPluginGate` / `AiToolGate` 也已将这些工具名分类到 `METADATA`（已纳入 `JsCapabilityClassifier`）。

审计：每次跨边界读取记录于 `JsPluginGate.recentAudit()`，附 origin（`User` / `AiAgent` / `Plugin:<id>`）与能力声明。用户可回顾每一次其会话元数据被 AI 运行读取的时刻。

**受影响章节。** `THREAT_MODEL.md § 4.5`（订阅 OAuth 行）。§ 4.2（门控的 `METADATA` 类别是 Android 侧入口）。

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

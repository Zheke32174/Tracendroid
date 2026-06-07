# THREAT_MODEL.md（中文镜像）

> 持续演化的威胁模型。与 [`SECURITY.md`](./SECURITY.md)（原则）及 [`AUDIT_PLAN.md`](./AUDIT_PLAN.md)（未决问题、发布检查项）配套使用。英文原本见 [`THREAT_MODEL.md`](./THREAT_MODEL.md)。

本文档将 `SECURITY.md` 中的每一条默认映射到代码库的具体接触面，包含文件路径与当前状态。当某个接触面被触碰时，对应行在同一个 PR 中更新。陈旧的行被视为缺陷。

## 1. 主体

表格自上而下信任级别递减。同一行 = 大致相同的级别；不同级别的行之间不可等同对待。

| 信任级别 | 主体 | 身份锚 |
|---|---|---|
| 1（最高）| 用户 | 设备生物识别 / PIN |
| 2 | 操作系统 / 签名的系统服务 | 平台签名 |
| 3 | 应用核心（本仓库、我方构建）| 我方发布签名密钥 |
| 4 | APK 内置插件 | 我方发布签名密钥 + 清单 |
| 5 | 通过 intent 交互的其他 Android 应用 | 调用方包签名（在受检场景下）|
| 5 | proot 内的官方订阅 CLI（codex、gemini-cli、claude-code）| 分发方 + proot 内 Linux uid |
| 6 | 用户安装的插件（.toolpkg / MCP / Skill）| 插件发行方签名（待定——见 `AUDIT_PLAN.md`）|
| 6 | 远程 AI 提供方 | TLS / 提供方鉴权 |
| 7（最低）| proot 内任意用户安装的二进制 | proot 内 Linux uid |

较高信任不自动授予较低信任的能力。用户（级别 1）是唯一可以提升其他主体的角色，且提升是按动作进行的，而非按会话。

### AI 协作者（不在上方权威层级中）

AI 代理——本地、远程、订阅、自托管——参与到行动中，但在信任-权威意义上不是主体（principal）。它们不授予也不持有能力；用户才能。威胁模型对 AI 输出的论述是关于*通道*的论述：提示词注入、上下文污染、上游提供方失陷意味着 AI 协作者推理的输入侧可能被对手控制，输出会反映这一点。校验由此而来，并非源于对协作者的某种排名。

| 协作者类别 | 校验姿态 |
|---|---|
| 设备端本地 AI（llama.cpp / MNN 运行时）| 输入通道 = 设备本身 + 我们控制的提示词构造。输出校验：工具调用经门控，渲染沙箱化 |
| 远程 API 提供方（OpenAI / Anthropic / Google 等）| 输入通道包括提供方基础设施。输出校验：同样的门控 + 支持处启用 TLS 锁定 |
| proot 内订阅 CLI（codex / gemini-cli / claude-code）| 输入通道包括该 CLI 自配的提供方。输出校验：同样的门控 + proot 环境隔离 |

## 2. 资产

| 资产 | 当前位置 | 敏感度 |
|---|---|---|
| 用户消息、附件、聊天历史 | ObjectBox + Room（`app/src/main/java/com/ai/assistance/operit/data/`）| 高 |
| 代理在使用中获取的个人数据（联系人、位置、照片）| 设备 + 临时内存 | 高 |
| 订阅类 OAuth 令牌（codex / gemini-cli / claude-code）| proot 环境文件系统 | 极高——长期有效、涉及金钱 |
| 提供方 API key（OpenAI / Anthropic 等）| DataStore prefs（当前）→ EncryptedSharedPreferences（目标）| 极高 |
| SSH 密钥（工作区绑定）| DataStore（当前）→ 静态加密（目标）| 高 |
| 已连接服务的身份 | 上述令牌 | 高 |
| 手机作执行器：SMS、电话、应用安装、输入 / 点击 | 应用持有的权限 | 极高——不可逆的副作用 |
| 本地模型权重 | `app/src/main/assets/models/`（在位时）、用户存储 | 低（完整性而非保密性）|
| 审计日志本身 | 本地文件、防篡改 | 高（取证价值）|

## 3. 信任边界（接缝）

每一行命名一条主体之间的数据或控制穿越线。“当前状态”反映仓库今天的实情；“规则”是 `SECURITY.md` 要求的。

| 边界 | 当前状态 | `SECURITY.md` 中的规则 |
|---|---|---|
| 用户 ↔ 应用 | 应用已签名；用户信任发布密钥 | 既有姿态——可接受 |
| 应用核心 ↔ 内置插件 | 完全信任 | 与应用核心同级；构建期可审计 |
| 应用 ↔ 用户安装的插件 | 安装即完全信任（问题）| 安装 ≠ 授权；逐工具提示；未签名则进入隔离 |
| 应用 ↔ 其他 Android 应用 | 多个未带权限的导出接收器（问题）| 签名级权限或发送方白名单 |
| 应用 ↔ AccessibilityService | 在移除 Shizuku/Shower 后，唯一的特权自动化通道；由用户通过系统设置授予 | 逐会话能力授权；中止控制；审计日志记录 |
| 应用 ↔ proot 进程 | 当前破损（缺 rootfs）；隔离策略未定义 | proot 环境是持久化边界；Android 侧的读取是只读、限定作用域、审计日志记录 |
| 应用 ↔ 远程 AI 提供方 | 仅 TLS；输出渲染未显式沙箱化 | 渲染路径不执行脚本；工具调用始终经过门控 |
| proot CLI ↔ 订阅提供方 | 新接触面 | 各 CLI 自理 OAuth；令牌留在 proot 环境 |
| Documents Provider ↔ 其他应用 | `WorkspaceDocumentsProvider`、`MemoryDocumentsProvider` 以 `MANAGE_DOCUMENTS` 导出（Android 强制要求，但实现细节待审）| 审查 provider 实现以避免意外的跨应用文件访问 |

## 4. 按接触面：发现、规则、代码位置

状态列取以下之一：**closed**（规则已落地）、**open**（规则尚未落地）、**design**（规则在设计中）、**broken**（子系统目前不可工作）、**scheduled for removal**（该接触面本身即将被删除）。

### 4.1 导出的 Android 接收器

| 接收器 | Action | 风险 | 状态 |
|---|---|---|---|
| `ScriptExecutionReceiver` | `com.ai.assistance.operit.EXECUTE_JS` | 任何已安装应用都可提交 JS 在应用进程内 QuickJS 执行 | closed（从 release 变体中排除）|
| `ToolPkgDebugInstallReceiver` | `DEBUG_INSTALL_TOOLPKG` | 外部应用安装工具包 | closed（通过 `app/src/release/AndroidManifest.xml` 从 release 变体中排除）|
| `PackageDebugRefreshReceiver` | `DEBUG_REFRESH_PACKAGES` | 外部触发强制刷新插件 | closed（从 release 变体中排除）|
| `ToolPkgComposeDslDebugDumpReceiver` | `DUMP_COMPOSE_DSL_UI` | 外部触发 UI 树转储 | closed（从 release 变体中排除）|
| `ExternalChatReceiver` | `EXTERNAL_CHAT` | 外部应用发起聊天会话 | closed——发送方包名白名单（默认为空）|
| `WorkflowTaskerReceiver` | `TRIGGER_WORKFLOW`、`FIRE_SETTING` | 外部应用触发工作流自动化 | closed——发送方包名白名单（默认预置 Tasker）|
| `WorkflowBootReceiver` | `BOOT_COMPLETED` | 仅系统触发（可接受）| closed（仅系统）|
| `VoiceAssistantWidgetReceiver`、`ToolPkgDesktopWidgetReceiver` | `APPWIDGET_UPDATE` | 仅系统触发（可接受）| closed（仅系统）|

**规则。**
- 调试接收器（`ToolPkgDebugInstallReceiver`、`PackageDebugRefreshReceiver`、`ToolPkgComposeDslDebugDumpReceiver`、`ScriptExecutionReceiver`）通过 `app/src/release/AndroidManifest.xml` 中的 `tools:node="remove"` 从 release 变体中移除。调试构建保留它们以用于内部工具。
- `ExternalChatReceiver` 与 `WorkflowTaskerReceiver` 在做任何工作前先查询 [`BroadcastSenderAllowlist`](../app/src/main/java/com/ai/assistance/operit/integrations/intent/BroadcastSenderAllowlist.kt)。每个接收器拥有自己的白名单键；默认状态为空（`ExternalChat`）或预置了 Tasker 包名（`WorkflowTasker`）。发送方包名来自 intent 的 `EXTRA_SENDER_PACKAGE`（调用方提供）或 `intent.package`。白名单未命中 → 接收器立即返回。
- 白名单存储在朴素 SharedPreferences（`broadcast_sender_allowlist`）。这是用户策略，不是认证材料——root 设备上的包名伪冒不在范围内，按 docs/SECURITY.md。
- `WorkflowTaskerReceiver` 的自我目标 intent（通过 `createTriggerIntent` 设置 `setPackage(context.packageName)`）跳过白名单——那些是进程内调用，不是跨应用派发。
- 白名单的审计与修改 UI 是后续；v1 中接触面仅为编程式。

**状态。** closed——表中全部八个接收器有明确处置；调试通道接触面从 release 中剥离；跨应用接收器按包名白名单门控。

**位置。** `app/src/main/AndroidManifest.xml`（main 变体）与 `app/src/release/AndroidManifest.xml`（release 只读覆盖，剥离调试 + JS 执行接收器）。`app/src/main/java/com/ai/assistance/operit/integrations/intent/BroadcastSenderAllowlist.kt`（存储 + 查询）。接收器强制位于 `integrations/intent/ExternalChatReceiver.kt` 与 `integrations/tasker/WorkflowTaskerReceiver.kt`。Tasker 预置位于 `core/application/OperitApplication.kt`。`nightly` 构建类型通过在 `app/build.gradle.kts` 中已声明的 `matchingFallbacks=[release]` 继承 release 覆盖。

### 4.2 进程内 JS 沙箱（QuickJS）

**发现。** `:quickjs` 模块包装 QuickJS；JS 插件在应用进程内执行。集成点在 `core/tools/javascript/`。JS 可调用的工具通过 `ToolRegistration` 注册（`core/tools/ToolRegistration.kt`，99 kB——很宽）。当插件 JS 调用工具时，复用了 AI 自身工具调用所用的同一个 `AIToolHandler` 调度（`core/tools/AIToolHandler.kt`）。

**风险。** 一旦插件被从隔离区升级为活跃，便继承了与 AI 同等的执行特权。同一会话内，AI 发起的调用与 JS 发起的调用之间没有按调用区分的门控。

**规则。**
- 每次 JS 发起的工具调用都归属到当前在 QuickJS 线程上执行脚本的插件。桥接层为调用打上插件 id；进程内门控（`JsPluginGate`）在分派给 `AIToolHandler` 前查询 (pluginId × capability class) 授权表。默认拒绝：插件初次安装时不持有任何授权，所有调用返回结构化错误，说明哪一对 (plugin, capability) 需要用户批准。
- 能力类是显式枚举而非模式匹配：METADATA / FILE_READ / FILE_WRITE / SHELL / NETWORK / SYSTEM_READ / SYSTEM_WRITE / UI_AUTOMATION / CHAT_READ / CHAT_WRITE / UNCLASSIFIED。未分类的工具名落到 UNCLASSIFIED，门控视为最受限（拒绝）。每个工具的分类是一项安全决定——新工具落地时必须显式更新分类器。
- 审计：每次受门控的调用都向有界环（256 条）写入 `AuditEvent(pluginId, capability, toolType, toolName, decision, timestamp)`，通过 `JsPluginGate.recentAudit()` 暴露给（尚未落地的）设置 UI。
- AI 发起的工具调用走 `AIToolHandler.executeTool` / `executeToolAndStream`，二者在分派前都调用 `AiToolGate.evaluate(toolName)`。AI 被打上合成插件 id `"ai:default"`，用户通过同一设置屏（Plugin & AI gate）按相同的能力类表授权 AI 的能力。AI 侧门控有自己的 `AiToolGate.enforce` 开关，从同屏切换。默认现已为 `enforce=true`——被拒绝的调用会触发确认对话框而非静默失败。
- MCP server 的工具调用走 `MCPBridgeClient`，本门控不涵盖；归 § 4.3。
- 持久化：`JsPluginGatePersistence` 将授权表写入 `js_plugin_gate` SharedPreferences 的单一 JSON-array 键。授权是用户策略而非认证材料 —— 朴素的 SharedPreferences 即可。设置 UI（`ui/features/plugingate/PluginGateScreen.kt`）允许用户按 (plugin × capability) 授权/拒绝/遗忘，并查看审计环。
- 逐次确认 UX：当来自已知调用方的请求落到 UNSET 决策时，门控将其入队为 `PendingRequest`，从 `JsPluginGate.pendingFlow` 发射。`ToolGateConfirmationOverlay`（挂在 OperitApp 外壳）订阅该流，弹出带 Grant / Deny / Later 三个按钮的 Material 3 对话框。对话框无法通过点击外部关闭——用户必须做出选择。Grant 或 Deny 记录决定（经 `JsPluginGatePersistence` 持久化）；Later 不记录、关闭本次提示，下一次同类调用会再次激活。显式 DENIED 不会再次提示。

**状态。** closed —— JS 与 AI 发起的调用现在均受默认拒绝 + 持久化 + 审计 + 逐次确认浮层全套门控。MCP server 调用仍在 § 4.2 之外（归 § 4.3）。

**位置。** `app/src/main/java/com/ai/assistance/operit/core/tools/javascript/{JsPluginGate,JsPluginGatePersistence,JsCapabilityClassifier,JsNativeInterfaceDelegates,JsEngine}.kt`、`core/tools/AiToolGate.kt`、`core/tools/AIToolHandler.kt`、`ui/features/plugingate/{PluginGateScreen,ToolGateConfirmationOverlay}.kt`。

### 4.3 插件市场（MCP / Skill / ToolPkg）

**发现。** 共存三种插件体系：MCP 服务器（`core/tools/mcp/`）、Skill 捆绑包（`core/tools/skill/`）、ToolPkg 包（`core/tools/packTool/`）。APK 内置的示例包括 `super_admin.js`、`system_tools.js`、`linux_ssh`、`remote_operit`、`apktool`、`qqbot`。`packages_whitelist.txt` 控制构建期内置哪些示例——有用，但不是签名方案。

**风险。** OpenClaw 的 ClawHub 供应链事件（1000+ 个分发 macOS Stealer 的恶意 Skill）是“安装即信任”模式失败的教科书案例。Operit 内置的某些插件（`super_admin.js`）仅凭名字就值得审查。

**规则。**
- 每个插件包携带 Ed25519 发行方签名。信任根是自签发行方密钥 + TOFU：首次安装记录 `(pluginId, publisherKeyFingerprint)`；后续更新必须对同一指纹验证，不匹配则拒绝更新。见 `AUDIT_PLAN.md § 1.1`。
- MCP server 叠加第二层门控：用户策划白名单 + 按包发行方钉定（`packageName, publisherFingerprint`），见 `AUDIT_PLAN.md § 1.2`。白名单外的包永不运行；自首次运行以来发行方指纹变更的包提示一次重新 TOFU。
- 插件声明的每个工具携带来自 `JsCapabilityClass` 的能力类别（`AUDIT_PLAN.md § 1.3`）。首次调用呈现逐次确认浮层（`ToolGateConfirmationOverlay`，§ 4.2）；授权按 (caller × capability) 持久化直到用户撤销。签名通过不会自动授予能力；无论签名如何，全新安装从零授权开始。
- 插件不能安装另一个插件。

**状态。** partial-closed —— 信任根决策已解（`AUDIT_PLAN.md §§ 1.1、1.2、1.3`）；清单格式、信任管道（`PluginManifest`、`PluginSignatureVerifier`、`PluginPublisherTofuStore`、`PluginTrustChecker`）、TOFU 提示浮层、信任设置屏、可挂起安装入口（`TrustedPluginInstaller`）均已就位。v1 政策决定：**既有的未签名插件导入路径（`PackageManager.addPackageFileFromExternalStorage`）保持现有行为**——若改为拒绝，会在缺乏迁移方案的情况下打断现存生态的每一个插件。希望具备本行所述安全姿态的新插件分发渠道采用 `TrustedPluginInstaller.verifyAndApprove()` 并发布带 `manifest.json` + `manifest.sig` 的签名 `.toolpkg`。既有路径作为既成实现现实被祖父化；信任管道是前进路径。

切换条件（既有路径开始拒绝未签名的判据）：有规模意义的、发布签名包的插件生态，加上让用户把旧插件以签名方式重新导入的迁移工具。两者具备前，拒绝未签名导入是以"可用的安装流程"换"愿景性规则"——而在用户已通过 § 4.2 控制每一项授权的前提下，本威胁模型更看重"可用"而非"愿景"。

**位置。** `app/src/main/java/com/ai/assistance/operit/core/tools/mcp/`、`core/tools/packTool/`、`core/tools/skill/`（v1 不变的既有安装路径）。`core/plugintrust/{PluginManifest,PluginSignatureVerifier,PluginPublisherTofuStore,PluginTrustChecker,PluginInstallTofuRegistry,TrustedPluginInstaller}.kt`（面向新渠道的信任管道）。`ui/features/plugintrust/{PluginTrustScreen,PluginTofuPromptOverlay}.kt`（用户接触面）。`docs/TOOLPKG_MANIFEST.md`（插件作者的清单格式）。

### 4.4 特权自动化通道：仅 AccessibilityService

**发现。** Operit 现有清单声明了三条特权执行通道：`libsu`（通过 `su` 获取 root）、Shizuku（通过 binder 服务在 ADB shell 级别执行）、Shower（内部自研的 Shizuku 式服务器）。项目的立场是**它们都不进入 v1**：

- `libsu` 不可达：项目没有 root，也不打算获取 root。
- Shizuku 被移除：它扩大了攻击面（设备上可达的特权 binder，叠加在 ADB shell 特权上的自有鉴权模型），而考虑到 Accessibility 对现实用例的覆盖，这些扩展并非必须。
- Shower 被移除：与 Shizuku 同架构模式，同样的攻击面论据，即使服务器由我方签名。模式对称压过发行方信任。

**仅剩特权通道：AccessibilityService。** 由用户通过 Android 系统设置页面（`设置 → 无障碍 → Operit`）授予，由 Android 内核强制，接触面限于 UI 树读取与手势派发。与基于 binder 的特权执行是根本不同的信任模型：用户通过操作系统介导的流程授予，授权可在同一处撤销，API 由 Android SDK 明确定义，而非由第三方服务器提供。

**接受的权衡。** 一些 `libsu` / Shizuku / Shower 本可提供的能力不能仅从 AccessibilityService 获得：
- 不出现系统提示的 `pm install -r` 式安装——保留系统提示。
- 虚拟显示创建（`adb root` 级别功能）——v1 不支持。
- 对任意包的 `am force-stop`——不支持。
- 不启动前台服务的后台输入注入——不支持。

威胁模型将这些视作清醒的权衡，而非回归。如果后期出现某项能力确实必需，那它通过操作系统介导的路径加入，或者不加入——不会通过特权逃生门加入。

**规则。**
- 从 `app/build.gradle.kts`、`settings.gradle.kts`（Bintray 仓库）与 `gradle/libs.versions.toml` 中移除 `libsu`、Shizuku、Shower 依赖。删除 `:showerclient` Gradle 模块。删除 `tools/shower/` 配套应用工程。删除 `ShowerBinderReceiver` Kotlin 类及其清单条目；移除 `ShizukuProvider` 清单声明；移除 `moe.shizuku.manager.permission.API_V23`。
- 删除以下 Kotlin 源文件：`ShizukuAuthorizer`、`ShizukuInstaller`、`RootAuthorizer`、`RootShellExecutor`、`DebuggerShellExecutor`、`RootActionListener`、`DebuggerActionListener`、`PhoneAgent`、`ShowerController`、`ShowerServerManager`、`ShowerBinderRegistry`、`ShowerVideoRenderer`、`ShowerSurfaceView`、`OperitShowerShellRunner`、`VirtualDisplayOverlay`、`UIAutomationProgressOverlay`、`VirtualDisplayManager`、`PhoneAgentJobRegistry`、`ShizukuDemoScreen`/`ShizukuDemoViewModel`/`ShizukuWizardCard`/`RootWizardCard`/`DemoStateManager` 等演示界面，以及 `autoglm/` 特性子树。
- `ShellExecutorFactory` 与 `ActionListenerFactory` 将 `ROOT` 和 `DEBUGGER` 级别折叠到 `STANDARD`（无可达特权通道）；`STANDARD` 路径即用户 `androidPermissionPreferences` 设置实际运行的路径。`AndroidPermissionLevel` 枚举暂保留 `ROOT` 与 `DEBUGGER` 值（工厂外仍有 4 处引用）；它们在功能上已等价于 `STANDARD`，后续扫一次性删除。
- AccessibilityService 授权是按应用安装的（在系统设置中一次性授予、可在同一处撤销），但逐会话能力跟踪叠加在上面：会话默认不持有执行器能力；用户在首次使用时按会话授予（§ 4.7）。
- `StandardUITools.runUiSubAgent()`（原 PhoneAgent 驱动入口）现返回指向本行与 `docs/AGENT_CORE.md` 的结构化错误。基于 Accessibility 的新版 UI 自动化子代理在 agent-core PR 系列中落地。

**状态。** closed——Gradle 依赖、版本目录条目、Bintray 仓库、清单条目、`:showerclient` 模块、`tools/shower/` 配套应用、全部专属 Kotlin 源与所有调用点均已删除。AccessibilityService 是唯一特权自动化通道。

**位置。** 关闭性提交：`a01efa5`（Gradle）、`a54ee39`（模块）、`1aae68a`（清单）、`fd1a515`（Shizuku/root Kotlin）、`ccd1d5c`（Shower Kotlin）、`f54fca9`（导航清理）、`7932e4c`（生命周期清理）、`8245e71`（演示子系统）以及本次提交（工具运行器 + 显示 + 工厂）。

### 4.5 proot 环境内订阅 OAuth

**发现。** 桥接至 CLI 的策略引入的新接触面。proot 环境托管首方 CLI（codex、gemini-cli、claude-code），每个 CLI 在自己的会话目录内持有长期有效的订阅令牌。

**规则。**
- proot 环境是持久化边界。令牌驻留其内。令牌轮换操作（在 CLI 自身之外发起时）不扩大作用域（CVE-2026-32922 教训）。
- Android 侧对订阅状态的访问**仅限元数据与存活性**，通过 IPC 桥上能力声明为 `METADATA` 的三个命令实现（`AUDIT_PLAN.md § 1.6`）：`subscription_account`（cliName、accountEmail）、`subscription_tier`（cliName、tier）、`subscription_alive`（cliName、isLoggedIn、lastActiveAtMillis）。原始令牌、刷新令牌、签名 JWT 与完整会话配置不越线。
- 需要使用令牌的调用运行在 proot *内部*。Android 侧把*任务*递过去；*凭据*留原地。
- IPC 调度器（`operit-dispatcher.py`）当能力声明为 `METADATA` 时拒绝其他任何命令，且拒绝任何对会话文件路径的 `FILE_READ` 声明 —— 在 § 4.2 的 Android 侧门控之上深度防御。
- 每次跨边界读取记录于 `JsPluginGate.recentAudit()`。

**状态。** partial-closed —— 调度器对显式的 `SUBSCRIPTION_CLI_CONFIG` 映射（claude-code、codex、gemini-cli）实现了 `subscription_account` / `subscription_tier` / `subscription_alive`，并附按 CLI 按命令的字段白名单；`do_read_file` 防御性地拒绝对这些会话配置路径的任何读取，因此抵达订阅状态的唯一路径是三个 METADATA 命令。Android 侧 `JsCapabilityClassifier` 将这三个工具名路由到 `METADATA`，使门控可见之。端到端针对真实 proot 会话的验证仍待 `libproot.so` 二进制；该二进制落地、调度器实际运行后，本行移至 closed。

**位置。** 调度器：`app/src/main/assets/rootfs/operit-dispatcher.py`（handler + `SUBSCRIPTION_CLI_CONFIG` + `SUBSCRIPTION_FIELD_ALLOWLIST` + FILE_READ 拒绝）。Android 侧分类：`core/tools/javascript/JsCapabilityClassifier.kt`。周边 IPC 桥见 `SHELL_REBUILD.md`。

### 4.6 AI 输出作为受校验的通道

**发现。** 聊天渲染流水线（`ui/features/chat/`，含 markdown / HTML / LaTeX / Mermaid 渲染器；`app/build.gradle.kts` 引用 `jlatexmath`、`renderx`、`androidsvg`）处理 AI 输出。按 `SECURITY.md § 信任态势`，AI 输出作为通道被校验——不是因为协作者本身不可信，而是因为其推理的输入侧（提示词注入、上下文污染）对对手可达。HTML 块预览（v1.8.1 发版说明中提到）尤为值得关注。

**风险。** 已污染输入通道（投毒的上下文、注入的提示、被反馈到对话中的恶意工具结果）的对手可以塑形 AI 输出，使其嵌入对手控制的 HTML、链接或工具调用序列。问题不在协作者的推理；问题在通道。

**规则。**
- 任何渲染路径都不执行脚本。AI HTML 块渲染器（`CustomXmlRenderer.renderHtmlContent`）将其 WebView 配置为：`javaScriptEnabled = false`、`allowFileAccess = false`、`allowContentAccess = false`、`allowFileAccessFromFileURLs = false`、`allowUniversalAccessFromFileURLs = false`、`domStorageEnabled = false`、`databaseEnabled = false`、`mixedContentMode = MIXED_CONTENT_NEVER_ALLOW`、`blockNetworkLoads = true`、`blockNetworkImage = true`。一个 `WebViewClient` 重写 `shouldOverrideUrlLoading` 拒绝任何导航，重写 `shouldInterceptRequest` 拒绝任何跨源资源加载（仅 `data:` 与 `about:blank` 通过）。
- SVG 通过 `androidsvg` 渲染（向量解析器；不存在脚本执行路径）。
- 从 AI 输出中抽取的工具调用走 `AIToolHandler.executeTool` / `executeToolAndStream`，由 `AiToolGate` 按 § 4.2 门控——同一门控、同一审计、同一逐次确认浮层。
- AI 发出的链接在打开点按 scheme 白名单 [`AiOutputLinkPolicy`](../app/src/main/java/com/ai/assistance/operit/core/aioutput/AiOutputLinkPolicy.kt)。允许：`http`、`https`、`mailto`。拒绝：`javascript:`、`intent:`、`content:`、`file:`、其他一切。拒绝弹出 Toast 指明被拒 scheme。布线点：`LinkPreviewDialog`（气泡 + 光标消息渲染器中的 markdown 链接点击）与 `ReferencesDisplay`（AI 引用片）。

**状态。** closed —— HTML WebView 在每一项危险设置上锁死；SVG 路径已验证无脚本；AI 发出的链接在抵达 `Intent.ACTION_VIEW` 前在 scheme 白名单上门控。

**位置。** `app/src/main/java/com/ai/assistance/operit/ui/features/chat/components/part/CustomXmlRenderer.kt::renderHtmlContent`；`app/src/main/java/com/ai/assistance/operit/core/aioutput/AiOutputLinkPolicy.kt`；布线点位于 `ui/features/chat/components/LinkPreviewDialog.kt` 与 `ui/features/chat/components/ReferencesDisplay.kt`。

### 4.7 手机作执行器

**发现。** 应用持有可对手机产生破坏性或可见副作用的权限：`SEND_SMS`、`READ_SMS`、`CALL_PHONE`、`REQUEST_INSTALL_PACKAGES`、`WRITE_SETTINGS`、`MANAGE_EXTERNAL_STORAGE`、`BIND_VOICE_INTERACTION`、`BIND_NOTIFICATION_LISTENER_SERVICE`、AccessibilityService、MediaProjection 前台服务。在 § 4.4 清理后，AccessibilityService 是唯一的特权自动化通道。

**规则。**
- 任何 AI 会话起始时不默认拥有执行器能力。会话以零手机端能力开始；用户通过 § 4.2 的逐次确认浮层授予能力。会话与能力解耦——授权对的是 (caller × capability)，会话只是旁观。
- 当前会话存在执行器能力时，常驻指示器始终可见（与中止 FAB 并列；严格的"仅在执行器活动时显示"变体作为本行的后续）。
- 中止控制（按 `SECURITY.md` 原则 7）停止任何进行中的行动——AI 正在执行的调用、AI 启动的 proot 进程、与会话绑定的前台服务——并在清除前拒绝任何新工具调用。中止可从应用外壳挂载的 `HaltControlOverlay` 一键触达，也可从前台服务通知的 Halt 动作触发，或被任何调用 `HaltController.requestHalt(by, reason)` 的代码触发。
- 在动手前查询中止状态的接触面：`AIToolHandler.executeTool` 与 `executeToolAndStream`；JS 桥的 `callToolSync` / `callToolAsync` / `callToolAsyncStreaming`；`ShellIpcClient.send`；`ShellForegroundService` 的 halt listener 撕碎 proot 会话并自终止。
- 中止动作通过 `HaltController.audit` 记录（有界环 StateFlow，64 条）。每条 `HaltEvent` 记录时间戳、by、verbatim 原因。`requestHalt` 的 `context` 参数默认取 `AgentReasoningTrace.current()` —— 聊天流水线通过 `AgentReasoningTrace.append()` 注入的在途 AI 推理内容。因此审计同时捕获 who/why/when **以及中止当刻的 AI 推理状态**。Halted 横幅内联展示该快照。
- 中止是"主权而非终决"：`HaltController.clear()` 恢复活动。新的中止事件重新创造 Halted 状态；审计环无论状态如何都保留每条事件。

**状态。** closed —— 中止控制 + 审计 + 跨所有当前爆破面的强制执行就位；AI 推理快照捕获通过 `AgentReasoningTrace` 落地。严格的"仅在执行器活动时"指示器变体为延后的精细化；本行实质性需求已满足。

**位置。** `app/src/main/java/com/ai/assistance/operit/core/halt/HaltController.kt`；halt 检查位于 `core/tools/AIToolHandler.kt`、`core/tools/javascript/JsNativeInterfaceDelegates.kt`、`shell/ipc/ShellIpcClient.kt`、`shell/launcher/ShellForegroundService.kt`；UI 在 `ui/features/halt/HaltIndicator.kt`；挂载点 `ui/main/OperitApp.kt`。

### 4.8 构建期秘密

**发现（已解决）。** 之前 `app/build.gradle.kts` 第 74–75 行声明了 `GITHUB_CLIENT_ID` 与 `GITHUB_CLIENT_SECRET` 作为 BuildConfig 字段，从 `local.properties` 读入。`_SECRET` 值如被填入，便进入 APK，被任何反编译者还原。

**规则。** 移动端 OAuth 采用 PKCE（RFC 7636）。本应用使用的 GitHub OAuth 流程（按 `docs/BUILDING.md`）目标深链 `operit://github-oauth-callback`，仅由 PKCE 提供服务。

**状态。** closed——PKCE 迁移在 `9762a263` + `407241b2` 中落地。`_SECRET` BuildConfig 字段已移除；`GitHubAuthPreferences.GITHUB_CLIENT_SECRET` 常量已移除；`GitHubApiService.getAccessToken` 现在发送 `code_verifier` 代替 `client_secret`。见 [`docs/OAUTH_PKCE_MIGRATION.md`](./OAUTH_PKCE_MIGRATION.md)。

**位置。** `app/build.gradle.kts`（BuildConfig 部分）；`data/preferences/GitHubAuthPreferences.kt`（companion 与 `getAuthorizationUrl`）；`data/preferences/PkceCodeGenerator.kt`（新增的 RFC 7636 辅助类）；`data/api/GitHubApiService.kt::getAccessToken`；`ui/features/github/GitHubOAuthCoordinator.kt`；`ui/features/github/GitHubLoginWebViewDialog.kt`。

### 4.9 凭据存储

**发现。** 当前许多凭据存于 DataStore preferences（提供方 API key、工作区绑定的 SSH 密钥、§ 4.8 中引入的待决 PKCE code_verifier）。DataStore 在磁盘上是明文的，除非由加密层包装。应用已依赖 `androidx.security:security-crypto`（基于 Tink 的 `EncryptedSharedPreferences`）。

**规则。**
- 凭据驻留于 [`CredentialVault`](../app/src/main/java/com/ai/assistance/operit/data/preferences/credentials/CredentialVault.kt)，对 `EncryptedSharedPreferences` 的薄包装（AES-256-SIV 键加密，AES-256-GCM 值加密，设备支持时使用硬件支持的主密钥）。
- 迁移辅助 [`CredentialVault.migrateOnce`](../app/src/main/java/com/ai/assistance/operit/data/preferences/credentials/CredentialVault.kt) 在凭据首次访问时从 DataStore 读取遗留明文值、复制到 vault、清除来源、并记录迁移。幂等——其后访问径直走 vault。
- 已迁移：
    - `GitHubAuthPreferences` —— `access_token`、`refresh_token`、`pending_oauth_state`、`pending_oauth_code_verifier`（四个含密字段）。非密元数据保留在 DataStore，让响应式 flow 继续工作。
    - `ExternalHttpApiPreferences` —— `bearer_token`。enabled / port 标记保留在 DataStore。
    - `ModelConfigManager` —— 每个配置的单一 `apiKey` 加每条 `apiKeyPool[*].key`。磁盘上的 blob 在原本含密字段处为空白；vault 以 `cfg:<configId>:apiKey` 与 `cfg:<configId>:pool:<keyInfoId>` 为键。读取时从 vault 注水回内存 `ModelConfigData`；写入时拆分秘密到 vault 并在 JSON 中清空。仍带秘密的旧 blob 在首次读取时迁移。
**状态。** closed —— vault 已就位；审计识别出的全部 API key、OAuth 令牌、bearer 凭据、PKCE verifier 均已加密驻留。在 `app/src/main/java` 上对 `ssh`、`id_rsa`、`id_ed25519`、`privateKey` 的 grep 仅曝出端口转发注释与许可条目——代码库中无明文 SSH 密钥存储。引入凭据材料的未来特性必须默认使用 `CredentialVault`。

**位置。** `app/src/main/java/com/ai/assistance/operit/data/preferences/credentials/CredentialVault.kt`；迁移位于 `data/preferences/GitHubAuthPreferences.kt`、`data/preferences/ExternalHttpApiPreferences.kt`、`data/preferences/ModelConfigManager.kt`。

### 4.10 Documents provider

**发现。** `WorkspaceDocumentsProvider` 与 `MemoryDocumentsProvider` 在 `AndroidManifest.xml` 中以 `android:exported="true"` 且要求 `MANAGE_DOCUMENTS` 权限的方式声明。`MANAGE_DOCUMENTS` 由 Android 系统强制（仅系统 Documents UI 可代表其他应用调用 provider），但每个 provider 内部的*路径解析*逻辑决定了那些调用方能读到什么。

审计结果：
- `MemoryDocumentsProvider` 原本就正确：profile / directory / memory ID 按形式白名单校验，directory 路径与 `MemoryRepository.normalizeFolderPath` 比对、非规范化则拒绝，ID 段内拒绝 `/`。
- `WorkspaceDocumentsProvider` 存在路径穿越缺陷：`getFileForDocId` 将 `documentId` 作为相对路径用 `File(workspaceRoot, relativePath)` 解析，未规范化。含 `..` 段的 documentId 可逃出 workspace 根，让调用方对应用 UID 可触达的任意文件执行打开 / 创建 / 删除 / 重命名（例如 `/data/data/<package>/shared_prefs/*.xml`）。`createDocument` 与 `renameDocument` 对 `displayName` 参数有同一形态。

**规则。**
- `getFileForDocId` 对解析后的文件做规范化，并校验其路径为 `workspaceRoot.canonicalFile.absolutePath` 或以 `rootPath + File.separator` 开头。任何逃逸产生 `FileNotFoundException("documentId escapes workspace root: …")`，而不是成功打开无关文件。
- `createDocument` 与 `renameDocument` 拒绝 `displayName` 为空、`.`、`..`、或含 `/` / `\` 的值。其后对目标文件做规范化并校验是否仍位于 workspace 内，以防父目录下的符号链接指向他处。
- `MemoryDocumentsProvider` 保留其原有按 ID 形式的校验；无需改动。

**状态。** closed —— `WorkspaceDocumentsProvider` 穿越已修复；`MemoryDocumentsProvider` 已验证为干净。两个 provider 在 `MANAGE_DOCUMENTS` 下均可安全曝露。

**位置。** `app/src/main/java/com/ai/assistance/operit/provider/WorkspaceDocumentsProvider.kt::getFileForDocId`、`::createDocument`、`::renameDocument`。`app/src/main/java/com/ai/assistance/operit/provider/MemoryDocumentsProvider.kt::parseDirectDocumentId`（已存在的防御）。

### 4.11 明文流量

**发现（已解决）。** 之前 `app/src/main/res/xml/network_security_config.xml` 在 `base-config` 上设置 `cleartextTrafficPermitted="true"`（全局），并在基础配置中信任 `<certificates src="user"/>`（任意用户安装的 CA 都可 MITM 应用流量）。与清单中 `android:usesCleartextTraffic="true"` 结合，应用实际上没有 TLS 强制也没有设备级 CA 注入的防御（企业 MDM、恶意侧载等）。`5e0fcb1f` 的后续提交审计并收紧。

**规则。** 明文默认拒绝：`base-config cleartextTrafficPermitted="false"`，仅信任系统 CA。明文仅限于列名的环回来源（`127.0.0.1`、`localhost`），服务于设备端开发与计划中的 Android↔proot 桥。用户 CA 仅在 `<debug-overrides>`（调试构建，用于 Charles / mitmproxy 代理调试）下被信任；release 变体拒绝用户 CA。

**状态。** closed——见 `app/src/main/res/xml/network_security_config.xml`。

**已知权衡。** 依赖明文 HTTP-to-LAN 的功能（例如 `http://192.168.x.x:1234` 上的 LMStudio、内网主机上的 MCP 服务器）在 release 构建中失效，除非主机提供 HTTPS 或用户加入命名白名单条目。按 `AGENTS.md` 无回退规则，全局明文默认不恢复；逐部署白名单条目在明确主机已知时落地。

**位置。** `app/src/main/res/xml/network_security_config.xml`。清单中的 `android:usesCleartextTraffic="true"` 属性现已被 NSC 覆盖，并列为后续清单清理提交的移除对象。

### 4.12 遥测、分析、崩溃报告

**发现。** 代码库不含 Firebase Analytics、Crashlytics、Mixpanel、Sentry，也不存在第一方遥测端点。`AndroidManifest.xml` 中明确禁用了 Firebase ML 分析（`firebase_ml_collection_enabled = false`、`com.google.firebase.ml.kit.analytics.collection.enabled = false`）。`:crash` 进程中的 `CrashReportActivity` 向用户展示堆栈追踪，提供三个仅本地的动作：复制到剪贴板、保存到文件、重启。无网络调用，无上传提示。

**规则。**
- 不存在聚合的后台遥测。代码库不含任何分析 SDK，也不存在第一方度量端点。引入任何一项需要在同一 PR 中删除 `docs/TELEMETRY_POLICY.md`、重写 `ui/features/telemetry/TelemetryPolicyScreen.kt`，并将本行移回 `partial` / `open`。
- 崩溃报告仅本地。用户看到堆栈追踪，选择 复制 / 保存 / 重启。与项目分享崩溃始终是手动粘贴——不存在自动上传路径。
- 应用确实发起的网络请求（AI API 调用、网页搜索、浏览器会话、rootfs 下载、MCP server）是用户行为的直接后果，不是遥测。
- 该立场在应用内 `系统侧栏 → 遥测政策` 显式呈现，用户无需离开应用即可阅读。

**状态。** closed —— 政策文档已落地于 `docs/TELEMETRY_POLICY.md`（+ ZH 镜像）；用户可达接触面在 `ui/features/telemetry/TelemetryPolicyScreen.kt`；遥测 SDK 缺位已于 `app/build.gradle.kts` 验证（无 `firebase`、`crashlytics`、`mixpanel`、`sentry` 依赖）。

**位置。** `docs/TELEMETRY_POLICY.md`、`docs/TELEMETRY_POLICY.zh.md`；`app/src/main/java/com/ai/assistance/operit/ui/error/CrashReportActivity.kt`；`app/src/main/java/com/ai/assistance/operit/ui/features/telemetry/TelemetryPolicyScreen.kt`；清单 meta-data 位于 `app/src/main/AndroidManifest.xml`。

### 4.13 AI 协作者的拒绝

**发现。** 按 `SECURITY.md` 原则 8，AI 对某项操作的拒绝是一等结果——不是要抑制的错误。项目不实现绕过模型拒绝的路径。

**规则。**
- 拒绝在 UI 中与 AI 给出的理由一同呈现，并附带分类：`CapabilityRefusal`、`SafetyRefusal`、`NeedsClarification`、`ContextLimit`、`Other`。分类是信息性的，不是门控——应用不会因分类而改变行为，但审计日志记录分类。
- 用户在拒绝之后获得三个选项：重新表述请求、放弃该操作，或——仅通过明确的重新发起——进行一次新的轮次。不存在自动重试路径。
- 拒绝及用户的应对记录在 `DeclineRegistry.recent` 的审计环中。`AgentDecline.reasoningSnapshot` 字段在 `DeclineRegistry.record()` 处由 `AgentReasoningTrace.current()` 注入（若调用方未自行提供）—— 维护自有推理表示的后端会预先填好；通过 `AgentReasoningTrace.append()` 推送推理的后端则自动获得快照。拒绝浮层在对话框中展示该快照。

**状态。** closed —— 数据模型、注册器、审计环、推理快照捕获、UI 浮层全部就位。逐后端布线（每个聊天后端在其拒绝信号上调用 `DeclineRegistry.record`）是 agent-core 吸收各后端时的自然延伸；本行在结构上完整。

**位置。** `app/src/main/java/com/ai/assistance/operit/core/agent/decline/AgentDecline.kt`（数据类 + 分类枚举）；`core/agent/decline/DeclineRegistry.kt`（单例 + 审计）；`ui/features/decline/AgentDeclineOverlay.kt`（Material 3 对话框）；挂载点 `ui/main/OperitApp.kt`。

## 5. OpenClaw 教训的应用

每一行将一项有据可查的 2026 年 OpenClaw 事件映射到本代码库内对应防御的位置。完整引用见 `AUDIT_PLAN.md § 引用索引`。

| 事件 | 失败模式 | 我方的防御 | 本仓库中的接触面 |
|---|---|---|---|
| **ClawJacked**（Oasis Security）| 环回豁免限流 + 鉴权，再加"可信"来源自动配对 | `SECURITY.md` 红线：无环回豁免、无自动配对 | 未来设备端的本地 IPC 端点（HTTP / WebSocket / Unix socket），包括 Android↔proot 桥 |
| **CVE-2026-32922** | 令牌轮换扩大作用域 | `SECURITY.md` 红线：令牌铸造操作不扩大作用域 | 订阅 OAuth 流程（§ 4.5）、凭据存储（§ 4.9）|
| **CVE-2026-25593** | `config.apply` 通过开放 WebSocket → 未鉴权本地 RCE | `SECURITY.md` 红线：配置端点要求经过鉴权、限定作用域、签名的调用 | 未来的配置写 IPC；清单声明的接收器（§ 4.1）|
| **ClawHub Skill → Atomic Stealer** | 插件市场无信任根 | `SECURITY.md` 红线：未签名的插件进入隔离 | 插件市场（§ 4.3）|
| **Moltbook 后端泄漏** | 第三方 Supabase 后端以明文持有令牌 | `SECURITY.md` 红线：第三方后端不以明文持有代理状态 | 未来的云同步；当前未实现 |
| **Skill 安装即执行** | Skill 在安装时即运行 | `SECURITY.md` 红线：安装 ≠ 授权 | 插件市场（§ 4.3）|
| **138+ CVE 数量** | 集成蔓延而无逐集成的安全 | `SECURITY.md § 决策规则`：每一个新主体 / 边界在 PR 中说明理由 | 流程规则——适用于每一个 PR |

## 6. 从 `SECURITY.md` 默认到本文档的映射

便于溯源：

| `SECURITY.md` 默认 | 落地于 |
|---|---|
| 默认拒绝 | §§ 4.2（closed）、4.3、4.4（closed）、4.7、4.11（closed）|
| 高影响动作逐次授权 | §§ 4.2（closed）、4.3、4.7 |
| 最小权限 | §§ 4.4（closed）、4.8（closed）|
| 默认隔离 | §§ 4.2、4.5 |
| 构建产物中不嵌入秘密 | § 4.8（closed）|
| 可审计 | 所有章节——任何特权动作 |
| 用户权威具有主权性（中止控制）| § 4.7（closed）|
| AI 是协作者（拒绝为一等结果）| § 4.13（closed）、§ 4.6 |
| 导出接收器带权限 / 白名单 | § 4.1（closed）|
| 插件工具不在首次调用时自动运行 | § 4.3 |
| 不依赖第三方特权 binder | § 4.4（closed）|
| 订阅 OAuth 状态留在 proot 环境 | § 4.5 |
| APK 不嵌入秘密 | § 4.8（closed）|
| 安全路径无回退 | 所有章节 |
| 无环回豁免 | § 5 ClawJacked、§ 4.11（closed）|
| 令牌铸造不扩大作用域 | § 5 CVE-2026-32922 |
| 配置端点经过鉴权 / 签名 | § 5 CVE-2026-25593 |
| 无自动配对 | § 5 ClawJacked |
| 未签名插件进入隔离 | § 4.3、§ 5 ClawHub |
| 第三方后端非明文 | § 5 Moltbook |
| 遥测按事件选择加入 | § 4.12（closed）|
| 中止控制用户可达 | § 4.7（closed）|

## 7. 维护

当 PR 触碰 §§ 4.x 或 5 中的某一行时，该 PR 更新该行的 **status** 字段与 **location** 漂移。某行从 "open" 到 "closed" 需要：

- 防御实现位于所引用的位置。
- `AUDIT_PLAN.md` 中存在一条说明该关闭如何验证的条目（测试、人工检查或发布检查项）。
- PR 的 `SECURITY.md § 决策规则` 第 6 问回答中总结此变更。

某行从 "closed" 退回 "open" 是一次回归，应在 `AUDIT_PLAN.md` 中留下事件记录。

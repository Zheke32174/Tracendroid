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
| `ScriptExecutionReceiver` | `com.ai.assistance.operit.EXECUTE_JS` | 任何已安装应用都可提交 JS 在应用进程内 QuickJS 执行 | open |
| `ToolPkgDebugInstallReceiver` | `DEBUG_INSTALL_TOOLPKG` | 外部应用安装工具包 | closed（通过 `app/src/release/AndroidManifest.xml` 从 release 变体中排除）|
| `PackageDebugRefreshReceiver` | `DEBUG_REFRESH_PACKAGES` | 外部触发强制刷新插件 | closed（从 release 变体中排除）|
| `ToolPkgComposeDslDebugDumpReceiver` | `DUMP_COMPOSE_DSL_UI` | 外部触发 UI 树转储 | closed（从 release 变体中排除）|
| `ExternalChatReceiver` | `EXTERNAL_CHAT` | 外部应用发起聊天会话 | open——需要发送方白名单 |
| `WorkflowTaskerReceiver` | `TRIGGER_WORKFLOW`、`FIRE_SETTING` | 外部应用触发工作流自动化 | open——需要发送方白名单或签名级权限 |
| `WorkflowBootReceiver` | `BOOT_COMPLETED` | 仅系统触发（可接受）| closed（仅系统）|
| `VoiceAssistantWidgetReceiver`、`ToolPkgDesktopWidgetReceiver` | `APPWIDGET_UPDATE` | 仅系统触发（可接受）| closed（仅系统）|

**规则。** 上表中状态为 "open" 的每一条获得一个签名级权限，挂接到我方发布密钥（调试 / 内部通道）或合法调用方的发行方（Workflow 接收器对应 Tasker），并通过 build-type 专用清单从 release 构建中移除调试接收器。状态为 "scheduled for removal" 的条目在所引用的实现 PR 中被删除。

**位置。** `app/src/main/AndroidManifest.xml`（main 变体）与 `app/src/release/AndroidManifest.xml`（release 只读覆盖，剥离调试接收器）。调试构建保留所有接收器以用于内部工具。`nightly` 构建类型通过在 `app/build.gradle.kts` 中已声明的 `matchingFallbacks=[release]` 继承 release 覆盖。

### 4.2 进程内 JS 沙箱（QuickJS）

**发现。** `:quickjs` 模块包装 QuickJS；JS 插件在应用进程内执行。集成点在 `core/tools/javascript/`。JS 可调用的工具通过 `ToolRegistration` 注册（`core/tools/ToolRegistration.kt`，99 kB——很宽）。当插件 JS 调用工具时，复用了 AI 自身工具调用所用的同一个 `AIToolHandler` 调度（`core/tools/AIToolHandler.kt`）。

**风险。** 一旦插件被从隔离区升级为活跃，便继承了与 AI 同等的执行特权。同一会话内，AI 发起的调用与 JS 发起的调用之间没有按调用区分的门控。

**规则。**
- 每次 JS 发起的工具调用都归属到当前在 QuickJS 线程上执行脚本的插件。桥接层为调用打上插件 id；进程内门控（`JsPluginGate`）在分派给 `AIToolHandler` 前查询 (pluginId × capability class) 授权表。默认拒绝：插件初次安装时不持有任何授权，所有调用返回结构化错误，说明哪一对 (plugin, capability) 需要用户批准。
- 能力类是显式枚举而非模式匹配：METADATA / FILE_READ / FILE_WRITE / SHELL / NETWORK / SYSTEM_READ / SYSTEM_WRITE / UI_AUTOMATION / CHAT_READ / CHAT_WRITE / UNCLASSIFIED。未分类的工具名落到 UNCLASSIFIED，门控视为最受限（拒绝）。每个工具的分类是一项安全决定——新工具落地时必须显式更新分类器。
- 审计：每次受门控的调用都向有界环（256 条）写入 `AuditEvent(pluginId, capability, toolType, toolName, decision, timestamp)`，通过 `JsPluginGate.recentAudit()` 暴露给（尚未落地的）设置 UI。
- AI 发起的工具调用直接走 `AIToolHandler`，不经过 JS 桥；本轮中不受 `JsPluginGate` 门控。并行的 AI 侧门控是 § 4.2 的部分后续，与设置 UI 一并跟踪。
- MCP server 的工具调用走 `MCPBridgeClient`，本门控不涵盖；归 § 4.3。
- 授权存储在 v1 中是内存态。基于 DataStore 的持久化 + 设置 UI（浏览审计、按 (plugin, capability) 授权/拒绝）在后续实现 PR 中一并落地。

**状态。** partial——JS 发起的调用已受门控并审计；AI 发起的调用与设置 UI 作为后续跟踪。

**位置。** `app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsPluginGate.kt`、`JsCapabilityClassifier.kt`、`JsNativeInterfaceDelegates.kt`、`JsEngine.kt`。`core/tools/AIToolHandler.kt` 是后续要门控的 AI 侧接触面。

### 4.3 插件市场（MCP / Skill / ToolPkg）

**发现。** 共存三种插件体系：MCP 服务器（`core/tools/mcp/`）、Skill 捆绑包（`core/tools/skill/`）、ToolPkg 包（`core/tools/packTool/`）。APK 内置的示例包括 `super_admin.js`、`system_tools.js`、`linux_ssh`、`remote_operit`、`apktool`、`qqbot`。`packages_whitelist.txt` 控制构建期内置哪些示例——有用，但不是签名方案。

**风险。** OpenClaw 的 ClawHub 供应链事件（1000+ 个分发 macOS Stealer 的恶意 Skill）是“安装即信任”模式失败的教科书案例。Operit 内置的某些插件（`super_admin.js`）仅凭名字就值得审查。

**规则。**
- 每个插件包携带发行方签名。未签名的包在安装时进入隔离区；升级需要明确的用户动作，审计日志记录。
- 插件声明的每个工具在清单中声明能力类别（file-read / file-write / shell / network / SMS 等）。对某工具的首次调用呈现一次性提示；提示中标明插件与能力类别。授权是逐工具、逐工具可撤销。
- 插件不能安装另一个插件。
- 签名信任根的问题（谁信任谁）开放中，见 `AUDIT_PLAN.md § 插件签名方案`。

**状态。** design。

**位置。** `app/src/main/java/com/ai/assistance/operit/core/tools/mcp/`、`core/tools/packTool/`、`core/tools/skill/`。仓库根的 `packages_whitelist.txt` 文件涉及构建期内置决策，但不涉及运行期信任。

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

**规则。** 按 `SECURITY.md`：proot 环境是持久化边界。Android 侧的访问是只读、按操作限定作用域、审计日志记录的。令牌轮换操作（在 CLI 自身之外发起时）不扩大作用域（CVE-2026-32922 教训）。

**状态。** design——依赖 shell 重建。

**位置。** 见 `SHELL_REBUILD.md`。

### 4.6 AI 输出作为受校验的通道

**发现。** 聊天渲染流水线（`ui/features/chat/`，含 markdown / HTML / LaTeX / Mermaid 渲染器；`app/build.gradle.kts` 引用 `jlatexmath`、`renderx`、`androidsvg`）处理 AI 输出。按 `SECURITY.md § 信任态势`，AI 输出作为通道被校验——不是因为协作者本身不可信，而是因为其推理的输入侧（提示词注入、上下文污染）对对手可达。HTML 块预览（v1.8.1 发版说明中提到）尤为值得关注。

**风险。** 已污染输入通道（投毒的上下文、注入的提示、被反馈到对话中的恶意工具结果）的对手可以塑形 AI 输出，使其嵌入对手控制的 HTML、链接或工具调用序列。问题不在协作者的推理；问题在通道。

**规则。**
- 任何渲染路径都不执行脚本。HTML 块在沙箱化的 WebView 中渲染，配置 `setJavaScriptEnabled(false)`、`setAllowFileAccess(false)`、`setAllowContentAccess(false)`。SVG 通过 `androidsvg` 渲染（本就是向量解析器，无脚本）。
- 从 AI 输出中抽取的工具调用始终经过逐次门控（§ 4.2 规则对所有调用方一律适用）。
- Markdown 链接目标按 scheme 白名单；剥离 `intent://` 与 `javascript:` URL。

**状态。** open——需要对每个渲染接触面进行审计。

**位置。** `app/src/main/java/com/ai/assistance/operit/ui/features/chat/`（渲染器）、`api/chat/`（解析）。

### 4.7 手机作执行器

**发现。** 应用持有可对手机产生破坏性或可见副作用的权限：`SEND_SMS`、`READ_SMS`、`CALL_PHONE`、`REQUEST_INSTALL_PACKAGES`、`WRITE_SETTINGS`、`MANAGE_EXTERNAL_STORAGE`、`BIND_VOICE_INTERACTION`、`BIND_NOTIFICATION_LISTENER_SERVICE`、AccessibilityService、MediaProjection 前台服务。在 § 4.4 清理后，AccessibilityService 是唯一的特权自动化通道。

**规则。**
- 任何 AI 会话起始时不默认拥有执行器能力。会话以零手机端能力开始；用户通过可见提示按会话授权。
- 当前会话存在执行器能力时，常驻指示器（状态栏 / 悬浮点）始终可见。隐藏指示器不是用户可配置的选项。
- 中止控制（按 `SECURITY.md` 原则 7）停止任何进行中的行动——AI 正在执行的调用、AI 启动的 proot 进程、与会话绑定的前台服务——并撤销会话的执行器能力。中止控制可从常驻指示器一键触达。
- 中止动作记录于审计日志。日志保留中止当刻 AI 的推理状态，而非仅记录已执行的动作。

**状态。** open——中止控制 UI 是新增；按会话授权流程需要设计。

**位置。** `AndroidManifest.xml` 中的权限；`core/tools/defaultTool/` 各处的能力检查；`services/core/` 中的会话管理。

### 4.8 构建期秘密

**发现（已解决）。** 之前 `app/build.gradle.kts` 第 74–75 行声明了 `GITHUB_CLIENT_ID` 与 `GITHUB_CLIENT_SECRET` 作为 BuildConfig 字段，从 `local.properties` 读入。`_SECRET` 值如被填入，便进入 APK，被任何反编译者还原。

**规则。** 移动端 OAuth 采用 PKCE（RFC 7636）。本应用使用的 GitHub OAuth 流程（按 `docs/BUILDING.md`）目标深链 `operit://github-oauth-callback`，仅由 PKCE 提供服务。

**状态。** closed——PKCE 迁移在 `9762a263` + `407241b2` 中落地。`_SECRET` BuildConfig 字段已移除；`GitHubAuthPreferences.GITHUB_CLIENT_SECRET` 常量已移除；`GitHubApiService.getAccessToken` 现在发送 `code_verifier` 代替 `client_secret`。见 [`docs/OAUTH_PKCE_MIGRATION.md`](./OAUTH_PKCE_MIGRATION.md)。

**位置。** `app/build.gradle.kts`（BuildConfig 部分）；`data/preferences/GitHubAuthPreferences.kt`（companion 与 `getAuthorizationUrl`）；`data/preferences/PkceCodeGenerator.kt`（新增的 RFC 7636 辅助类）；`data/api/GitHubApiService.kt::getAccessToken`；`ui/features/github/GitHubOAuthCoordinator.kt`；`ui/features/github/GitHubLoginWebViewDialog.kt`。

### 4.9 凭据存储

**发现。** 当前许多凭据存于 DataStore preferences（提供方 API key、工作区绑定的 SSH 密钥、§ 4.8 中引入的待决 PKCE code_verifier）。DataStore 在磁盘上是明文的，除非由加密层包装。应用已依赖 `androidx.security:security-crypto`（基于 Tink 的 `EncryptedSharedPreferences`）。

**规则。** 每一项凭据——提供方 API key、SSH 密钥、工作区绑定、待决 PKCE code_verifier、任何具有令牌性质的值——迁移至加密存储。迁移一次性、幂等，每条迁移记录产生一条审计日志条目。

**状态。** open——迁移方案待制定。

**位置。** `app/src/main/java/com/ai/assistance/operit/data/`、`provider/` 中的 keystore 接线（路径在审计时确定）。

### 4.10 Documents provider

**发现。** `WorkspaceDocumentsProvider` 与 `MemoryDocumentsProvider` 在 `AndroidManifest.xml` 中以 `android:exported="true"` 且要求 `MANAGE_DOCUMENTS` 权限的方式声明。Android 对 `MANAGE_DOCUMENTS` 的要求由系统强制；但每个 provider 内部的*路径解析*逻辑决定了其他应用究竟能读到什么。

**规则。** 审查 provider 实现，避免意外的跨应用文件访问。具体而言：未取得用户明确授权的应用不可访问工作区绑定目录；调用方未从我方接收的 memory-document URI 不可被枚举。

**状态。** open——审计待启动。

**位置。** `provider/WorkspaceDocumentsProvider`、`provider/MemoryDocumentsProvider`。

### 4.11 明文流量

**发现（已解决）。** 之前 `app/src/main/res/xml/network_security_config.xml` 在 `base-config` 上设置 `cleartextTrafficPermitted="true"`（全局），并在基础配置中信任 `<certificates src="user"/>`（任意用户安装的 CA 都可 MITM 应用流量）。与清单中 `android:usesCleartextTraffic="true"` 结合，应用实际上没有 TLS 强制也没有设备级 CA 注入的防御（企业 MDM、恶意侧载等）。`5e0fcb1f` 的后续提交审计并收紧。

**规则。** 明文默认拒绝：`base-config cleartextTrafficPermitted="false"`，仅信任系统 CA。明文仅限于列名的环回来源（`127.0.0.1`、`localhost`），服务于设备端开发与计划中的 Android↔proot 桥。用户 CA 仅在 `<debug-overrides>`（调试构建，用于 Charles / mitmproxy 代理调试）下被信任；release 变体拒绝用户 CA。

**状态。** closed——见 `app/src/main/res/xml/network_security_config.xml`。

**已知权衡。** 依赖明文 HTTP-to-LAN 的功能（例如 `http://192.168.x.x:1234` 上的 LMStudio、内网主机上的 MCP 服务器）在 release 构建中失效，除非主机提供 HTTPS 或用户加入命名白名单条目。按 `AGENTS.md` 无回退规则，全局明文默认不恢复；逐部署白名单条目在明确主机已知时落地。

**位置。** `app/src/main/res/xml/network_security_config.xml`。清单中的 `android:usesCleartextTraffic="true"` 属性现已被 NSC 覆盖，并列为后续清单清理提交的移除对象。

### 4.12 遥测、分析、崩溃报告

**发现。** `AndroidManifest.xml` 中明确禁用了 Firebase ML 分析（`firebase_ml_collection_enabled = false`）。`ui/error/CrashReportActivity` 是一个 `CrashReportActivity`，运行在 `:crash` 进程。没有书面遥测政策。

**规则。** 按 `SECURITY.md`：不存在聚合的后台遥测。崩溃报告按事件需要用户选择加入，传输前向用户展示报告内容。用户可拒绝；拒绝不会进入降级模式。

**状态。** open——需要政策 + UI 工作。

**位置。** `app/src/main/java/com/ai/assistance/operit/ui/error/CrashReportActivity.kt`、清单 meta-data。

### 4.13 AI 协作者的拒绝

**发现。** 按 `SECURITY.md` 原则 8，AI 对某项操作的拒绝是一等结果——不是要抑制的错误。项目不实现绕过模型拒绝的路径。

**规则。**
- 拒绝在 UI 中与 AI 给出的理由（当其提供时）以及一项分类（如能力拒绝、安全拒绝、需要澄清）一同呈现。分类是信息性的，不是门控——应用不会因分类而改变行为，但审计日志记录分类。
- 用户在拒绝之后获得选项：重新表述请求、放弃该操作，或——仅通过明确的重新发起——进行一次新的轮次。不存在自动重试路径。
- 拒绝与拒绝当刻的 AI 推理状态一并记录于审计日志。

**状态。** design——依赖 `AUDIT_PLAN.md § 1.7` 中定义、并在 `AGENT_CORE.md § 拒绝通道` 中曝露的 `AgentOutcome` 数据模型。

**位置。** 即将引入。可能位于 `app/src/main/java/com/ai/assistance/operit/api/chat/` 与 `app/src/main/java/com/ai/assistance/operit/services/core/`（聊天协调层）。

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
| 默认拒绝 | §§ 4.2（partial）、4.3、4.4（closed）、4.7、4.11（closed）|
| 高影响动作逐次授权 | §§ 4.2（partial）、4.3、4.7 |
| 最小权限 | §§ 4.4（closed）、4.8（closed）|
| 默认隔离 | §§ 4.2、4.5 |
| 构建产物中不嵌入秘密 | § 4.8（closed）|
| 可审计 | 所有章节——任何特权动作 |
| 用户权威具有主权性（中止控制）| § 4.7 |
| AI 是协作者（拒绝为一等结果）| § 4.13、§ 4.6 |
| 导出接收器带权限 / 白名单 | § 4.1 |
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
| 遥测按事件选择加入 | § 4.12 |
| 中止控制用户可达 | § 4.7 |

## 7. 维护

当 PR 触碰 §§ 4.x 或 5 中的某一行时，该 PR 更新该行的 **status** 字段与 **location** 漂移。某行从 "open" 到 "closed" 需要：

- 防御实现位于所引用的位置。
- `AUDIT_PLAN.md` 中存在一条说明该关闭如何验证的条目（测试、人工检查或发布检查项）。
- PR 的 `SECURITY.md § 决策规则` 第 6 问回答中总结此变更。

某行从 "closed" 退回 "open" 是一次回归，应在 `AUDIT_PLAN.md` 中留下事件记录。

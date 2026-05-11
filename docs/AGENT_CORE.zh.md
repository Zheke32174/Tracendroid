# AGENT_CORE.md（中文镜像）

> 每个 AI 后端实现的接缝。定义了一个轮次如何从用户输入出发，穿越 AI（无论身处何处），通过工具调用返回 Android 侧，伴随流式输出、结构化拒绝、用户主权中止，以及在整个链路上保留 AI 推理过程的审计日志。与 [`SECURITY.md`](./SECURITY.md)、[`THREAT_MODEL.md`](./THREAT_MODEL.md)、[`AUDIT_PLAN.md`](./AUDIT_PLAN.md)、[`SHELL_REBUILD.md`](./SHELL_REBUILD.md) 配套使用。英文原本见 [`AGENT_CORE.md`](./AGENT_CORE.md)。

## 为何需要此文档

没有统一的内部契约，每个后端（本地 llama、本地 MNN、远程 OpenAI、远程 Anthropic、通过 proot 的 codex / gemini-cli / claude-code 等）都会将自己的接触面带入应用的其余部分。UI 会按后端类型分岁。工具调度会按后端类型分岁。审计日志会按后端类型分岁。这似 openclaw 的失败模式：每个集成都是自己的接触面。

agent-core 是接缝：一个 Kotlin 接口、一种包裹形状、一个流式协议。每个后端从自己的线上格式写一个适配器到 core。UI、工具调度器、审计日志器、中止机制从不看到线上格式。它们只看到 core。

## 范围内的后端

| 后端 | 线上格式 | 运行位置 | 掌握鉴权 |
|---|---|---|---|
| 本地 llama.cpp | JNI 到 native | 应用进程 | 不适用 |
| 本地 MNN | JNI 到 native | 应用进程 | 不适用 |
| 远程 API：OpenAI 兼容 | HTTP / SSE | 应用进程（网络）| EncryptedSharedPreferences 中的 API key |
| 远程 API：Anthropic Messages | HTTP / SSE | 应用进程（网络）| EncryptedSharedPreferences 中的 API key |
| 远程 API：Google Vertex | HTTP / SSE + OAuth2 | 应用进程（网络）| Google 账户 OAuth |
| 远程 API：Azure OpenAI | HTTP / SSE + Entra ID | 应用进程（网络）| Entra OAuth |
| 订阅 CLI：codex | 经 proot 的 Unix socket 上的 stdio | proot 环境 | CLI 掌握的 OAuth |
| 订阅 CLI：gemini-cli | 经 proot 的 Unix socket 上的 stdio | proot 环境 | CLI 掌握的 OAuth |
| 订阅 CLI：claude-code | 经 proot 的 Unix socket 上的 stdio | proot 环境 | CLI 掌握的 OAuth |
| 订阅 CLI：aider / cline / continue.dev | 经 proot 的 Unix socket 上的 stdio | proot 环境 | CLI 掌握的 OAuth |
| MCP 服务器 | stdio 或 HTTP | proot 环境或远程 | 逐服务器 |

每个后端实现（下文的）`AgentBackend`。适配器位于逐后端 Gradle 模块，按构建变体可启用或禁用。

## 核心抽象

Kotlin 草图。名字是占位符；形状才是承诺。

```kotlin
interface AgentBackend {
    /** Describes what this backend can do (streaming, tools, vision, reasoning, etc.) so the UI can adapt. */
    val capabilities: AgentCapabilities

    /** Establishes a turn. Returns a flow of TurnEvents until the turn ends. */
    suspend fun streamTurn(
        input: TurnInput,
        toolResults: Flow<ToolResult>,
        sessionState: SessionState
    ): Flow<TurnEvent>

    /** Halts an in-flight turn. Backend-specific best-effort cancellation. */
    suspend fun halt(reason: HaltReason)

    /** Returns the backend's reasoning trace for the most recent turn, if available. */
    fun reasoningTrace(): ReasoningTrace?
}

sealed class TurnEvent {
    data class TextChunk(val text: String) : TurnEvent()
    data class ReasoningChunk(val text: String) : TurnEvent()
    data class ToolCall(val id: String, val name: String, val args: JsonObject, val capability: CapabilityClass) : TurnEvent()
    data class ToolResultEcho(val id: String, val result: JsonElement) : TurnEvent()
    data class Decline(val reason: String, val classification: DeclineClass, val suggestedAlternatives: List<String>?) : TurnEvent()
    data class HaltedByUser(val haltedAt: Instant) : TurnEvent()
    data class Completed(val turnId: String, val tokenUsage: TokenUsage?) : TurnEvent()
    data class Failed(val error: AgentError) : TurnEvent()
}
```

顺序重要：事件按后端产生它们的顺序到达。UI 订阅；审计日志器订阅；工具调度器订阅 `ToolCall` 事件并通过 `toolResults` 流发出 `ToolResult` 值。

## 轮次包裹

`TurnInput` 携带：
- 对话历史（系统提示词、以往轮次、以往工具调用与结果——后端无关的表示）。
- 用户的新消息（文本 + 可选附件）。
- 本轮次可用的工具集合（携带 `AUDIT_PLAN § 1.3` 中的能力类别）。
- 逐轮次标志：temperature、max output tokens、vision-enabled、reasoning-enabled。

`SessionState` 携带：
- 会话身份。
- 本会话的能力授权（按 `THREAT_MODEL § 4.4` 与 § 4.7）。
- 绑到本会话的审计日志追加器。
- 中止令牌（取消信号）。

## 流式模型

轮次的 `Flow<TurnEvent>` 可以以任何顺序交错事件。具体模式：

- 纯文本轮次发出一系列 `TextChunk`，然后 `Completed`。
- 工具调用轮次可能发出 `TextChunk`，然后一个 `ToolCall`，暂停到分发器执行并通过 `toolResults` 流发出 `ToolResult` 后，继续更多文本或另一个工具调用，最后 `Completed`。
- 支持推理的后端（带扩展思考的 Anthropic；带思考令牌的本地模型；暴露推理的订阅 CLI）发出 `ReasoningChunk` 与文本并行。UI 可以在折叠面板中渲染推理；审计日志原文保留。
- 拒绝发出 `Decline`，流结束但不发 `Completed`。不自动重试。
- 用户中止发出 `HaltedByUser`；流结束。
- 后端失败发出 `Failed`，携带结构化 `AgentError`。没有陰魂错误串。

## 工具调用分发

当 `ToolCall` 事件到达时，它流过以下管道：

1. **能力检查。** 事件上的 `capability` 字段命名 `AUDIT_PLAN § 1.3` 中的一个类别。分发器检查当前会话的授权。若缺失：向用户提示（按 `SECURITY.md` “高影响动作逐次授权”）。授权被赋予该特定工具；后续调用在会话期间复用该授权。可撤销。
2. **插件来源检查。** 若工具的实现位于插件中，插件的隔离状态被验证。隔离区插件 → 调用被拒；拒绝写入审计日志。
3. **中止检查。** 若中止正在进行，短路；返回结构化中止结果。
4. **执行。**
   - 对于 Android 侧工具（accessibility、intent、telephony、install、screen）：通过标准 SDK 路径击中 Android 系统。**AccessibilityService 是 UI 控制唯一的特权通道**（按 `THREAT_MODEL § 4.4`——Shizuku 与 Shower 被移除）。
   - 对于 proot 绑定工具（`/workspace` 内的文件操作、在 proot 中执行的网络调用、proot 环境内的包管理）：分发器按 `SHELL_REBUILD § IPC 协议` 跨越 IPC 桥。
5. **审计日志。** 每次调用写入：工具名、能力类别、插件或核心来源、调用当刻 AI 的推理状态（以便用户看到 AI 为什么发起调用）、结果或失败。
6. **结果发出。** `ToolResult` 通过 `toolResults` 流发送回后端。

AI 发起的路径与插件 JS 发起的路径注册的工具都经过同一个分发器。不存在特权跳过路径。（见 `THREAT_MODEL § 4.2`。）

## 拒绝通道

后端可以在轮次中的任何点发出 `Decline`。拒绝携带：

- AI 给出的理由（原文文本）。
- 一项分类：`CapabilityRefusal`、`SafetyRefusal`、`NeedsClarification`、`ContextLimit`、`Other`。分类是信息性的；应用不会因它改变行为。
- 可选的建议替代方案（AI 自身的建议，如有）。

UI 与拒绝一同呈现理由。用户看到选项：重新表述请求、放弃该操作、或从头重新发起轮次。不存在 `Decline → 自动重试` 路径。（`SECURITY.md` 原则 8。）

## 中止通道

用户发起的中止通过 `AgentBackend.halt(HaltReason)` 进入，并向外传播：

- 后端取消其当前进行中的操作（HTTP 请求、JNI 调用、stdio 管道）。
- 进行中的工具分发看到中止标志，发出中止结果而不是执行工具主体。
- 会话启动的任何 proot 进程接收 `SIGTERM`（2 秒后发送 `SIGKILL`，按 `AUDIT_PLAN § 1.8`）。
- 会话的前台服务要么结束（若中止为“结束会话”），要么以闲置状态保持存活（若中止为“停止这个行动链但保留会话”）。
- 作为最后一个 `TurnEvent` 发出 `HaltedByUser`。
- 中止在保留中止当刻 AI 推理状态的情况下写入审计日志。

中止是主权的——后端不拒绝、不延迟、不绕过。（`SECURITY.md` 原则 7。）

## 推理保留

按 `SECURITY.md` 原则 8，AI 推理在审计日志中与所采取的行动一同保留。具体而言：

- `ReasoningChunk` 事件累积为逐轮次的推理迹迹。
- 迹迹按时间戳与每个 `ToolCall` 事件关联（工具调用的审计日志条目携带调用当刻的推理状态，而非仅仅一个轮次级别的推理块）。
- 迹迹被包含在 `HaltedByUser` 的审计日志条目中，以便用户能看到中止时 AI 在思考什么。
- 当后端不暴露推理时（大多数订阅 CLI 隐藏推理；OpenAI 的隐藏思考链不可访问），该后端的迹迹为空。审计日志诚实记录缺席，而非虑造填充。

## 会话生命周期

会话是持有以下内容的单元：
- 对话历史。
- 逐工具能力授权。
- 前台服务绑定（活跃时）。
- 执行器能力授权（按会话，按 `THREAT_MODEL § 4.7`）。

状态：`Idle`、`Active`、`Backgrounded`、`Halted`、`Ended`。

转换：
- `Idle → Active`：用户启动一个轮次。
- `Active → Backgrounded`：应用进入后台时轮次进行中或会话持有活执行器授权。前台服务启动。常驻指示器出现。
- `Backgrounded → Active`：用户返回应用。
- `Active 或 Backgrounded → Halted`：用户调用中止。
- `Halted → Idle`：用户取消中止状态；对话可以继续。
- `Active 或 Backgrounded 或 Halted → Ended`：用户结束会话。所有授权被撤销，前台服务停止，审计日志完结。

## 后台操作

当会话有进行中的轮次或持有执行器授权时，应用启动一个前台服务。该服务：

- 仅当代理实际运行某个操作时持有 `WAKE_LOCK`；在操作之间释放。
- 发布一个常驻通知。通知唯一的交互元素是 `Halt`。点击它调用中止通道。
- 声明与最高影响活跃授权匹配的 `foregroundServiceType`：屏幕截取授权时为 `mediaProjection`，语音活跃时为 `microphone`，`dataSync` 作为 AI 启动的 proot 进程必须的后备，`specialUse` 用于执行器会话。
- 在屏幕关闭与锁定状态下存活。

在屏幕关闭或锁定时可运作的内容：
- 网络操作（订阅 CLI 推理、远程 API 调用）。
- `/workspace` 内的文件操作。
- 本地模型推理（CPU / NPU 与屏幕状态无关）。
- 通过 `AlarmManager` 的调度动作（权限已在）。

需要屏幕亮起的内容：
- AccessibilityService 手势注入——Android 要求屏幕亮起以进行大多数输入事件。
- MediaProjection 屏幕截取——技术上可以在屏幕关闭时工作，但在许多设备上产生黑帧。
- 触摸驱动的 UI 自动化。

当 AI 计划某项需要屏幕亮起但屏幕关闭的动作时，agent core 有一个 `requestScreenWake` 能力，它发布通知：“AI 想做 X——唯醒屏幕？”用户可以批准、拒绝或调度到下一次交互。代理不会在没有该用户可见步骤的情况下单方面唯醒屏幕。（`SECURITY.md` 原则 7——用户主权。）

## 手机作执行器集成

被分类为 `accessibility`、`screen`、`telephony`、`install` 的工具调用（来自 `AUDIT_PLAN § 1.3`）通过以下路径击中 Android 系统：

- **Accessibility**：`AccessibilityService`（已在清单中声明）。UI 树读取作为 `ToolResult` 负载返回；手势通过 `AccessibilityService.dispatchGesture` 派发。这是 UI 控制唯一的特权通道——Shizuku 与 Shower 被移除（`THREAT_MODEL § 4.4`）。
- **Screen**：`MediaProjection` 通过 `ScreenCaptureService`。MediaProjection 授权提示由操作系统控制；代理不能绕过。要么用户批准提示，要么不发生屏幕截取。
- **Telephony**：`SmsManager`、`TelecomManager`。SMS 发送需 `SEND_SMS`（当前为安装时授予；v2 可能切换到逐次调用运行时权限）。发送逐次门控。
- **Install**：`PackageInstaller` 使用操作系统控制的安装提示。即使一个工具请求安装，用户也看到系统的“安装此应用？”对话。不提供 `pm install -r` 式旁路（Shizuku 本可提供）——这是设计选择。

每个路径在注册表中实现为一个工具。每次调用经过分发器的逐次门控。每次调用写入审计日志。

代理从不直接调用这些。它发出一个携带适当能力的 `ToolCall` 事件；分发器决定是否执行、提示什么、记录什么。

## 视觉语言感知（预留能力，v2）

Mobile-Agent 与 DroidClaw（见 § 先辈工作）证明了视觉语言模型在屏幕上定位产生比单独 UI 树解析更鲁棒的 UI 自动化，尤其是在 UI 树稀疏的应用上（如没有语义内容描述的 Compose-only 应用）。

协议预留一个 `screen_perception` 能力类别（在 `AUDIT_PLAN § 1.3` 中）与 `ToolCall` 一同发出的 `PerceptionRequest` 包裹，使一个未来后端能够发出：

- `accessibility.tree`（当前路径）——获取结构化 UI 树。
- `screen.image`——获取一张屏幕截图供 VLM 定位。
- `vlm.ground(<image>, <prompt>)`——问一个视觉模型某事物在图像上的位置。

v1 仅发布 accessibility-tree 路径。v2 增加 VLM 定位路径。协议形状在 v1 与 v2 之间不变。

## 多代理协作（会话形状，v2）

Mobile-Agent-E 与类似架构使用多个专业代理一同推理——一个规划者、一个执行者、一个评论者。这与 `SECURITY.md` 原则 8 一致：多个涌现心智协作。

agent-core 的会话形状支持这一点而无需协议变更：一个 `Session` 可以托管多个 `AgentBackend` 实例。每个后端的轮次事件流入共享事件总线；分发器路由工具调用；每个后端的推理迹迹在审计日志中分别保留。

v1 发布单后端会话。v2 支持多后端形状。现有会话 UX 适配。

## 先辈工作

| 项目 | 我们借用的 | 我们不采用的 |
|---|---|---|
| **x-plug/Mobile-Agent**（8.7k stars，NeurIPS 2025）| 仅 Accessibility 特权路径；视觉语言定位架构；多代理协作形状；MCP 为一等公民 | 特定 VLM 选择（我们保持模型中立）；运行时直接使用 ADB（我们运行时没有 ADB）|
| **unitedbyai/DroidClaw**（1.4k stars，2026 年 2 月）| 28 动作分类作为 `AUDIT_PLAN § 1.3` 能力类别的参考；多步骤技能；确定性流执行 | 基于 Tailscale 的远程控制（v1 不在范围）；USB 调试要求（我们不要求开发者模式）|
| **SenninTadd/agentX** | （Mobile-Agent 变体——与 x-plug 重叠）| 不适用 |

这些项目被引用为正面示例（我们愿意与之收敛的架构）。`THREAT_MODEL.md` 继续引用 **openclaw** 作为反面示例——要避免什么。

## 交叉引用

- 面向 `THREAT_MODEL.md § 4.7`（手机作执行器）以便在中止控制与逐会话授权落地后从 `open` 转入 `closed`。
- 解决 `AUDIT_PLAN.md § 1.7`（AI 拒绝作为一等结果）的形状：`TurnEvent` 中的 `Decline` 事件是数据模型。
- 解决 `AUDIT_PLAN.md § 1.8`（中止控制：作用域与 UI）的形状：`AgentBackend` 上的 `halt()` 方法与前台服务中止按钮。
- 依赖 `SHELL_REBUILD.md § IPC 协议` 进行 proot 绑定工具分发。
- 依赖 `THREAT_MODEL.md § 4.4`（仅 AccessibilityService）以获得手机作执行器路径。
- 遵守 `SECURITY.md` 红线：拒绝上无回退模式、无自动配对、中止为主权、代码与 UI 中不使用“杀死 AI”术语。

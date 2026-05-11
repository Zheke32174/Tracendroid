# SHELL_REBUILD.md（中文镜像）

> proot 环境重建的范围与决策。当前的 `:terminal` 子模块（`AAswordman/OperitTerminalCore`）与其缺失的 blob 依赖被列为移除对象；本文档定义取代它们的方案。英文原本见 [`SHELL_REBUILD.md`](./SHELL_REBUILD.md)。

## 为何

当前 shell 子系统同时在两个层面上损坏：

1. **硬依赖于一个缺失的外部 blob。** `app/src/main/assets/subpack/.keep` 的字面内容是 `pls download from drive`。rootfs 位于上游项目维护的 Google Drive 文件夹中的 `subpack.zip`。缺失时，引导静默失败——用户体验到的就是“不能设置 shell”。
2. **信任依赖于一个不可读的子模块。** `:terminal` Gradle 模块指向 `AAswordman/OperitTerminalCore`。我们无法检查、无法对照 `SECURITY.md` 进行审计、无法在其中落实默认拒绝 / 中止控制 / 审计日志的要求。

两者都是长期重建的障碍。补丁路径（“从镜像拉 blob 的 Gradle 任务”）被项目的无回退规则拒绝，也被采取慢路径的决定拒绝。

## 本次重建替换的内容

- `:terminal` Gradle 子模块。
- `app/src/main/assets/subpack/` blob 依赖。
- `Terminal.kt::initialize()` 代理到子模块的所有调用点。
- 当前失败时什么都不显示的引导 UX。

## 本次重建保留的内容

- 面向用户的聊天体验（其本身不直接引用子模块）。
- `app/src/main/java/com/ai/assistance/operit/ui/features/toolbox/screens/` 中的终端工具箱 UI 页面。
- `shellexecutor` 页面（一次性 ADB 风格命令运行器，与 proot 环境不同）。

## 决策

### 发行版：Debian 12（bookworm）

解决 `AUDIT_PLAN.md § 1.9`。

| 候选 | 结果 |
|---|---|
| Ubuntu 24 LTS | 被否决。rootfs 较大（~200 MB），驲马较多（snap、遥测接口），PPA 诱惑较广（PPA 本身是一种信任风险）。|
| **Debian 12（bookworm）** | **采纳。** 更精简的稳定版，同样的 apt 生态，最长的维护记录，无遥测。|
| Alpine | 被否决。musl-libc 与 Python wheel 及许多 Node 包不兼容——与 codex / gemini-cli / claude-code 目标冲突。|

rootfs 体积目标：压缩后 ~80–100 MB，解压后 ~250–300 MB。订阅 CLI 与 Python / Node 工具在环境内按需安装，不进入基础镜像。

### 启动器：仅 proot

启动器是 proot。`chroot()` 需要 `CAP_SYS_CHROOT`，而该 capability 是 root 独占的。项目没有 root、不打算获取 root，且按 `THREAT_MODEL.md § 4.4` 也不使用 Shizuku 或 Shower。chroot 路径从 v1 范围中移除。

接受的权衡：

- **系统调用转换开销。** 在微基准中可测，但对于我们承载的 CLI 与开发工具不是阻塞性问题。订阅 CLI 是网络绑定的；本地模型运行时是计算绑定的，不重度穿越 proot 边界。
- **没有内核强制的隔离。** 威胁模型已考虑此点：proot 环境是*软*边界，不是沙箱。订阅令牌的机密性由 Android 侧加密（环境位于应用私有存储中）与 rootfs 内的 Linux 文件 ACL 来保护，而非由内核隔离。
- **比 chroot-with-init 所需要更多的 Android 侧工具：**
  - 前台服务架构以保持 proot 进程在应用进入后台时存活（见 `AGENT_CORE.md § 后台操作`）。
  - IPC 桥承担软边界的重量（见下方 § IPC 协议）。
  - 显式的生命周期处理——proot 不像 chroot-with-init 那样自动从 OOM kill 中恢复。会话恢复由 agent-core 层处理。

### rootfs 构建流水线

在 CI 中从 `debian/build.sh`（或 `Dockerfile.rootfs`）构建，该脚本针对 bookworm 的稳定镜像运行 `debootstrap`，堆叠我们的基础包集合，剥离不分发的语言包 / 文档 / man 页，并将结果打包为 `.tar.zst`（压缩比 gzip 更优，设备端解压迅速）。

产出：每个支持的 ABI 一份产物。v1 仅发布 `arm64-v8a`，与应用当前的 ABI 过滤器一致。构建可重复到足以在两次 CI 运行中逐字节验证一致——严格可重复构建（密封、源码钉定）是 v2 目标，非 v1。

### rootfs 托管与完整性

- **托管。** 同仓库其中一个的 GitHub Release 产物（v1 可能是 `zheke32174/tracendroid`；后期可迁至专用资产仓库）。每个 rootfs 版本一次发布；发布产物不可变。
- **身份。** 每个发布产物由项目发布密钥签名。设备端在解压前验证签名。未验证的签名以用户可见错误中止引导——不静默重试，不“表示同意仍然解压”。
- **钉定。** 应用嵌入其构建时针对的 rootfs 版本的预期 SHA-256。不匹配以引导中止。升级 rootfs 的方式是应用升级。
- **隐私。** 下载请求不携带 GitHub CDN 默认可见内容之外的遥测。事前、中、后都不汇报。

### 引导 UX

首次运行流程：

1. 用户打开终端功能（或任何需要环境的功能）。
2. 应用检查预期版本的 rootfs 是否已安装。如存在且签名有效，继续。否则：
3. 用户看到一个清晰的面板：即将发生什么、下载体积、文件来源、内部运行什么。
4. 用户确认。下载进行。进度可见。
5. 签名 + SHA-256 验证。失败以实际错误中止（不是“出了点问题”）。
6. 解压至 `Android/data/<package>/files/rootfs/`（应用私有存储；卸载后仅在用户选择备份时保留）。
7. 首启初始化运行——生成主机配置、设置 Unix socket 端点、打开一个 shell。
8. 后续启动跳过步骤 2–6。

该流程中没有任何“缺失则跳过”的路径。按 `AGENTS.md` 与 `SECURITY.md`，失败被呈现出来，而不是被掩盖。

### IPC 协议

解决 `AUDIT_PLAN.md § 1.5`。

**Unix 域 socket** 位于共享 bind-mount 中。Android 侧与 proot 侧都看到 socket 文件；Linux 文件 ACL 限定访问。无网络接触面、无环回暴露——`THREAT_MODEL.md § 5 ClawJacked` 的失败模式在该接触面不可达。

线上格式：长度前缀的 JSON 消息，每个包裹一次逻辑请求 / 响应。每个包裹携带：

- 单调递增的请求 id（用于响应匹配）。
- 来源标签（`user` / `ai-agent` / `plugin:<id>`），使 proot 侧知道是谁在调用。
- 能力声明（调用 `AUDIT_PLAN § 1.3` 中哪一类别）。
- 实际命令。

proot 侧在执行前强制能力类别（与 `AUDIT_PLAN § 1.3` 声明的集合同一）。Android 侧在发送前再次强制。纵深防御。

每次调用鉴权：每个连接由引导时铸造的会话秘钥鉴权，该秘钥在 Android 侧存于 `EncryptedSharedPreferences`，作为每个连接的首个帧出示。秘钥在应用升级时与用户发起的轮换时轮换。对任何调用方类别不豁免。

### 订阅 CLI 安装策略

重建**不在 rootfs 内置** codex、gemini-cli、claude-code、aider、cline 或 continue.dev。各 CLI：

- 通过自身发布的安装器或环境的包管理器按需安装。
- 在环境内部处理自己的 OAuth 流程。
- 在环境内持久化会话状态，按 `THREAT_MODEL.md § 4.5`。
- 按自身节奏更新（他们的更新通道，不是我们的）。

这让他们的安全更新随他们走，并使我们的构建流水线脱离供养第三方 CLI 的业务。

### 文件系统布局

rootfs 内部：

```
/                       Debian 根（引导后只读；由 rootfs 升级替换）
/home/operator/         用户主目录——聊天会话范围，不是用户个人的
/var/lib/operit/        应用管理的状态（逐会话配置、审计日志镜像）
/var/lib/operit/ipc/    Unix socket 位于此处
/opt/cli/               订阅 CLI 在用户选择加入时安装于此
/workspace              来自 Android/data/<package>/files/workspace/ 的 bind-mount——仅有这个路径跨会话持久化
```

该布局是 Android 侧与环境内任何运行代码之间的契约。插件、CLI、临时 shell 会话都看到同一布局下的同一位置。

### 从 `:terminal` 子模块的迁移

本重建跨多个 PR 落地，按以下顺序：

1. **rootfs 构建流水线 + 托管。** 仅内部产物；无应用端代码改动。
2. **启动器与 IPC 代码**作为并行模块（`:shell` 或类似名称——待定）。旧的 `:terminal` 继续存在。
3. **逐一切换入口点**从 `Terminal.kt` 到新模块。每个切换是可审查的 PR，更新 `THREAT_MODEL.md § 4.5` 状态。
4. **删除 `:terminal` 与 `subpack` 资产路径**，一旦无调用点引用。该提交彻底移除 Google Drive 依赖。
5. **从 `.gitmodules` 中移除 `terminal` git 子模块**。

按 `AGENTS.md`，这是逐步增加后跟随删除——无回退模式，不保留挂久的并行模式开关。

## 推到实现 PR 的待定项

- `debian/build.sh`（或 `Dockerfile.rootfs`）的具体内容。
- 基础包集合的具体清单（可能为：`bash`、`coreutils`、`procps`、`ca-certificates`、`curl`、`git`、`openssh-client`、`python3`、`nodejs`、`npm`——最终清单位于构建脚本的 PR 中）。
- 可重复构建加固（v2）。
- rootfs 增量更新而非完整重下（v2——初期用户在首次运行与版本更新时支付许多约 80 MB）。
- x86_64 ABI 支持，若/当模拟器驱动的开发成为优先项。

## 交叉引用

- 解决 `AUDIT_PLAN.md § 1.5`（proot ↔ Android IPC 协议）。
- 解决 `AUDIT_PLAN.md § 1.9`（发行版选择）。
- 面向 `THREAT_MODEL.md § 4.5`（proot 环境内订阅 OAuth）以便在实现落地后从 `design` 转入 `closed`。
- 遵守 `SECURITY.md` 红线：无环回豁免、无自动配对、无回退模式、中止控制接入所有 proot 进程、不依赖第三方特权 binder。

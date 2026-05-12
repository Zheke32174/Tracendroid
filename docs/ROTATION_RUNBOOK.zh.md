# ROTATION_RUNBOOK.zh.md

> 如何轮换 rootfs 签名密钥。配套
> [`THREAT_MODEL.md § 4.5`](./THREAT_MODEL.md) 与
> [`SHELL_REBUILD.md`](./SHELL_REBUILD.md)。本地开发签名材料见
> [`signing/README.md`](../signing/README.md)。英文原本见
> [`ROTATION_RUNBOOK.md`](./ROTATION_RUNBOOK.md)。

rootfs 信任链有且仅有一个锚：APK 内置于
`app/src/main/assets/rootfs/operit-rootfs-pubkey.pem` 的 Ed25519 公钥。匹配的私钥
在 CI 中对 rootfs `.tar.zst` 签名。设备从不读取、获取或接受 APK 之外任何来源的公钥。

因此**轮换是应用更新事件**，不是运行时协商。设备上不存在"现在信任这把新密钥"的路径。

## 何时轮换

在下列情形轮换：
- 怀疑曾被信任的私钥泄露。
- 持有私钥的维护者离开项目。
- 定期轮换（v1 无固定周期）。

**不要**为了"测试流程"而轮换。如需测试，按 `signing/README.md` 生成个人开发密钥对，
旁路装载自签的 rootfs。生产密钥对仅在实际意图轮换时触碰。

## 流程

整个流程以单一 PR 出现。审查该 PR 即是安全检查点。

### 1. 生成新密钥对

在维护者本地机器、**不在**仓库内的目录：

```bash
cd ~/operit-keystore   # 或你维护者密钥材料的存放位置
openssl genpkey -algorithm Ed25519 -out new-rootfs-signing.pem
openssl pkey -in new-rootfs-signing.pem -pubout -out new-rootfs-pubkey.pem

# 计算指纹，以便你能确认 CI 日志与设备侧 bootstrap 接触面看到相同值。
openssl pkey -in new-rootfs-pubkey.pem -pubin -outform DER \
    | sha256sum | awk '{print $1}'
```

本地保存两个文件。私钥永不进入仓库或 CI 提交。公钥将在下一步成为受跟踪文件。

### 2. 替换仓库中的公钥资产

```bash
cd /path/to/operit/repo
cp ~/operit-keystore/new-rootfs-pubkey.pem \
   app/src/main/assets/rootfs/operit-rootfs-pubkey.pem
```

为此变更**单独**开 PR。审阅者的工作：

- 确认第 1 步算出的指纹与 PR diff 中文件计算的指纹一致（`openssl pkey -in
  app/src/main/assets/rootfs/operit-rootfs-pubkey.pem -pubin -outform DER | sha256sum`）。
- 私钥未混入（`git diff` 仅显示 `assets/rootfs/` 下的公钥 PEM + 本 runbook + 如有则
  对应 ZH 镜像）。
- PR 描述说明轮换原因（泄露 / 维护者变更 / 计划性轮换）。

### 3. 更新 GitHub Actions secret

PR 合并后：

```
Repository → Settings → Secrets and variables → Actions → ROOTFS_SIGNING_KEY
→ Update secret
→ 粘贴 ~/operit-keystore/new-rootfs-signing.pem 内容
```

签名工作流（`.github/workflows/rootfs.yml`）读取 `ROOTFS_SIGNING_KEY`，在 runner
内通过 `openssl pkey -pubout` 自行推导公钥半部分，**仅信任本次构建签名时刚推导出的
公钥**。不存在外部提供的"匹配公钥"信任对——runner 每次都重新推导，以避开这类对偶
脚枪。

### 4. 触发新一次 rootfs 构建

```
Repository → Actions → rootfs-build → Run workflow
```

工作流：
- 按 `debian/build.sh` 从钉定的 Debian 快照构建 rootfs。
- 将新私钥落入 runner。
- 推导公钥半部分并对 `.tar.zst` 签名。
- 上传前在 runner 内重新验证签名（`debian/sign.sh` 的合理性检查）。
- 用 `shred -u` 从 runner 销毁私钥。

### 5. 发布产物

产物 + `.sig` + `.pubkey.pem` 上传至 GitHub Release。在跟进 PR 中将
`ShellRootfsRelease.kt` 的 `EXPECTED_VERSION` + `EXPECTED_SHA256` 常量更新到新版。

### 6. 发版应用

构建并发布新版本应用。用户照常通过 Play Store / 侧载更新。

更新后首次启动：
- `ShellRootfsKeyProvisioner` 注意到内置公钥资产与磁盘副本不同，覆写磁盘副本。
  （见 `app/src/main/java/com/ai/assistance/operit/shell/ShellRootfsKeyProvisioner.kt`。）
- 用户下次运行 rootfs bootstrap 时，验证器使用新公钥。已用旧密钥签名并提取的现存
  rootfs 仍可使用——签名验证在安装时进行一次，不在每次启动时进行——但重新安装或版本
  升级 rootfs 走新密钥。

## 不轮换的内容

- 设备上的插件发行方 TOFU 记录（`PluginPublisherTofuStore`）与本密钥无关。插件信任
  逐插件、逐插件轮换，见 `TOOLPKG_MANIFEST.md`。
- IPC 鉴权秘密（`ShellIpcAuth`）与本密钥无关。它通过
  `ShellRootfsDispatcherInstaller.rotateForSessionStart()` 在每次 shell 会话启动时
  轮换。
- 用户的聊天 / 记忆数据、存于 `CredentialVault` 的 OAuth 令牌及其他应用侧数据与本
  密钥无关。

## 泄露响应

如确认私钥泄露：

1. 立即撤销 GitHub Actions secret。下一次 rootfs 构建因此拒绝签名（工作流的
   `if: env.ROOTFS_SIGNING_KEY != ''` 分支静默）。
2. 以密钥生成到应用更新发布间最短的延迟执行上述轮换流程。
3. **不要**发布让设备动态不信任旧密钥的"撤销"机制——那将是一条运行时信任路径，与
   威胁模型相悖（`SECURITY.md`）。防御方式是：新版本应用自动不信任旧密钥，运行新版
   应用的设备上任何呈现旧密钥签名的 rootfs 安装尝试验证失败。
4. 与用户沟通：在旧版应用下安装 rootfs 的用户仍在信任被泄露的密钥。缓解措施是鼓励
   应用更新，而非任何设备侧动作。

## 审计

每次轮换 PR 都保留在
`git log -- app/src/main/assets/rootfs/operit-rootfs-pubkey.pem` 中。这是公开审计
轨迹。PR 上的审阅者签名是人工关卡。

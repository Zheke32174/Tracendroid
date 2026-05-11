# debian/中文说明

构建 proot 环境使用的 Debian 12（bookworm）rootfs。架构上下文见 [`docs/SHELL_REBUILD.md`](../docs/SHELL_REBUILD.md)。英文原本见 [`README.md`](./README.md)。

## 本地构建

需要 Linux 主机，含 `debootstrap`、`zstd`，且具备 root（或 `fakeroot`）权限。推荐 arm64 本地主机；在 x86_64 上需要 `qemu-user-static` 与启用 binfmt_misc 的内核。

```bash
sudo bash debian/build.sh
```

产出：

- `debian/out/tracendroid-rootfs-<版本>-arm64.tar.zst`
- `debian/out/tracendroid-rootfs-<版本>-arm64.tar.zst.sha256`

可通过环境变量覆盖：`ROOTFS_VERSION`、`TARGET_ARCH`、`DEBIAN_SUITE`、`DEBIAN_MIRROR`、`SOURCE_DATE_EPOCH`。默认值即 v1 钉定。

## CI 构建

通过 [`.github/workflows/rootfs.yml`](../.github/workflows/rootfs.yml) 中的 `workflow_dispatch` 手动触发。使用原生 arm64 runner（`ubuntu-24.04-arm`），不涉及 qemu。产物上传后保留 90 天。

当 `debian/**` 或该 workflow 文件本身变更被推送到本分支时，workflow 也会自动运行——对构建脚本的迭代不需要手动触发。

## 刷新流程

切一个新的 rootfs 版本：

1. 同步推进 `ROOTFS_VERSION`（`build.sh` 中的默认值）与 `DEBIAN_MIRROR` 快照钉定。
2. 更新 `SOURCE_DATE_EPOCH` 以匹配快照时间戳。
3. 提交 PR。CI 发布产物。
4. 后续 PR 将新的 SHA-256 接入设备端验证器，并与匹配的应用版本一起落地。（见 `SHELL_REBUILD.md § rootfs 托管与完整性`。）

## v1 不在范围内的内容

- **严格可重复构建**（密封、源码钉定）。今天的构建是“截至某点可重复”（同一源码下的两次 CI 运行产生逐字节一致的输出）；独立于构建主机的逐位可重复是 v2 目标——见 `SHELL_REBUILD.md § rootfs 构建流水线`。
- **使用项目发布密钥签名。** 产物携带 SHA-256 但未签名。签名基础设施在后续 PR 中与设备端验证器一同落地。
- **增量更新。** v1 使用完整 rootfs 替换；增量更新是 v2。
- **x86_64 ABI 支持。** v1 仅发布 `arm64`，与应用当前的 ABI 过滤器一致。x86_64 跟随模拟器驱动开发需求。

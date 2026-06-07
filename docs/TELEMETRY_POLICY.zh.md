# TELEMETRY_POLICY.zh.md

> Operit 关于遥测、分析与崩溃报告的立场。英文镜像见 [`TELEMETRY_POLICY.md`](./TELEMETRY_POLICY.md)。

## 立场

**应用不收集任何遥测数据。** 无聚合后台度量，无安装计数，无功能使用分析，无错误率仪表板，无静默收集的"opt-out"开关。

这是一条声明的红线，不是一项配置。代码库不包含 Firebase Analytics、Crashlytics、Mixpanel、Sentry，也不存在第一方遥测端点——添加任何一项都需要在同一 PR 中删除本文档并更新 [`THREAT_MODEL.md § 4.12`](./THREAT_MODEL.md)。release CI 不剥离遥测代码，因为没有可剥离的。

## 具体接触面

### Firebase ML Kit

`firebase_ml_collection_enabled` 与 `com.google.firebase.ml.kit.analytics.collection.enabled` 两项 meta-data 在 `app/src/main/AndroidManifest.xml` 中设置为 `false`。ML Kit 携带了一些 Android 图像处理依赖；该 meta-data 指示其上游 SDK 即便在默认上报情境下也不要回拨。

### 崩溃报告

当应用崩溃时，`CrashReportActivity` 在独立的 `:crash` 进程中运行，向用户展示堆栈追踪。用户拥有三个按钮：

- **复制到剪贴板** —— 堆栈追踪进入系统剪贴板。无任何数据离开设备。
- **保存到文件** —— 堆栈追踪写入用户的 Downloads 文件夹。无任何数据离开设备。
- **重启应用** —— 重启。

无网络调用，无 `firebase.crashlytics.recordException`，无上传提示。

用户若希望分享某段堆栈追踪给我们，需手动操作（粘贴到 issue、邮件等）。路径始终是显式的，内容始终是其先可见的。

### Logcat 导出

工具箱包含 logcat 导出屏。它将日志写入用户选择的文件。与崩溃报告同一模型：仅本地、用户发起、内容可见。

### 网络请求

应用为用户的实际工作发起网络请求：AI API 调用（指向用户配置的提供商）、网页搜索、浏览器会话、rootfs 下载（PR 2/N）、MCP server 等。这些都不是"遥测"——每个请求都是用户行为的直接后果。

## 实际含义

- 安装应用产生零外联流量。第一个请求出现在用户触发某项需要网络的操作时。
- 卸载应用产生零外联流量。
- 崩溃产生零外联流量。
- 长时间会话产生的流量精确正比于用户与其所选提供商的 API 用量。

## 如该立场更改

任何未来在上述定义内引入 SDK 或端点的提交必须：

1. 删除或重写本文档。
2. 更新 [`THREAT_MODEL.md § 4.12`](./THREAT_MODEL.md) —— 将其从 `closed` 移回 `partial` 或 `open`，并重写 Rule。
3. 在首个携带该变更的构建发布前落地一个应用内说明界面。
4. 新增的收集默认关闭，按 `SECURITY.md` 原则 1（默认拒绝）。

用户侧遥测屏 `app/src/main/java/com/ai/assistance/operit/ui/features/telemetry/TelemetryPolicyScreen.kt` 将本立场直接读入一个设置界面，用户任何时候都能看到该政策。

# OAUTH_PKCE_MIGRATION.md（中文镜像）

> 将 GitHub OAuth 从 confidential-client（使用 `client_secret`）迁移至 PKCE（Proof Key for Code Exchange）的范围与执行计划。实现落地后解决 `THREAT_MODEL.md § 4.8`。英文原本见 [`OAUTH_PKCE_MIGRATION.md`](./OAUTH_PKCE_MIGRATION.md)。

## 为何

`app/build.gradle.kts` 第 74–75 行嵌入了 `GITHUB_CLIENT_SECRET` 作为 `BuildConfig` 字段，来源于 `local.properties`。如被填入，该值会进入 APK 并可被反编译者还原。按 `SECURITY.md` 红线**“APK 不嵌入秘密”**，必须移除。

当前 OAuth 流程是**纯 confidential-client**：`client_id` + `client_secret` + `code` → `access_token`。代码库中不存在 PKCE 机制——在分支 `claude/operit-fork-optimization-WWapH` 上对 `code_verifier`、`code_challenge`、`pkce` 的出现次数为零。迁移在一个原子 PR 中添加 PKCE 往返并丢弃秘密。

GitHub OAuth App 支持 PKCE（带 `code_challenge` + `code_challenge_method=S256` 的 `response_type=code`），无论是否注册了秘密。按当前 `docs/BUILDING.md` 注册了带秘密的 OAuth App 的用户无需重新注册——他们只是不再使用该秘密。

## 当前消费者（已审计）

| 文件 | 秘密被触碰之处 |
|---|---|
| `app/build.gradle.kts` | 第 74–75 行声明 `GITHUB_CLIENT_ID` + `GITHUB_CLIENT_SECRET` 为 BuildConfig 字段 |
| `app/src/main/java/com/ai/assistance/operit/data/preferences/GitHubAuthPreferences.kt` | Companion object：`val GITHUB_CLIENT_SECRET = BuildConfig.GITHUB_CLIENT_SECRET`。`getAuthorizationUrl()` 仅以 `client_id` / `redirect_uri` / `scope` / `state` 构造授权 URL。无 `code_challenge` 参数。|
| `app/src/main/java/com/ai/assistance/operit/data/api/GitHubApiService.kt` | `getAccessToken(code)` 以 form-encoded 形式 POST `client_id` + `client_secret` + `code` 到 `https://github.com/login/oauth/access_token`。|
| `app/src/main/java/com/ai/assistance/operit/ui/features/github/GitHubOAuthCoordinator.kt` | 编排往返；接收回调 URI；提取 `code` + `state`；调用 `GitHubApiService.getAccessToken(code)`。|
| `app/src/main/AndroidManifest.xml` | 声明 `MainActivity` 上的深链 intent filter（`scheme="operit"`，`host="github-oauth-callback"`）。本迁移不变。|
| `app/src/main/java/com/ai/assistance/operit/ui/main/MainActivity.kt` | `handleIntent()` → `GitHubAuthPreferences.isOAuthRedirectUri()` → 分发到 `GitHubOAuthCoordinator.completeExternalLogin()`。不变。|
| `local.properties.example` | 要求用户填入 `GITHUB_CLIENT_ID`。（当前文件仅提及 ID；秘密的说明在 `docs/BUILDING.md` 中，导致不一致。）|
| `docs/BUILDING.md` | 构建指令第 5 步描述 OAuth App 设置；通过 Client ID 流程隐含了秘密。|

## 目标状态

### 授权 URL

由 `GitHubAuthPreferences.getAuthorizationUrl()` 构造：

```
https://github.com/login/oauth/authorize
  ?client_id=<id>
  &redirect_uri=operit://github-oauth-callback
  &scope=notifications,public_repo,user:email,read:user
  &state=<random32>
  &code_challenge=<base64url-无填充(sha256(verifier))>
  &code_challenge_method=S256
```

### 验证器生成与持久化

- `code_verifier` 是 64 个 URL 安全随机字符（RFC 7636 要求 43–128）。由与 `GitHubAuthPreferences` 同处的新 `PkceCodeGenerator` 对象生成（可能位于 `data/preferences/PkceCodeGenerator.kt`）。
- 验证器持久化于现有的 `githubAuthDataStore`（一个新键，如 `PENDING_OAUTH_CODE_VERIFIER`，与现有 `PENDING_OAUTH_STATE` 键并列）。在回调处理器首次读取时被消费并清除，与 state 令牌完全一致。
- **加密被推到后期**：`PENDING_OAUTH_STATE` 已位于明文 DataStore 中，项目更广泛的凭据加密工作跟踪于 `THREAT_MODEL.md § 4.9`。验证器在本 PR 中遵循同一模式，随 § 4.9 的其余部分一起迁移到 `EncryptedSharedPreferences`。

### 访问令牌 POST

`GitHubApiService.getAccessToken(code, verifier)`：

```kotlin
val formBody = FormBody.Builder()
    .add("client_id", GitHubAuthPreferences.GITHUB_CLIENT_ID)
    .add("code", code)
    .add("code_verifier", verifier)
    .build()
```

`client_secret` 行被移除。函数签名增加验证器参数；协调器传递被消费的验证器。

### 移除

- `app/build.gradle.kts:75` — `buildConfigField("String", "GITHUB_CLIENT_SECRET", ...)` 行删除。
- `GitHubAuthPreferences.kt` companion — `val GITHUB_CLIENT_SECRET = BuildConfig.GITHUB_CLIENT_SECRET` 行删除。
- `local.properties.example` — 无需修改（示例文件当前未提及秘密；按 `docs/BUILDING.md` 操作的用户可能本地添加了该项）。
- `docs/BUILDING.md` — OAuth 设置步骤改写，不再要求秘密。加说明：“你可能在 GitHub OAuth App 设置中看到的 Client Secret 不再被应用使用——PKCE 取代它。你可以把秘密留在 GitHub UI 中；本应用只是忽略它。”

## 逐步实现

1. **添加 `PkceCodeGenerator`** 至 `app/src/main/java/com/ai/assistance/operit/data/preferences/PkceCodeGenerator.kt`。小 `object`，包含：
   - `generateCodeVerifier(): String` — 使用 `SecureRandom` 生成 64 个 URL 安全随机字符。
   - `computeCodeChallenge(verifier: String): String` — 使用 `java.security.MessageDigest` + `android.util.Base64.URL_SAFE or NO_PADDING or NO_WRAP` 计算 base64url-无填充 SHA-256。
2. **更新 `GitHubAuthPreferences.kt`**：
   - 移除 `val GITHUB_CLIENT_SECRET`。
   - 添加 `private val PENDING_OAUTH_CODE_VERIFIER = stringPreferencesKey("pending_oauth_code_verifier")`。
   - 添加 `setPendingCodeVerifier(verifier: String)` 与 `consumePendingCodeVerifier(): String?` 与 state 方法平行。
   - 修改 `getAuthorizationUrl(state)` 以额外接受（或生成并持久化）验证器、计算挑战、并在 URL 中添加 `code_challenge` + `code_challenge_method`。签名：`suspend fun getAuthorizationUrl(state: String = createOAuthState()): String` — 新增的 suspend 修饰符使验证器能在返回 URL 前持久化。
3. **更新 `GitHubApiService.kt::getAccessToken`**：
   - 添加 `verifier: String` 参数。
   - 将 `.add("client_secret", …)` 替换为 `.add("code_verifier", verifier)`。
4. **更新 `GitHubOAuthCoordinator.kt`** 以：
   - 处理回调时调用 `consumePendingCodeVerifier()`。
   - 将验证器传递给 `getAccessToken(code, verifier)`。
   - 若验证器缺失，流程可见失败（不是静默）——按 `SECURITY.md` “安全路径中无回退模式”。
5. **从 `app/build.gradle.kts` 中移除 `GITHUB_CLIENT_SECRET`**。删除第 75 行。
6. **更新 `docs/BUILDING.md`** 第 5 步描述。删除与秘密相关的指令。说明正在使用 PKCE。
7. **更新 `THREAT_MODEL.md § 4.8`** + ZH 镜像：状态 `open → closed`，位置刷新。
8. **更新 `AUDIT_PLAN.md § 发布检查项`** secret-scan 行：确认该检查项在迁移后代码上通过。

## 测试计划

1. 本地构建：`./gradlew :app:compileDebugKotlin` 成功。
2. 烟雾运行：安装调试 APK，点击 GitHub 登录按钮。
3. 浏览器打开 GitHub authorize URL。验证其含 `code_challenge=` 与 `code_challenge_method=S256`。
4. 在浏览器中批准。重定向至 `operit://github-oauth-callback?code=...&state=...`。
5. 应用接收回调。`GitHubOAuthCoordinator` 使用 PKCE 交换。`access_token` 返回。
6. 验证访问令牌可用：拉 `GET /user` 返回已登录用户。
7. 反编译 release APK（`apktool d`）；grep `client_secret`；确认零匹配。

## 不在范围

- **凭据静态加密。** 验证器持久化于现有明文 DataStore 中，与 `PENDING_OAUTH_STATE` 一同。到 `EncryptedSharedPreferences` 的迁移位于更大的 `§ 4.9` 凭据存储 PR 中，不在本次。
- **其他 OAuth 提供方**（Google、Microsoft 等）。它们在未来 PR 中逐提供方落地，在我们拥有面向它们的 AGENT_CORE 后端之后。
- **服务端代理。** GitHub 不需要。后期针对需要机密秘密的提供方可能需要（那时 `SECURITY.md` 规则“若机密秘密在结构上必须，该凭据存放于我方掌握的服务端代理之后”适用）。

## 交叉引用

- 实现落地后解决 `THREAT_MODEL.md § 4.8`（构建期秘密）。
- 遵守 `SECURITY.md` 红线：APK 不嵌入秘密。
- 遵守 `SECURITY.md` 红线（安全路径中无回退模式）：验证器缺失 = 可见失败，不静默重试。
- 引用 `THREAT_MODEL.md § 4.9`，验证器的明文 DataStore 住处随之迁到加密存储。

# STATUS.md

> Branch state as of `bc4db3b` (commit 48/N). Companion to
> [`SECURITY.md`](./SECURITY.md), [`THREAT_MODEL.md`](./THREAT_MODEL.md),
> [`AUDIT_PLAN.md`](./AUDIT_PLAN.md). This document is the handoff —
> read first if you're picking up the work. 中文镜像不维护单独翻译，
> 各小节直接引用对应的中文威胁模型镜像与运行手册。

## TL;DR

`claude/operit-fork-optimization-WWapH` is a long-haul reconstruction branch that
takes Operit from "many quietly-broken security surfaces" to **eleven of thirteen
threat-model rows fully closed, two partial**, both partial only because they depend
on external infrastructure that cannot be produced inside the development sandbox
(libproot.so binary; a real production signing keypair backed by CI secrets; a real
plugin ecosystem shipping signed bundles). Every code-side gap the audit identified
is closed. No row is in pure "design" status.

## Section 4 closure scorecard

| § | Title | Status | Notes |
|---|---|---|---|
| 4.1 | Exported Android receivers | closed | `ScriptExecutionReceiver` + 3 debug receivers stripped from release; `ExternalChatReceiver` + `WorkflowTaskerReceiver` gate on `BroadcastSenderAllowlist` (Tasker seeded) |
| 4.2 | In-process JS sandbox (QuickJS) | closed | `JsPluginGate` + `JsCapabilityClassifier` + `AiToolGate` + `JsPluginGatePersistence` + `ToolGateConfirmationOverlay`. Default-deny per (caller × capability), persisted, audited |
| 4.3 | Plugin marketplaces | partial | Trust pipeline complete (`PluginManifest` / `PluginSignatureVerifier` / `PluginPublisherTofuStore` / `PluginTrustChecker` / `TrustedPluginInstaller`) + manifest spec + TOFU overlay + settings screen. v1 deliberately keeps backward compat for the legacy unsigned-import path; cut-over criteria documented |
| 4.4 | Privileged automation channel: AccessibilityService only | closed | libsu, Shizuku, Shower, root, and the `:showerclient` / `tools/shower` modules removed; `ROOT`/`DEBUGGER` enum values dropped; ~13,000 lines deleted |
| 4.5 | Subscription OAuth in proot | partial | Dispatcher implements `subscription_account` / `subscription_tier` / `subscription_alive` with per-CLI per-command field allowlists; `do_read_file` refuses session-file paths defensively; Android classifier routes the three commands to `METADATA`. End-to-end runtime test pending `libproot.so` |
| 4.6 | AI output as a validated channel | closed | AI HTML WebView locked down (no JS / no file / no net / refuses every nav); `AiOutputLinkPolicy` scheme allowlist gates AI-emitted link clicks. Caught + fixed an RCE-via-prompt-injection on the way |
| 4.7 | Phone-as-actuator | closed | `HaltController` + halt FAB + halted banner + foreground-service halt action + `AgentReasoningTrace` snapshot capture |
| 4.8 | Build-time secrets | closed | GitHub OAuth migrated to PKCE (RFC 7636); `GITHUB_CLIENT_SECRET` removed from BuildConfig |
| 4.9 | Credential storage | closed | `CredentialVault` (EncryptedSharedPreferences) migrates GitHub OAuth tokens, external HTTP bearer, ModelConfig single + pool API keys. No SSH-key storage found in the codebase |
| 4.10 | Documents providers | closed | `WorkspaceDocumentsProvider` path-traversal bug fixed (`getFileForDocId` + `createDocument` + `renameDocument` all canonicalize + check containment) |
| 4.11 | Cleartext traffic | closed | NSC `cleartextTrafficPermitted=false` base-config; loopback allowlist for `127.0.0.1` + `localhost`; user-CA trust only under `<debug-overrides>` |
| 4.12 | Telemetry, analytics, crash reports | closed | `TELEMETRY_POLICY.md` (EN+ZH); in-app `TelemetryPolicyScreen`; codebase has no analytics SDK |
| 4.13 | AI collaborator decline | closed | `AgentDecline` + `DeclineRegistry` + classification enum + `AgentDeclineOverlay` + reasoning snapshot capture |

## What the branch shipped (by category)

### Foundation documents

- `docs/SECURITY.md` + `.zh.md` — principles, red lines, AI-as-collaborator stance,
  sovereign-user authority.
- `docs/THREAT_MODEL.md` + `.zh.md` — 13 per-surface rows mapping principles to code,
  with status (closed / partial / design) per row.
- `docs/AUDIT_PLAN.md` + `.zh.md` — open design questions, release gates, CVE-class
  regression tests, reference index. All five v1 open questions resolved (§§ 1.1, 1.2,
  1.3, 1.4, 1.6).
- `docs/SHELL_REBUILD.md` + `.zh.md` — proot rebuild scope.
- `docs/AGENT_CORE.md` + `.zh.md` — agent backend interface, decline channel, halt channel.
- `docs/OAUTH_PKCE_MIGRATION.md` + `.zh.md` — PKCE migration record.
- `docs/TELEMETRY_POLICY.md` + `.zh.md` — no-telemetry stance.
- `docs/TOOLPKG_MANIFEST.md` + `.zh.md` — plugin manifest format spec for plugin authors.
- `docs/ROTATION_RUNBOOK.md` + `.zh.md` — production rootfs key rotation procedure.
- `signing/README.md` — local-dev signing flow.

### Removals (§ 4.4 + adjacent)

- `:showerclient` Gradle module deleted
- `tools/shower/` companion app project deleted
- libsu + Shizuku + Bintray repo + version-catalog entries dropped
- Shizuku manifest entries (permission + provider + receiver) stripped
- All Shizuku / Shower / root / PhoneAgent / VirtualDisplayOverlay /
  UIAutomationProgressOverlay / debug-permission-wizard Kotlin sources deleted
- `AndroidPermissionLevel.ROOT` and `.DEBUGGER` enum values dropped; `root/` and
  `debugger/` tool variant trees deleted; ShellExecutorFactory and ActionListenerFactory
  collapsed
- `RootCommandExecutionMode` enum + DataStore keys (with one-shot legacy cleanup)
- `autoglm/` feature subtree deleted (PhoneAgent-driven)
- 216 stale i18n strings per locale removed across 6 locales
- `ScriptExecutionReceiver` stripped from release variant (RCE vector)

### Security infrastructure landed

| Subsystem | Files |
|---|---|
| Shell rebuild — rootfs builder | `debian/build.sh`, `debian/sign.sh`, `.github/workflows/rootfs.yml` |
| Shell rebuild — downloader / verifier / extractor | `app/src/main/java/.../shell/{ShellRootfsLayout,ShellRootfsManifest,ShellRootfsRelease,ShellRootfsDownloader,ShellRootfsSignatureVerifier,ShellRootfsExtractor,ShellRootfsKeyProvisioner,ShellBootstrapState,ShellBootstrapManager,ShellRootfsDispatcherInstaller}.kt` |
| Shell rebuild — IPC | `app/src/main/java/.../shell/ipc/{ShellIpcProtocol,ShellIpcAuth,ShellIpcServer,ShellIpcClient}.kt`; `app/src/main/assets/rootfs/operit-dispatcher.py` |
| Shell rebuild — launcher | `app/src/main/java/.../shell/launcher/{ShellProcessSpawner,ShellSessionManager,ShellForegroundService}.kt` |
| AI safety | `core/halt/HaltController.kt`, `core/agent/decline/{AgentDecline,DeclineRegistry}.kt`, `core/agent/reasoning/AgentReasoningTrace.kt`, `core/tools/AiToolGate.kt`, `core/tools/javascript/{JsPluginGate,JsPluginGatePersistence,JsCapabilityClassifier}.kt`, `core/aioutput/AiOutputLinkPolicy.kt` |
| Plugin trust | `core/plugintrust/{PluginManifest,PluginSignatureVerifier,PluginPublisherTofuStore,PluginTrustChecker,PluginInstallTofuRegistry,TrustedPluginInstaller}.kt` |
| Credentials | `data/preferences/credentials/CredentialVault.kt`; migrations in `data/preferences/{GitHubAuthPreferences,ExternalHttpApiPreferences,ModelConfigManager}.kt` |
| Receiver gating | `integrations/intent/BroadcastSenderAllowlist.kt`; ExternalChat / WorkflowTasker receivers gated |
| CI | `.github/workflows/{rootfs.yml,app-build.yml}` |

### User-reachable surfaces

Sidebar (system section, order 5–10):

- **Accessibility setup** — `AccessibilityOnboardingScreen`
- **Shell environment setup** — `ShellBootstrapScreen`
- **Plugin & AI gate** — `PluginGateScreen` (grants + audit)
- **Broadcast sender allowlist** — `BroadcastAllowlistScreen`
- **Telemetry policy** — `TelemetryPolicyScreen`
- **Plugin trust** — `PluginTrustScreen` (TOFU records + forget)

Overlays mounted at the OperitApp root (cross-screen reachable):

- **Per-call confirmation** — `ToolGateConfirmationOverlay` (§ 4.2)
- **Halt FAB + halted banner with reasoning snapshot** — `HaltControlOverlay` (§ 4.7)
- **AI decline dialog with reasoning snapshot** — `AgentDeclineOverlay` (§ 4.13)
- **Plugin TOFU prompt** — `PluginTofuPromptOverlay` (§ 4.3)

Notification: shell foreground service notification with Halt action.

## Open follow-ups gated on external infrastructure

These will not happen inside the development sandbox; they need access to a build
server, a signing-key custodian, or a community ecosystem.

1. **`libproot.so` per ABI** in `app/src/main/jniLibs/`. License-clean static build of
   proot. Without this, `ShellProcessSpawner` returns `BinaryMissing` and the shell
   session never actually runs. The IPC bridge round-trip is structurally complete on
   both sides; only the binary is missing.

2. **Real Ed25519 signing keypair** for the rootfs. Replace
   `app/src/main/assets/rootfs/operit-rootfs-pubkey.pem` with the production public
   key; add the matching private key as the `ROOTFS_SIGNING_KEY` GitHub Actions
   secret. Full procedure in `docs/ROTATION_RUNBOOK.md`.

3. **Real rootfs release**. Run `.github/workflows/rootfs.yml` against a build server,
   publish the `.tar.zst` + `.sig` to a GitHub Release, update
   `ShellRootfsRelease.EXPECTED_VERSION` + `EXPECTED_SHA256` to pin the new artifact.

4. **Plugin ecosystem shipping signed bundles**. § 4.3's legacy-install cut-over flips
   on when there's a meaningful number of plugins users care about that ship signed
   `.toolpkg` bundles + a migration tool exists for legacy plugins.

5. **End-to-end runtime validation** for the shell rebuild + § 4.5 subscription
   metadata commands. Needs a real proot session running against a real rootfs to
   actually round-trip a request through the dispatcher and back.

## Open follow-ups that are pure code work

These can land without external infra but were not in scope of this branch:

- **Per-backend `DeclineRegistry.record` wiring.** Each chat backend's "AI refused"
  signal varies (HTTP error code, JSON field, natural-language pattern). Recognizing
  declines correctly is a per-backend study. The data model + capture path are in
  place; the recognition wiring is the natural extension as the agent-core absorbs
  each backend.

- **Strict actuator-active-only halt indicator.** § 4.7's text contemplates a stricter
  mode where a second indicator lights up only while a tool call is actively running.
  The FAB satisfies the always-on requirement; the stricter variant is a refinement.

- **Settings UI for the IPC auth secret rotation** and other adjacencies — currently
  every secret rotates correctly on session start, but there's no settings surface to
  force a rotation manually.

## Where to find what

- **Why this row is closed** → `docs/THREAT_MODEL.md` (the row in question carries a
  Rule / Status / Location block).
- **What decision was made on an open design question** → `docs/AUDIT_PLAN.md § 1.x`.
- **How to ship a plugin against this app** → `docs/TOOLPKG_MANIFEST.md`.
- **How to rotate the signing key** → `docs/ROTATION_RUNBOOK.md`.
- **What the telemetry stance is** → `docs/TELEMETRY_POLICY.md`.
- **What the proot environment looks like** → `docs/SHELL_REBUILD.md`.
- **What the agent backend interface looks like** → `docs/AGENT_CORE.md`.
- **The full security philosophy** → `docs/SECURITY.md`.

## Commit index

Numbered commits in this series ship with an `(N/N)` suffix in their subject line. The
pre-numbering commits (1–17 below) are the documentation foundation that
established the scope before code work began.

| # | Commit | Subject |
|---|---|---|
| - | `08a9ff8` | docs: add SECURITY, THREAT_MODEL, AUDIT_PLAN (EN) |
| - | `af0459f` | docs: add SECURITY, THREAT_MODEL, AUDIT_PLAN (ZH mirrors) |
| - | `28cd850` | docs: add SHELL_REBUILD scope (EN + ZH) |
| - | `425af4a` | docs(shell): switch launcher to proot-only |
| - | `a401bf6` | docs: bake in no-Shizuku/Shower stance + add AGENT_CORE |
| - | `fa3dc4a` | docs(en cleanup): finish no-Shizuku/Shower coherence + taxonomy |
| - | `f2d88fc` | docs(zh): mirror THREAT_MODEL + SHELL_REBUILD |
| - | `fe925ac` | docs(zh): mirror AUDIT_PLAN + add AGENT_CORE translation |
| - | `4159715` | build(rootfs): add Debian 12 bookworm build pipeline (PR 1/N) |
| - | `66b41f0` | build(manifest): strip debug-only receivers from release builds |
| - | `54ae253` | docs(threat-model): mark 3 debug-receiver rows closed (EN + ZH) |
| - | `7bcd552` | docs(oauth): scope the GITHUB_CLIENT_SECRET → PKCE migration |
| - | `9762a26` | feat(oauth): migrate GitHub OAuth from client_secret to PKCE (4/N) |
| - | `407241b` | feat(oauth): complete PKCE migration — restore compile + close § 4.8 |
| - | `5e0fcb1` | docs(threat-model): close § 4.8 (build-time secrets) — EN + ZH |
| - | `433f6d5` | feat(network): default-deny cleartext + drop user-CA trust (closes § 4.11) |
| - | `0e33822` | docs(threat-model zh): mirror § 4.11 closure |
| 1 | `a01efa5` | build: drop Shizuku, libsu, showerclient — start § 4.4 removal |
| 2 | `a54ee39` | build: delete :showerclient module + tools/shower companion app |
| 3 | `1aae68a` | build(manifest): strip Shizuku permission, provider, and Shower receiver |
| 4 | `fd1a515` | feat(rm): delete Shizuku- and root-only Kotlin files |
| 5 | `ccd1d5c` | feat(rm): delete Shower-only main-app Kotlin files |
| 6 | `f54fca9` | feat(rm): scrub nav layer of Shizuku/Shower references |
| 7 | `7932e4c` | feat(rm): scrub lifecycle/app of Shower init + shutdown hooks |
| 8 | `8245e71` | feat(rm): delete orphaned demo subsystem |
| 9 | `2b1f754` | feat(rm): finish § 4.4 call-site scrub |
| - | `a0722fe` | docs(threat-model): close § 4.4 — EN + ZH |
| 10 | `8fb2fd0` | feat(rm): drop ROOT/DEBUGGER from AndroidPermissionLevel enum |
| 11 | `130ed75` | i18n: scrub Shizuku/Shower/autoglm string resources |
| 12 | `80da9a0` | feat(js): add JsPluginGate + JsCapabilityClassifier scaffold |
| 13 | `d5a327c` | feat(js): wire JsPluginGate into the JS tool dispatcher |
| 14 | `89f2cf6` | docs(threat-model): close § 4.2 (partial) — EN + ZH |
| 15 | `a2ff59c` | feat(ui): add AccessibilityOnboardingScreen |
| 16 | `fe91831` | feat(shell): rootfs bootstrap scaffold — downloader/verifier/extractor |
| 17 | `166f7da` | feat(ui): shell bootstrap progress screen |
| 18 | `6ef5e74` | feat(data): drop root execution mode + custom su command from preferences |
| 19 | `ff46c08` | feat(js): plugin gate persistence + settings screen |
| 20 | `8d37e0e` | feat(ai): AI-side tool gate audited via the plugin gate UI |
| 21 | `ddeceb7` | feat(shell): rootfs signing infrastructure — PR 4/N |
| 22 | `0220ff4` | feat(shell): proot launcher + Unix-socket IPC scaffold — PR 3/N |
| 23 | `fb4d7da` | feat(js): per-call confirmation overlay closes § 4.2 |
| 24 | `c77fdc2` | feat(shell): foreground service owns ShellSessionManager |
| 25 | `1fd662a` | feat(shell): send-side IPC client |
| 26 | `268bd99` | feat(shell): in-proot IPC dispatcher |
| 27 | `2766d31` | feat(shell): filesystem-path address mode for ShellIpcClient |
| 28 | `ae1f882` | feat(halt): sovereign user halt control closes § 4.7 partial |
| 29 | `9d9ee09` | feat(agent): first-class AI decline outcome closes § 4.13 partial |
| 30 | `0ba244c` | feat(receivers): close § 4.1 — ScriptExec stripped + allowlist gates |
| 31 | `a51e034` | feat(ui): broadcast allowlist settings screen |
| 32 | `f913f69` | docs(telemetry): close § 4.12 — policy doc + in-app screen |
| 33 | `140bb68` | feat(security): close § 4.6 — lock down AI HTML WebView + link allowlist |
| 34 | `796d4ff` | feat(security): close § 4.9 partial — CredentialVault + token migrations |
| 35 | `5ccd608` | fix(provider): close § 4.10 — path-traversal in WorkspaceDocumentsProvider |
| 36 | `1a98aeb` | feat(data): close § 4.9 — ModelConfig API keys to vault |
| 37 | `bf8634c` | feat(shell): rotate IPC auth on every session start |
| 38 | `cd5c0ba` | docs(audit): resolve five open AUDIT_PLAN questions |
| 39 | `c8d304e` | feat(plugintrust): plugin signature + TOFU trust pipeline |
| 40 | `9e69d92` | feat(plugintrust): manifest format doc + TOFU settings screen |
| 41 | `632278a` | feat(plugintrust): TOFU prompt overlay for plugin install |
| 42 | `b5e5299` | feat(plugintrust): suspending install entry point that ties the pipeline |
| 43 | `e48a044` | feat(shell): § 4.5 dispatcher metadata commands + classifier rows |
| 44 | `5440467` | docs(threat-model): § 4.3 partial-closed — formal legacy-install policy |
| 45 | `5e17a9d` | feat(agent): AI reasoning snapshot capture closes §§ 4.7 + 4.13 |
| 46 | `57464f7` | ci: add app-build workflow that compiles + lints on every push |
| 47 | `c2be97f` | feat(agent): wire AgentReasoningTrace into EnhancedAIService |
| 48 | `bc4db3b` | docs(signing): rotation runbook + dev-only pubkey replaces non-parseable placeholder |

## Bugs caught + fixed along the way

The audit-driven walkthrough surfaced real bugs:

1. **AI HTML WebView RCE** (`140bb68`, § 4.6). `CustomXmlRenderer.renderHtmlContent`
   shipped with every dangerous WebView setting enabled — `javaScriptEnabled = true`,
   `allowFileAccess = true`, `allowUniversalAccessFromFileURLs = true`,
   `mixedContentMode = ALWAYS_ALLOW`. An attacker with prompt-injection control could
   make the AI emit HTML that ran arbitrary JS in the WebView, read local files, and
   exfiltrated over HTTP. Fixed: every dangerous setting OFF, `WebViewClient` refuses
   all navigation + cross-origin loads.

2. **`WorkspaceDocumentsProvider` path traversal** (`5ccd608`, § 4.10).
   `getFileForDocId` resolved `documentId` as a relative path without canonicalization,
   so `..` segments could escape the workspace root and read / write files anywhere
   the app's UID could reach (e.g. `/data/data/<pkg>/shared_prefs/*.xml`). Reachable
   via the system Documents UI on behalf of any third-party app the user granted
   access to. Fixed: canonicalize + containment check on all three entry points
   (`getFileForDocId`, `createDocument`, `renameDocument`).

3. **Stale IPC auth secret on session restart** (`bf8634c`). The in-proot dispatcher
   reads `/var/lib/operit/auth.secret` once at boot; if a previous proot session
   wrote `S1` and died, a fresh `ShellSessionManager.start()` using `S2` would
   silently fail auth. Fixed: `rotateForSessionStart()` rewrites the rootfs auth
   file before spawning the new dispatcher.

## How to resume

If you're picking this up later:

1. Read this document. Done.
2. Read `THREAT_MODEL.md` (or its ZH mirror). The Rule / Status / Location blocks tell
   you where each surface lives.
3. Pick from the **Open follow-ups that are pure code work** list above. None of those
   need external resources.
4. For anything in **Open follow-ups gated on external infrastructure**, talk to the
   project maintainer about the missing piece (binary / signing key / etc) rather
   than trying to work around it.

If you find a private key checked into this repo, that's a real bug — see
`signing/README.md` for the protocol.

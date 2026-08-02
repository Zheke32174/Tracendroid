# Masamune — de-fork execution plan + upstream give-back

**Status:** plan, not state. Nothing in Stages A–E is done. Every number below was
measured against this tree (branch `claude/zerotrace-mobile-accessories-pxpz04`),
not estimated. Re-measure before executing — the commands are inlined so you can.

---

## 0. We are NOT a fork

Masamune is its own application. The Operit codebase (package
`com.ai.assistance.operit`, its AI-harness / llama / mnn / quickjs / terminal base)
is a **donor** — the same relationship Shizuku and Dhizuku have to Yojimbo, or
InviZible / RethinkDNS to Godwall. We absorb the useful pieces and build superior
variants; we do not track upstream as a fork. By the end, none of the fork identity
remains.

De-fork means three separable things, and conflating them is why this hasn't
happened yet:

1. **Compile-time identity** — the Kotlin/Java package namespace. Changing it costs
   a lot of mechanical churn and breaks nothing for users.
2. **Runtime identity** — `applicationId`, intent actions, provider authorities,
   SharedPreferences filenames, on-disk directory names, deep-link scheme. Changing
   these breaks existing installs, saved Tasker configs, published plugins, and
   granted SAF URIs. This is where the danger lives.
3. **Trust identity** — the app currently self-updates from the *donor's* GitHub
   releases and pulls its plugin/skill/MCP markets from the *donor's* repos. This is
   a supply-chain dependency on a third party and is arguably more urgent than the
   rename.

Stage C covers (3). Do not let the size of Stage A delay it.

---

## 1. Measured baseline

Run these to reproduce:

```bash
grep -rIl 'com\.ai\.assistance\.operit' . --exclude-dir=.git | wc -l   # files
grep -rI  'com\.ai\.assistance\.operit' . --exclude-dir=.git | wc -l   # occurrences
```

| Metric | Value |
|---|---|
| Files containing `com.ai.assistance.operit` | **1205** |
| Total occurrences | **6125** |
| `package com.ai.assistance.operit…` declarations | 1124 |
| `import com.ai.assistance.operit…` lines | 4460 |
| Inline fully-qualified uses (neither package nor import) | 328 |
| Files by extension | 1123 `.kt`, 29 `.js`, 12 `.ts`, 11 `.xml`, 11 `.md`, 4 `.bat`, 3 `.sh`, 3 `.py`, 2 `.kts`, 2 `.aidl`, 1 each `.tpl` `.pro` `.json` `.java` `.cpp` |
| `:app` module | 1140 files / 5962 occurrences |
| `:quickjs` module | 4 files / 4 occurrences |
| `tools/` | 19 files / 39 occurrences |
| `examples/` | 34 files / 87 occurrences |
| `docs/` | 7 files / 28 occurrences |
| Files under `app/src/main/java/com/ai/assistance/operit/` | 1028 files across 276 directories |
| Files under `app/src/{test,androidTest}/` | 118 |
| `import …operit.R` | 341 · `import …operit.BuildConfig` 6 · inline `…operit.R.` 4 |
| Case-insensitive `operit` (branding, wider than the namespace) | 1498 files / 9722 occurrences |
| Git history | `.git` = **530 MB**, pack 528.07 MiB, 45 129 objects, 1243 commits |

### Module map (measured from each `build.gradle.kts`)

| Gradle module | `namespace` | Notes |
|---|---|---|
| `:app` | `com.ai.assistance.operit` | `applicationId` identical. Only module using kapt + ObjectBox. |
| `:dragonbones` | `com.dragonbones` | Already donor-neutral. Leave alone. |
| `:mnn` | `com.ai.assistance.mnn` | 49 JNI symbols `Java_com_ai_assistance_mnn_*` |
| `:llama` | `com.ai.assistance.llama` | 26 JNI symbols |
| `:mmd` | `com.ai.assistance.mmd` | 21 JNI symbols |
| `:fbx` | `com.ai.assistance.fbx` | 9 JNI symbols |
| `:quickjs` | `com.ai.assistance.quickjs` | **but its 4 Kotlin sources declare `package com.ai.assistance.operit.core.tools.javascript`** and its C++ exports 6 `Java_com_ai_assistance_operit_core_tools_javascript_QuickJsNativeBridge_*` symbols. Namespace/package mismatch. |
| `:terminal` | — | Git submodule `AAswordman/OperitTerminalCore`, not checked out here, so no build file in-tree. |
| `tools/desktop` | `com.ai.assistance.operit.desktop` | **Separate Gradle build** (own `settings.gradle.kts`); NOT in root `settings.gradle.kts`. Won't be caught by a root-build compile check. |

`settings.gradle.kts` also carries `rootProject.name = "Operit"`.

---

## 2. Stage A — package namespace rename

### A.0 Recommended namespace

**`dev.pleiades.masamune`**

Rationale: reverse-DNS under the org identity already asserted in
`MODOS_COMPONENT.yaml` (`modos.pleiades/v1alpha1`, `component:understory-agent-trunk`);
gives siblings a coherent family (`dev.pleiades.yojimbo`, `dev.pleiades.genji`,
`dev.pleiades.godwall`); `.dev` is a real registrable TLD so the reverse-DNS claim
is honest; zero collision with the donor.

**Precondition:** you must actually control `pleiades.dev`. If not, use
**`io.github.zheke32174.masamune`** — guaranteed-owned via the GitHub username, ugly
but unimpeachable. Decide this *before* Stage A.4.1; changing it later means doing the
whole rename twice.

Rejected: `com.masamune` (unowned, squattable, generic — "Masamune" is a common
product name); `com.ai.assistance.masamune` (cheapest, since the sibling modules
already sit under `com.ai.assistance.*` — but it keeps the donor's vendor prefix,
which defeats the entire exercise).

**Sibling modules are in scope too.** A complete de-fork also renames
`com.ai.assistance.{mnn,llama,mmd,fbx,quickjs}` → `dev.pleiades.masamune.{…}`. That
costs **105 additional JNI symbol renames** in C/C++ (49+26+21+9), each of which is
underscore-mangled and therefore invisible to a dotted-name search. Budget for it or
explicitly defer it (step A.4.8) — but write down which you chose.

### A.1 `namespace` vs `applicationId` — the distinction that decides the risk

They are set on two different lines and mean two different things:

- `app/build.gradle.kts:25` — `namespace = "com.ai.assistance.operit"`.
  Compile-time only. Determines the package of the generated `R` and `BuildConfig`
  classes and the default manifest package. Changing it has **zero** effect on
  installed apps or user data. All 341 `import …operit.R` and 6
  `import …operit.BuildConfig` lines follow it mechanically; there is no separate
  R-class migration.
- `app/build.gradle.kts:57` — `applicationId = "com.ai.assistance.operit"`.
  The on-device install identity. Changing it produces a **different app**: no
  upgrade path, no data carry-over, a fresh install alongside the old one, all
  runtime permissions re-requested, all SAF grants void, and any external caller
  (Tasker, `adb am broadcast` scripts, sibling apps) targeting the old id breaks.

**They can be renamed independently, and should be.** Rename `namespace` first (safe),
ship, then decide about `applicationId` separately with an explicit "we are breaking
existing installs" decision.

The manifest already uses `${applicationId}` correctly in two places
(`AndroidManifest.xml:142` fileprovider, `:176` androidx-startup) and hardcodes the
literal in others — see A.3 item 4.

### A.2 The mechanical part (an IDE refactor handles this)

- 1124 `package` declarations, 4460 `import` lines, 328 inline FQNs.
- Directory moves: `app/src/main/java/com/ai/assistance/operit/` (1028 files,
  276 dirs) → `app/src/main/java/dev/pleiades/masamune/`; likewise
  `app/src/{test,androidTest}/java/…` (118 files) and
  `app/src/androidTest/js/com/ai/assistance/operit/…` (the JS fixtures mirror the
  package path).
- `app/src/main/aidl/com/ai/assistance/operit/provider/` — 2 `.aidl` files. **See A.3
  item 9 before moving these.**
- `quickjs/src/main/java/com/ai/assistance/operit/core/tools/javascript/` — 4 files.
- `settings.gradle.kts` `rootProject.name`; `app/build.gradle.kts` `namespace`.
- `tools/desktop/app/build.gradle.kts` + its 6 sources (separate build).

Prefer Android Studio's *Refactor → Rename package* for the Kotlin/Java tree — it
updates imports and `R` references correctly. Do **not** run a repo-wide
`sed -i 's/com\.ai\.assistance\.operit/dev.pleiades.masamune/g'` and call it done;
the next section is why.

### A.3 The dangerous part — what a mechanical rename MISSES or silently BREAKS

These are real, enumerated from this tree. Each needs a deliberate decision, not a
substitution. **Note the shape of the risk: almost every item here fails at runtime,
not at compile time.** A green build proves nothing about this list.

**1. JNI symbol names — a dotted-name search does not match them.**
11 exported C++ functions encode the package with underscores:
- `app/src/main/cpp/streamnative/native_xml_splitter.cpp:29`
  `Java_com_ai_assistance_operit_util_streamnative_NativeXmlSplitter_nativeSplitXmlSegments`
- `app/src/main/cpp/streamnative/native_markdown_splitter.cpp:30,39,48,58` (4 more)
- `quickjs/src/main/cpp/quickjs_jni.cpp:737,760,769,802,841,855` (6 for
  `QuickJsNativeBridge`)

`grep -rn 'Java_com_ai_assistance'` finds them; the dotted grep returns nothing. They
fail with `UnsatisfiedLinkError` at runtime.
`app/src/main/cpp/streamnative/StreamOperators.cpp:14` additionally documents an
ordinal contract against `com.ai.assistance.operit.util.markdown.MarkdownProcessorType`.

**2. SharedPreferences filenames that are literal FQN strings — renaming them orphans
user data.** These strings are the XML filenames under
`/data/data/<pkg>/shared_prefs/`:
- `data/preferences/SkillVisibilityPreferences.kt:9`
  `PREFS_NAME = "com.ai.assistance.operit.data.preferences.SkillVisibilityPreferences"`
- `core/tools/packTool/PackageManager.kt:64`
  `PACKAGE_PREFS = "com.ai.assistance.operit.core.tools.PackageManager"`

A sed will happily rewrite them and the app will silently start with empty prefs.
**Correct move: leave these two literals frozen at their old value** (they are opaque
storage keys, not identity), or write a one-shot file-copy migration. Add a comment
saying so, or someone will "fix" the inconsistency later.

**3. Intent action constants — ~25 of them, mirrored in five places.**
Kotlin constants in:
`api/chat/AIForegroundService.kt:111,114,117,122,126,130,133,135,137` (9),
`services/FloatingChatService.kt:101–104` (4),
`shell/launcher/ShellForegroundService.kt:48,49`,
`integrations/intent/ExternalChatReceiver.kt:19,20`,
`integrations/tasker/WorkflowTaskerReceiver.kt:24`,
`ui/main/MainActivity.kt:68`,
`ui/features/settings/screens/ExternalHttpChatSettingsScreen.kt:463,464`,
`ui/features/workflow/viewmodel/WorkflowViewModel.kt:410,464`,
`widget/ToolPkgDesktopWidgetHost.kt:13,14`,
`core/tools/system/ScreenCaptureService.kt:26`,
`core/tools/javascript/ScriptExecutionReceiver.kt:27`,
`core/tools/packTool/{ToolPkgDebugInstallReceiver,PackageDebugRefreshReceiver,ToolPkgComposeDslDebugDumpReceiver}.kt:17`.

Mirrored in `AndroidManifest.xml:362,370,378,386,394,462`; in shipped JS
(`app/src/main/assets/packages/operit_editor.js:2061,2063`); in dev tooling
(`tools/execute_js.sh:168,170`, `tools/execute_js.bat:206,208`,
`tools/execute_js_dir.{sh,bat}`, `sync_example_packages.py:17,18,20,23`); and in docs
(`docs/workflow_intent_trigger.md`, `docs/external_intent_chat.md`).

Renaming these is a **breaking public API change**: every Tasker profile a user has
already saved, and every external integration, targets the old action string. Either
keep the old actions as manifest aliases alongside the new ones, or version the change
loudly in a release note.

**4. ContentProvider authorities — hardcoded, and they invalidate persisted URI grants.**
`AndroidManifest.xml:153` `android:authorities="com.ai.assistance.operit.documents.workspace"`
and `:164` `…documents.memory`, mirrored at
`provider/WorkspaceDocumentsProvider.kt:26` and `provider/MemoryDocumentsProvider.kt:33`.
Every `content://` URI the user has persisted through SAF dies on change. Fix these to
derive from `${applicationId}` (as `:142` and `:176` already do) so they follow the
applicationId decision automatically, and accept the one-time grant loss.
Also `app/src/androidTest/.../SerializerAndroidTest.kt:67` parses
`content://com.ai.assistance.operit/item/1`.

**5. `res/xml/` component references — AAPT does not validate these.**
- `accessibility_service_config.xml:9` `settingsActivity="com.ai.assistance.operit.MainActivity"`
  — **already wrong today**: `MainActivity` actually lives at
  `…operit.ui.main.MainActivity` (cf. `shortcuts.xml:12,23`). Fix while you're in here.
- `shortcuts.xml:10–12,21–23` — `action` / `targetPackage` / `targetClass` ×2
- `interaction_service.xml:4,8,9` — `sessionService` / `settingsActivity` / `recognitionService`
- `toolpkg_desktop_widget_info.xml:14` — `configure=`

All fail at runtime only.

**6. Activity aliases resolved by string concatenation.**
`util/AppIconManager.kt:18–21` builds three component names as
`"${MainActivity::class.java.name}DefaultAlias"` / `…DefaultLauncherAlias` /
`…SimpleAlias`, matched against manifest aliases `.ui.main.MainActivityDefaultAlias`
(`:233`), `…DefaultLauncherAlias` (`:242`), `…SimpleAlias` (`:259`). A rename that
changes the alias *relative* names — or an IDE refactor that renames `MainActivity`
without the aliases — silently breaks icon switching **and the Android Studio launch
entry** (`ensureComponentState` re-enables the IDE alias by that same derived name).

**7. The JS↔Java bridge resolves classes from script-supplied strings.**
`core/tools/javascript/JsJavaBridgeDelegates.kt:902,905,907` calls
`Class.forName(normalized)` on names that come from plugin scripts. Consequences:
- `examples/` contains **212** `Java.type("com.ai.assistance.operit…")` /
  `Java.com.ai.assistance.operit.…` references across 34 files.
- `tools/sandboxpackage_dev_install_or_update.js:120`
  `Java.type("com.ai.assistance.operit.util.AssetCopyUtils")`.
- `docs/SCRIPT_DEV_GUIDE.md:623` documents the FQN form as the public plugin API.
- Instrumentation JS contracts assert the package name literally:
  `androidTest/js/.../basic_syntax.js:103`, `java_to_js.js:92`,
  `bridge_edges.js:395` — `assertEq(String(context.getPackageName()), 'com.ai.assistance.operit')`;
  `suspend_await.js:10-12,31,54,131` binds five FQNs.

**Any toolpkg already published to the plugin market that uses the FQN form breaks on
rename, and we cannot fix third-party plugins.** Mitigation: in `loadClass`, rewrite a
leading `com.ai.assistance.operit.` to the new prefix before `Class.forName`, log a
deprecation, keep it for at least one release. Ship the mapping *in the same build as
the rename*, not after.

**8. A separately-compiled Java helper resolves into the host by name.**
`examples/apktool/runtime_helper/.../ApkReverseHelperFacade.java:230`
`Class.forName("com.ai.assistance.operit.core.subpack.KeyStoreHelper")`. This helper is
shipped as `examples/apktool/resources/apktool/apk-reverse-helper-runtime-android.jar`
(a committed 9 MiB binary). Renaming the Kotlin class without rebuilding and
recommitting that jar breaks APK re-signing at runtime.

**9. AIDL + the prebuilt accessibility provider APK — a hard stop.**
`app/src/main/aidl/com/ai/assistance/operit/provider/IAccessibilityProvider.aidl` and
`IAccessibilityEventCallback.aidl` declare `package com.ai.assistance.operit.provider`.
The AIDL **interface descriptor is that FQN** and must byte-match the peer.
The peer is `app/src/main/assets/accessibility.apk` — a **2.7 MB prebuilt binary we
cannot rebuild from this repo** (donor-built). `data/repository/UIHierarchyManager.kt:45,49`
binds it via `PROVIDER_PACKAGE_NAME = "com.ai.assistance.operit.provider"` /
`PROVIDER_ACTION = "com.ai.assistance.operit.provider.IAccessibilityProvider"`,
`core/tools/system/AccessibilityProviderInstaller.kt:18` installs it, and
`AndroidManifest.xml:68` declares `<package android:name="com.ai.assistance.operit.provider"/>`
for package visibility.

**Decision required:** either (a) keep the `…operit.provider` AIDL package and those
literals frozen forever — legal and works, but a permanent donor fingerprint; or
(b) rebuild the provider APK from our own source under the new namespace, which means
sourcing or reimplementing it. Do **not** rename the AIDL package without (b) — it
compiles and then fails to bind.

Same class of problem, different peer: `core/tools/system/OperitTerminalManager.kt:15`
`PACKAGE_NAME = "com.ai.assistance.operit.terminal"` is a **third-party app's** package
id (`AAswordman/OperitTerminal`, fetched from the donor's GitHub releases). It is not
ours to rename. Freeze it, or replace the integration (Stage C.1).

**10. ProGuard/R8 keep rules go stale silently.**
`app/proguard-rules.pro:42`
`-keep class com.ai.assistance.operit.core.tools.javascript.JsEngine$JsToolCallInterface { *; }`.
Minification is **off** today (`isMinifyEnabled = false` for both `release` and
`nightly`), so a stale rule costs nothing right now and detonates the first time anyone
turns R8 on. Update it with the rename, and add a task to actually enable minification
once so the rule is exercised.

Also in the same file: `-keep class com.ai.assistance.shower.{ShowerBinderContainer,
IShowerService,IShowerVideoSink}` — a **different** `com.ai.assistance.*` namespace,
belonging to a `shower-server.jar` asset that **no longer exists in the tree** (history
only; no `shower` reference survives in any `.kt`). Do not blanket-rename
`com.ai.assistance.` — and separately, delete these three dead rules.

**11. Localized strings embed the data path.** Six `strings.xml` locales carry
`Android/data/com.ai.assistance.operit/files/packages` under
`name="navigate_to_external_files"` (`values`, `values-en`, `values-es`, `values-id`,
`values-ms`, `values-pt-rBR`). These must track `applicationId`, not `namespace` —
best fixed by formatting `context.packageName` in at runtime.

**12. Instrumented tests assert the id.**
`app/src/androidTest/.../ExampleInstrumentedTest.kt:22`
`assertEquals("com.ai.assistance.operit", appContext.packageName)`, plus the same
assertion in `tools/desktop/app/src/androidTest/.../ExampleInstrumentedTest.kt:22`
for `…operit.desktop`.

**13. `local.properties.example`** documents the OAuth callback as
`operit://github-oauth-callback`, matching `AndroidManifest.xml:212-213`
(`android:scheme="operit"` / `android:host="github-oauth-callback"`). The scheme is a
registered redirect URI on the GitHub OAuth App — changing it requires updating the
OAuth App registration **first**, or login breaks.

### A.4 Staged order, with a build check at each step

One commit per step; the check must pass before the next. Note that
`:app:compileDebugKotlin` currently **cannot run on a hosted runner** — see Stage E —
so run these locally / on-device.

| # | Change | Verify |
|---|---|---|
| A.4.1 | Add the `loadClass` old-prefix→new compatibility mapping in `JsJavaBridgeDelegates.kt` + a unit test. No rename yet. | `./gradlew :app:testDebugUnitTest --tests '*JsJavaBridge*'` |
| A.4.2 | Replace hardcoded `"com.ai.assistance.operit"` self-references with `context.packageName` / `${applicationId}` wherever the value means *our own id*: manifest authorities `:153,:164` + their Kotlin mirrors, `util/AnrMonitor.kt:349`, `core/tools/defaultTool/standard/StandardSystemOperationTools.kt:54`, the 6 locale strings. Do **not** touch the freeze-list. | `:app:assembleDebug`; install; confirm the memory + workspace DocumentsProviders still enumerate |
| A.4.3 | Freeze-list: inline `// FROZEN — storage key / donor peer descriptor, do not rename` at the 2 prefs literals, the 2 AIDL packages, `PROVIDER_PACKAGE_NAME`, `PROVIDER_ACTION`, `OperitTerminalManager.PACKAGE_NAME`, manifest `:68`. | review only |
| A.4.4 | **Namespace only.** IDE package refactor for `:app` sources + tests + androidTest JS fixture dirs; `namespace =` in `app/build.gradle.kts`; `rootProject.name`. Leave `applicationId` alone. | `:app:compileDebugKotlin`, then `:app:assembleDebug` |
| A.4.5 | JNI: rename the 5 `app/src/main/cpp/streamnative/*` symbols; fix the `StreamOperators.cpp:14` comment. | `:app:externalNativeBuildDebug`, then exercise markdown streaming **on-device** (`UnsatisfiedLinkError` is runtime-only) |
| A.4.6 | `:quickjs`: move the 4 Kotlin sources, make the module `namespace` consistent, rename the 6 `QuickJsNativeBridge` JNI symbols. | `:quickjs:assembleDebug :app:assembleDebug`; run a JS toolpkg on-device |
| A.4.7 | Non-source mirrors: `proguard-rules.pro:42` (+ delete the 3 dead `shower` rules), the 5 `res/xml/*` files (incl. the pre-existing `accessibility_service_config.xml:9` bug), `tools/*.sh|bat|py`, `sync_example_packages.py`, `tools/compose_dsl/*`, `docs/*.md`, `examples/**` (212 refs), `assets/packages/operit_editor.js`. | `:app:assembleRelease` with `isMinifyEnabled = true` **temporarily**, to actually exercise the keep rules |
| A.4.8 | Sibling modules `:mnn :llama :mmd :fbx` namespaces + 105 JNI symbols — **or** a written deferral. | `./gradlew assembleDebug`; on-device smoke of each backend |
| A.4.9 | `tools/desktop` (separate build) — or delete it if unused. | `cd tools/desktop && ./gradlew assembleDebug` |
| A.4.10 | **`applicationId` decision.** Separate commit, separate release note. If yes: bump `versionCode`, update the OAuth App redirect URI first, add manifest aliases for the old intent actions, write the "this is a new install" note. | fresh-install **and** upgrade-from-old-build test on a real device |

**Verification sweep after A.4.9:**
```bash
grep -rIn 'com\.ai\.assistance\.operit' . --exclude-dir=.git   # only freeze-list hits remain
grep -rn  'Java_com_ai_assistance_operit' --include='*.cpp' .   # empty
```

---

## 3. Stage B — ObjectBox removal (the kapt blocker)

### B.1 Why it matters

`kapt(libs.objectbox.processor)` at `app/build.gradle.kts:318` is the **only** `kapt(...)`
dependency left in the entire build. Room already moved to KSP (`ksp(libs.room.compiler)`,
line 312). ObjectBox has no KSP processor (objectbox-java#1075 open), so the build still
carries:

- the `kotlin-kapt` plugin on `:app` — declared **twice**
  (`alias(libs.plugins.kotlin.kapt)` and `id("kotlin-kapt")`, lines 11 and 15),
- the `io.objectbox` Gradle plugin (root `build.gradle.kts:1–9` buildscript classpath +
  `app/build.gradle.kts:14`),
- a JDK-21 workaround in `gradle.properties`: twelve `--add-opens jdk.compiler/…` flags
  **plus** `kapt.use.worker.api=false`. JDK 21 is mandatory because
  `com.kyant:backdrop:1.0.6` ships class-file major version 65.

kapt also stub-compiles all 1127 Kotlin files in `:app`. Removing ObjectBox removes kapt
entirely, and with it the JDK-21 fragility, the `--add-opens` list, the
in-process-kapt hack, and a meaningful chunk of build time.

### B.2 Exact ObjectBox footprint

**Build files (4 edits):** root `build.gradle.kts:1–9`; `app/build.gradle.kts:14,317,318`;
`gradle/libs.versions.toml:72,237–239,262`.

**Entities — 6, all in `data/model/`:**

| Entity | Props | ObjectBox relations |
|---|---|---|
| `Memory` (`Memory.kt:17`) | 16 | `ToMany<MemoryTag> tags`, `ToMany<MemoryProperty> properties`, `ToMany<MemoryLink> links`, `@Backlink(to="target") ToMany<MemoryLink> backlinks`, `@Backlink(to="memory") ToMany<DocumentChunk> documentChunks` |
| `MemoryTag` (`Memory.kt:75`) | 3 | `ToOne<MemoryTag> parent`, `@Backlink(to="tags") ToMany<Memory> memories` |
| `MemoryLink` (`Memory.kt:92`) | 6 | `ToOne<Memory> source`, `ToOne<Memory> target` |
| `MemoryProperty` (`Memory.kt:108`) | 3 | — |
| `DocumentChunk` (`DocumentChunk.kt:13`) | 5 | `ToOne<Memory> memory` |
| `MemoryAutoSaveCandidate` (`MemoryAutoSaveCandidate.kt:8`) | 9 | — (`@Index chatId`) |

Schema of record: `app/objectbox-models/default.json` (+ `.bak`).

**Support types:** `data/model/Embedding.kt` (FloatArray wrapper with custom
equals/hashCode); `data/model/EmbeddingConverter.kt`
(`PropertyConverter<Embedding?, ByteArray?>`, applied via
`@Convert(dbType = ByteArray::class)` on `Memory.embedding` and `DocumentChunk.embedding`).

**Store + consumers:**
- `data/db/ObjectBox.kt` (60 lines) — `ObjectBoxManager`, per-profile `BoxStore` at
  `filesDir/objectbox` (profile `default`) or `filesDir/objectbox_<profileId>`.
- `data/repository/MemoryRepository.kt` — **2814 lines**, 4 `Box<>` handles (`:91–94`),
  66 ObjectBox call sites (`put`/`remove`/`query`/`QueryBuilder`).
- `data/repository/MemoryAutoSaveCandidateRepository.kt` — 106 lines.
- `data/preferences/UserPreferencesManager.kt:1727` — `ObjectBoxManager.delete(...)`.
- `data/backup/RawSnapshotBackupManager.kt:243,390,453` — closes stores, skips
  `lock.mdb` under `objectbox*` dirs during export.
- Generated metamodel (`Memory_.`, `MemoryTag_.`, …): only **16** references, both in
  the two repository files. Small.
- 8 files import `MemoryRepository`: chat UI (3), settings (2), `MemoryViewModel`,
  `MemoryDocumentsProvider`, `MemoryLibrary`, `MemoryAutoSaveScheduler`,
  `EnhancedAIService`, `MemoryQueryToolExecutor`.
- `ui/features/about/screens/OpenSourceLicenses.kt:107` — license credit.

### B.3 The finding that makes Room viable

**Vector search is not ObjectBox's.** kNN runs on hnswlib
(`com.github.jelmerk:hnswlib-core:1.2.1`) via `util/vector/VectorIndexManager.kt`,
persisting an `HnswIndex` to `.vector_index`. `@HnswIndex` / `nearestNeighbors` appear
**nowhere** in the codebase — embeddings are stored as plain `ByteArray` blobs.
ObjectBox is used purely as an object store with a relation graph. Nothing
irreplaceable is lost by leaving it.

### B.4 Options

| Option | Verdict |
|---|---|
| **Wait for ObjectBox KSP** | Rejected. objectbox-java#1075 has been open for years with no processor. Not a plan. |
| **Move the 6 entities to Room** | **Recommended.** Room is already a dependency, already on KSP, already has `AppDatabase` (v18, 3 entities, 3 DAOs) with a working migration discipline. Removes kapt entirely. |
| **Isolate ObjectBox in a Java-only `:memorystore` module** | Fallback if the schedule can't take the rewrite. kapt survives but stops processing `:app`'s 1127 Kotlin files, recovering most of the build-time and JDK-21 blast radius. Does **not** de-fork the dependency. Stop-gap only; give it an expiry date. |
| **SQLDelight / raw SupportSQLite** | Rejected. Strictly more work than Room for no gain when Room is already in the build. |

### B.5 Room migration — what is easy and what is not

**Easy (near 1:1):**
- `EmbeddingConverter` → a Room `@TypeConverter` pair; the two method bodies port
  verbatim, only the annotations change.
- `MemoryAutoSaveCandidate` → a plain Room `@Entity` + DAO. No relations, one `@Index`.
  Do this one first as the proof.
- `MemoryProperty`, and `MemoryTag` minus `parent` → trivial entities.
- The query DSL: only 16 metamodel references. `Memory_.uuid.equal(x)` →
  `@Query("SELECT * FROM memory WHERE uuid = :x")`;
  `.contains(Memory_.title, s, CASE_INSENSITIVE)` (`MemoryRepository.kt:705`) →
  `LIKE '%' || :s || '%'` with `COLLATE NOCASE`.

**Hard (this is where the days go):**
- **`ToMany` is a live, mutating collection.** Code like
  `memory.tags.add(tag); memoryBox.put(memory)` persists the join implicitly — see
  `MemoryRepository.kt:1013–1020`, and `:978` which even documents a manual "mark the
  relation dirty" hack. Room's `@Relation` is a **read-only projection**; writes need
  explicit join-table DAO calls. Every one of the 66 call sites must be re-expressed.
  Plan for a `MemoryTagCrossRef` (M:N), FK columns on
  `MemoryProperty`/`MemoryLink`/`DocumentChunk` (1:N), and a self-FK on `MemoryTag.parent`.
- **Backlinks disappear.** `Memory.backlinks` and `Memory.documentChunks` become DAO
  queries, so anything that touched them lazily now needs an explicit fetch — watch for
  N+1 in the graph-walk paths.
- **Data migration.** Users have live data at `filesDir/objectbox[_<profileId>]`,
  **per profile**. You need a one-shot importer that opens the old `BoxStore`, streams
  entities into Room, then deletes the directory. That means keeping ObjectBox (and
  therefore kapt) in the build for exactly one release. Sequence: ship the importer
  release → confirm adoption → ship the kapt-removal release.
- **Backup format changes.** `RawSnapshotBackupManager` special-cases `objectbox*` dirs
  at `:390` and `:453`; snapshots taken before the migration must still restore.

**Cheap cleanup while you're in `data/model/`:** `PromptTag.kt:9`
(`@Entity(tableName="prompt_tags")`) and `CharacterCard.kt:41`
(`@Entity(tableName="character_cards")`) are Room-annotated but belong to **no**
`@Database` — `AppDatabase` (v18) lists only `ChatEntity`, `MessageEntity`,
`MessageVariantEntity`. Either wire them up or drop the annotations.

### B.6 Stage B order

| # | Change | Verify |
|---|---|---|
| B.1 | Port `MemoryAutoSaveCandidate` to Room (entity + DAO + `AppDatabase` v19 migration + importer from its box). ObjectBox stays. | `:app:assembleDebug`; on-device: existing auto-save candidates survive an upgrade |
| B.2 | Port `EmbeddingConverter` to a Room `@TypeConverter`; keep the ObjectBox `PropertyConverter` alongside. | unit test round-tripping a FloatArray |
| B.3 | Define the Room schema for the 5 memory entities incl. `MemoryTagCrossRef`; `AppDatabase` v20. Write DAOs. No call-site changes yet. | `:app:kspDebugKotlin` (Room schema validation) |
| B.4 | Rewrite `MemoryRepository` (2814 lines / 66 sites) against the Room DAOs. **Keep its public API identical** so the 8 consumers don't move. | repo unit tests; on-device: memory create/link/tag/search/document-chunk |
| B.5 | One-shot ObjectBox→Room importer for **all** profiles; update `RawSnapshotBackupManager`. **Ship this release. Wait for adoption.** | restore an old snapshot; upgrade from a pre-migration build |
| B.6 | Delete `data/db/ObjectBox.kt`, `app/objectbox-models/`, the `io.objectbox` plugin + buildscript block, the catalog entries, `kapt(...)`, **both** kapt plugin declarations, the twelve `--add-opens` flags and `kapt.use.worker.api=false`. Drop the `OpenSourceLicenses.kt:107` entry. | `:app:assembleDebug` on a **stock JDK 21 with no `--add-opens`**; `./gradlew :app:dependencies \| grep -i objectbox` empty; `grep -rn kapt *.gradle.kts */*.gradle.kts gradle.properties` empty |

---

## 4. Stage C — strip remaining donor branding & identity

`app_name` is already "Masamune" in `values/` and `values-en/`. Everything below is
still Operit. Measured: **1498 files / 9722 case-insensitive `operit` occurrences**.

### C.1 Trust anchors — do these first, independent of Stage A

The app currently trusts the donor's GitHub account for code and updates:

- `ui/features/update/screens/UpdateViewModel.kt:28-29` — `REPO_OWNER = "AAswordman"`,
  `REPO_NAME = "Operit"`. **The app offers users updates built by the donor.**
- `data/updates/UpdateManager.kt:174,226` — `Pair("AAswordman","Operit")`, `owner = "AAswordman"`.
- `ui/features/packages/market/ArtifactMarketModels.kt:12` `OPERIT_MARKET_OWNER = "AAswordman"`;
  `:13` `OPERIT_FORGE_REPO_NAME = "OperitForge"`; `:49,:58` `OperitScriptMarket`,
  `OperitPackageMarket`.
- `ui/features/packages/screens/skill/viewmodel/SkillMarketViewModel.kt:194` — `OperitSkillMarket`.
- `ui/features/packages/screens/mcp/viewmodel/MCPMarketViewModel.kt:228` — `OperitMCPMarket`.
- `ui/features/packages/market/GitHubForgePublishService.kt:215` — creates repos described
  as "Operit publish-only artifact repository".
- `core/tools/system/OperitTerminalManager.kt:16-17` — pulls `OperitTerminal` releases from
  `AAswordman`.
- `ui/features/packages/screens/QuickPluginCreatorSetupSupport.kt:21` — downloads and
  **executes** `install_or_update.js` from `cdn.jsdelivr.net/gh/AAswordman/Operit@main/…`.
- `.gitmodules` — `terminal` → `AAswordman/OperitTerminalCore`;
  `tools/hotbuild/OperitNightlyRelease` → `git@github.com:AAswordman/OperitNightlyRelease.git`.
- `.github/FUNDING.yml` — donates to the donor author.
- 129 `AAswordman` occurrences across 20 files.
- `MODOS_COMPONENT.yaml:50-53` — `derived-from: repo:AAswordman/Operit`. This one is
  **correct and stays**: it is an honest lineage record, not an identity claim.

Action: point updates and markets at our own repos, or disable the in-app updater and
markets until they exist (an updater pointed at a dead repo is better than one pointed
at a third party); replace `FUNDING.yml`; drop the `OperitNightlyRelease` submodule
(donor-private tooling we do not use — see also E.1).

### C.2 Deep link / OAuth

`AndroidManifest.xml:212-213` `android:scheme="operit"`, host `github-oauth-callback`,
documented in `local.properties.example`. Changing the scheme requires updating the
GitHub OAuth App's registered redirect URI **first**.

### C.3 Notification channels

Channel IDs are user-visible in Android Settings and persist across upgrades — changing
one resets the user's per-channel sound/importance:
- `shell/launcher/ShellForegroundService.kt:52` `CHANNEL_ID = "operit_shell_session"` —
  the only donor-branded one.
- Neutral, leave alone: `"floating_chat_channel"`, `"UIDebuggerChannel"`,
  `"AI_SERVICE_CHANNEL"`, `"AI_REPLY_COMPLETE_CHANNEL"` prefix.

### C.4 On-disk directory names (visible in the user's file manager)

`util/OperitPaths.kt:9` `OPERIT_DIR_NAME = "Operit"` — root of
`Download/Operit/{plugins,mcp_plugins,bridge,exports,workspace,test,websession,userscripts,cleanOnExit}`.
Hardcoded *independently* (i.e. not via `OperitPaths`) at:
`ui/error/CrashReportActivity.kt:253` (`Operit/error`),
`ui/features/chat/components/ExportDialogs.kt:788,848` (`Operit/exports`),
`ui/features/chat/webview/LocalWebServer.kt:74` and
`ui/features/settings/screens/ChatHistorySettingsScreen.kt:164` (`Operit/workspace`),
`ui/features/chat/components/ShareImagePreviewDialog.kt:214,226` (`Pictures/Operit`),
`ui/features/settings/screens/ModelPromptsSettingsScreen.kt:552,1599`,
`ui/features/packages/screens/QuickPluginCreatorSetupSupport.kt:23`,
`ui/features/chat/webview/WorkspaceUtils.kt:85`,
`ui/features/packages/market/PluginCreationIntent.kt:15,29,43` (inside a **prompt string
sent to the model**).
Renaming strands existing user files — needs a migration or a deliberate "new dir, old
dir still readable" decision. First step regardless: collapse every call site onto
`OperitPaths` so there is one place to change.

### C.5 Strings, assets, classes, docs

- **`app_name` still donor-branded in 4 of 6 locales:** `values-es` "Operit AI",
  `values-id:208` "Operit AI", `values-ms` "Operit AI", `values-pt-rBR` "Operar IA"
  (a machine-translation artifact). `plugin_app_name` = `"OPERIT"` in every locale.
- `values/strings.xml` alone has **72** `operit` hits: `about_title`,
  `about_description`, `about_website` (links to `github.com/AAswordman/Operit`),
  `about_copyright` ("© 2025 - 2026 Operit"), `config_title`,
  `agreement_human_readable_content`, the LGPLv3 notice, 11 `operit_terminal*` wizard
  strings, `permission_guide_intro_3_desc`, `tool_default_assistant_guide_desc`.
- **Asset filenames:** `assets/operit.png` (branding image), `assets/operit_shell_exec`
  (binary), `assets/packages/operit_editor.js` (shipped plugin, also listed in
  `packages_whitelist.txt`), `assets/rootfs/operit-dispatcher.py`,
  `assets/rootfs/operit-rootfs-pubkey.pem`,
  `assets/templates/java/src/{main,test}/java/com/operit/`,
  `assets/templates/flutter/android/app/src/main/kotlin/com/example/operit_flutter_project/`.
- **Class names (46 paths match `*Operit*`):** `OperitApplication`, `OperitApp`,
  `OperitScreens`, `OperitPaths`, `OperitNotificationListenerService`,
  `OperitVoiceInteractionService`, `OperitVoiceInteractionSessionService`,
  `OperitAssistActivity`, `OperitChatArchive`, `OperitNodeInfo`, `OperitBackupDirs`,
  `OperitTerminalManager`, `OperitTerminalWizardCard`, `OperitQuickJsEngine`,
  `OperitDesktopTheme`. `OperitApplication`, `OperitAssistActivity`,
  `OperitVoiceInteraction*Service` and `OperitNotificationListenerService` are named in
  `AndroidManifest.xml` (`:72,:277,:337`) and `res/xml/interaction_service.xml` — rename
  those together or the app won't start.
- **Web chat:** `web-chat/index.html:6,8` (`application-name` / `<title>` = "Operit AI"),
  `web-chat/package.json:2` `"operit-web-chat"`,
  `src/ui/features/chat/util/ConfigurationStateHolder.ts:1`
  `TOKEN_KEY = 'operit-web-chat-token'` (a localStorage key — renaming logs users out),
  `chatTheme.ts:12,265` `"OperitThemeFont"`.
- **Root files:** `package.json` `"name": "operit"`; `README.md` and `README(E).md`
  (25 `operit` hits each) are donor READMEs describing the donor product — rewrite as
  Masamune's or delete; `chinese_strings_detailed.txt` is a donor i18n audit artifact
  (delete); `examples/remote_operit/`, `examples/operit_editor.{ts,js}`,
  `examples/windows_control/resources/pc_agent/operit-pc-agent/`.
- **In-image branding:** `ui/features/chat/util/MessageImageGenerator.kt:218,230,236`
  draws a header reading **"Operit AI"** onto every shared chat image. Highest-visibility
  leak in the app; fix in the first branding pass.
- `util/GithubReleaseUtil.kt:118` and `QuickPluginCreatorSetupSupport.kt:91` send
  `User-Agent: Operit` / `Operit-QuickPluginCreator/1.0` — our network traffic identifies
  as the donor.

---

## 5. Stage D — history slimming (optional, destructive)

### D.1 The measured offenders

`.git` is **530 MB** (pack 528.07 MiB, 45 129 objects, 1243 commits). Aggregating
**true in-pack compressed bytes** per path (not raw blob size — text deltas well,
binaries don't):

```
 packMiB  blobs  inHEAD  path
    67.8      3    LIVE  examples/apktool/resources/apktool/jadx-runtime-android.jar
    47.1      6    DEAD  app/src/main/assets/subpack/android.apk
    32.7      1    DEAD  app/src/main/assets/termux.apk
    31.2      5    LIVE  examples/apktool/resources/apktool/apktool-runtime-android.jar
    26.1      9    LIVE  app/src/main/assets/accessibility.apk
    21.4      1    DEAD  app/src/main/assets/pets/emoji/anime-smile-transparent.webp
    21.3      1    DEAD  app/src/main/assets/pets/emoji/anime-aojiao-transparent.webp
    18.1      1    DEAD  app/src/main/assets/pets/emoji/anime-cry-transparent.webp
    17.5      1    DEAD  app/src/main/assets/pets/emoji/anime-angry-transparent.webp
    17.3      1    DEAD  app/src/main/assets/pets/emoji/anime-smile-talking-transparent.webp
    17.1      1    DEAD  app/src/main/assets/pets/emoji/anime-happy-transparent.webp
    17.1      1    DEAD  app/src/main/assets/pets/emoji/anime-shy-transparent.webp
    14.1      5    DEAD  app/src/main/assets/subpack/windows.zip
    10.6      5    DEAD  app/src/main/assets/shower-server.jar
     9.0      3    LIVE  examples/apktool/resources/apktool/apk-reverse-helper-runtime-android.jar
     7.4      2    DEAD  app/src/main/jniLibs/x86_64/libavcodec.so
     6.9      2    DEAD  app/src/main/jniLibs/armeabi-v7a/libavcodec_neon.so
     6.8      2    DEAD  app/src/main/jniLibs/x86/libavcodec.so
     6.7      2    DEAD  app/src/main/jniLibs/arm64-v8a/libavcodec.so
     6.6      1    DEAD  mobilenet.tgz
```

**Packed blob bytes: dead = 327.1 MiB, live = 197.1 MiB (total 524.2 MiB.)**

So a purge of dead paths recovers roughly **327 MiB of ~530 MB** — the repo lands near
200 MB. It does **not** produce a small repo, because 125 MiB of *live* committed
binaries remain (`jadx-runtime` 67.8 + `apktool-runtime` 31.2 + `accessibility.apk` 26.1).
Shrinking further means removing those from HEAD too — a separate product decision
(download-at-build-time, or Git LFS; there is no `.gitattributes` and no LFS today).

**Caveat on the standard one-liner.** `git cat-file --batch-check` reports *raw* blob
size, which ranks `app/src/main/res/values/strings.xml` first (109 MiB raw across 344
revisions). That is an artifact: text delta-compresses to near nothing, and its real
in-pack cost is negligible. **Rank by in-pack size** (`git verify-pack -v` on
`.git/objects/pack/*.idx`, joined against `git rev-list --objects --all`) before
deleting anything.

### D.2 The command

`git-filter-repo` is **not** present in this environment — `git-filter-repo`, `bfg`, and
the `git_filter_repo` Python module are all absent. Install first:
`pipx install git-filter-repo` (or `pip install --user git-filter-repo`).

Work on a **fresh clone**, never the working checkout:

```bash
git clone --no-local /workspace/tracendroid /tmp/masamune-slim
cd /tmp/masamune-slim

cat > /tmp/purge-paths.txt <<'EOF'
app/src/main/assets/subpack/android.apk
app/src/main/assets/subpack/windows.zip
app/src/main/assets/termux.apk
app/src/main/assets/shower-server.jar
app/src/main/assets/pets/emoji/anime-smile-transparent.webp
app/src/main/assets/pets/emoji/anime-aojiao-transparent.webp
app/src/main/assets/pets/emoji/anime-cry-transparent.webp
app/src/main/assets/pets/emoji/anime-angry-transparent.webp
app/src/main/assets/pets/emoji/anime-smile-talking-transparent.webp
app/src/main/assets/pets/emoji/anime-happy-transparent.webp
app/src/main/assets/pets/emoji/anime-shy-transparent.webp
app/src/main/jniLibs/x86_64/libavcodec.so
app/src/main/jniLibs/x86/libavcodec.so
app/src/main/jniLibs/armeabi-v7a/libavcodec_neon.so
app/src/main/jniLibs/arm64-v8a/libavcodec.so
mobilenet.tgz
EOF

# Safety gate: nothing on the list may exist in HEAD. No output = safe.
while read -r p; do git cat-file -e "HEAD:$p" 2>/dev/null && echo "LIVE(!) $p"; done < /tmp/purge-paths.txt

git filter-repo --invert-paths --paths-from-file /tmp/purge-paths.txt
git reflog expire --expire=now --all && git gc --prune=now --aggressive
git count-objects -vH        # expect size-pack ≈ 200 MiB
```

Blunter alternative that needs no list: `git filter-repo --strip-blobs-bigger-than 5M`.
It also strips large **live** files, so prefer the explicit list.

### D.3 Honest warning

**This rewrites every commit SHA from the first purged blob onward.** Consequences:

- Requires `git push --force` to **both** remotes (`origin` = tracendroid, `masamune`).
  Any protected-branch rule must be lifted and re-applied.
- Every existing clone, worktree, and CI cache is invalidated. Anyone with local work
  must `git rebase --onto` or re-clone; a naive `git pull` fabricates a duplicated
  parallel history.
- **Every SHA referenced anywhere becomes a dead link** — `docs/STATUS.md`,
  `docs/AUDIT_PLAN.md`, commit trailers, GitHub issue/PR comments, release notes.
  GitHub does not rewrite these.
- The 13 other branches on `origin` (`claude/*`, `modos/*`, `tracendroid/*`) each need
  the same rewrite, or the purged blobs walk straight back into the pack on the next `gc`.
- Open PRs against rewritten branches break and must be recreated.
- `filter-repo` intentionally deletes the `origin` remote after rewriting; re-add both
  remotes by hand.

**Recommendation: do this last, or not at all.** 327 MiB is real but it blocks nothing.
It is the only stage here that destroys information and, unlike every other stage,
cannot be undone by a follow-up commit. If you do it, pick a day when nobody has
uncommitted work, and tag the pre-rewrite tip (`git tag pre-filter-repo-2026 <sha>`,
pushed to a spare remote) so the old history survives somewhere.

---

## 6. Stage E — CI

### E.1 Why the workflow cannot pass today (three independent reasons)

1. **The checked-in `gradle.properties` is configured for an on-device Termux build.**
   ```
   android.aapt2FromMaven=false
   android.aapt2.executable=/data/data/com.termux/files/usr/bin/aapt2
   android.enableAapt2Daemon=false
   ```
   That path does not exist on `ubuntu-24.04`. `:app:compileDebugKotlin` depends on
   `processDebugResources`, which needs aapt2 — so the job fails on a hosted runner
   regardless of anything else. (Introduced by the donor sync in `dcad06d6`.)
2. **The trigger branch is stale.** `app-build.yml` fires on push to
   `claude/operit-fork-optimization-WWapH` and on PRs to `main`. The active branch is
   `claude/zerotrace-mobile-accessories-pxpz04`, so the workflow does not run at all
   right now. Same stale branch in `rootfs.yml`.
3. **kapt on JDK 21 is held together with tape.** JDK 21 is mandatory
   (`com.kyant:backdrop:1.0.6` ships class-file major 65, which JDK-17 javac refuses
   with `class file has wrong version 65.0, should be 61.0`); kapt's stub compiler
   reflects into `jdk.compiler` internals that JPMS closes on 16+. The current fix is
   twelve `--add-opens` in `org.gradle.jvmargs` **plus** `kapt.use.worker.api=false`
   (the Gradle worker did not inherit the opens). This is exactly the fragility Stage B
   deletes.

Also verify before trusting `submodules: recursive`: `.gitmodules` gives
`tools/hotbuild/OperitNightlyRelease` an **SSH** URL (`git@github.com:AAswordman/…`).
`actions/checkout` rewrites `https://github.com/` submodule URLs with its token but not
`git@` ones, so that submodule needs a deploy key or it fails. It is donor-owned tooling
we do not use — drop it (Stage C.1) and the question disappears.

### E.2 What CI should become

**There are no code runners available.** Stop pretending otherwise in the workflow file.
Pick one and write it down:

- **Now (recommended): make the truth explicit.** Change `app-build.yml` to
  `on: workflow_dispatch` only, add a header comment stating "no runners are available;
  this workflow documents the build and is not expected to pass", and move the
  authoritative build recipe into `docs/BUILDING.md`. A red X on every push trains
  everyone to ignore CI, which is worse than having none.
- **Make the build host-agnostic regardless.** The Termux aapt2 override belongs in a
  gitignored `local.properties` / `~/.gradle/gradle.properties`, or behind a conditional
  in `app/build.gradle.kts` that applies only when that file exists. A checked-in
  absolute path to `/data/data/com.termux/…` should not be in a shared repo. Worth doing
  even with zero runners — it un-breaks any future one.
- **When a runner exists:** self-hosted on the Termux device is the natural fit given
  the aapt2 config; a hosted runner works once the override is conditional. Keep
  `:app:compileDebugKotlin` as the gate (`assembleDebug` roughly doubles runtime), keep
  the wrapper-validation allowlist, and delete the JDK-21 `--add-opens` block the moment
  Stage B lands.
- **Do not enable branch protection requiring this check** until it has passed at
  least once.

`rootfs.yml` is independent of the app build and needs neither kapt nor aapt2 — it can
stay `workflow_dispatch`-only and is the more likely first thing to actually run.

---

## 7. Stage F — upstream give-back

We took from this donor. These fixes are not Masamune-specific and should go back as
**separate, single-purpose PRs** against `AAswordman/Operit` — a stack of unrelated
changes gets ignored. Offer them once our build is green, so we can say "verified on a
real build" rather than "should work".

| # | Fix | Where | Why upstream wants it |
|---|---|---|---|
| F.1 | **Restore the dropped `catch` clause** in `addPackageFileFromExternalStorage` | `app/src/main/java/…/core/tools/packTool/PackageManager.kt:2226` (our commit `9d3dac00`, +2 lines) | **A real syntax error in the base — upstream does not compile.** `try {` opens with no `catch`/`finally`; the handler body `return "Error importing package: ${e.message}"` is orphaned inside the try and `e` is undefined. Manifests as `PackageManager.kt:2276 Expecting 'catch' or 'finally'` in `kaptGenerateStubsDebugKotlin`. The fix adds the missing `} catch (e: Exception) {` plus a definite return for the fall-through so the `String`-returning body type-checks. **Send this one first and alone** — highest value, smallest diff, zero opinion in it. |
| F.2 | **Missing `bullet3` `.gitmodules` stanza** | `.gitmodules` (commit `b7ba53f7`) | `mmd/third_party/bullet3` is a gitlink in the tree with no `.gitmodules` entry, so `--recurse-submodules` silently skips it and the `:mmd` native build fails on a clean clone. |
| F.3 | **Gradle `distributionUrl` → official** | `gradle/wrapper/gradle-wrapper.properties` (commit `f3109a38`) | Now `https://services.gradle.org/distributions/gradle-8.13-bin.zip`. The Aliyun mirror it replaced is unreachable from most CI and from outside China. Frame it as "official by default, mirror via env override if wanted" — this is the one with an opinion in it, so say so in the PR. |
| F.4 | **Wrapper-validation allowlist for the vendored Flatbuffers sample** | `.github/workflows/app-build.yml` (commit `64692871`) | Once F.2 lets the full submodule tree check out, `gradle/actions/wrapper-validation` trips on `mnn/.../MNN/3rd_party/flatbuffers/samples/android/gradle/wrapper/gradle-wrapper.jar` — an upstream Flatbuffers sample on an old Gradle version, not a wrapper the project builds with. Fix: standalone validator with checksum `ee3739525a995bcb5601621a6e2daec1f183bbefc375743acc235cec33547e04` allowlisted, plus `validate-wrappers: false` on `setup-gradle` to avoid double-validation. **The real wrapper is still validated.** |
| F.5 | **JDK 21 + kapt-on-21 toolchain fix** | `.github/workflows/app-build.yml`, `gradle.properties` (commits `8f985a3e`, `59d2a7b2`, `5dc7b579`) | `com.kyant:backdrop:1.0.6` ships class-file major 65, so JDK 17 cannot read it and `kaptDebugKotlin` fails. Building on JDK 21 while keeping `sourceCompatibility`/`jvmTarget = 17` fixes it; kapt then needs the twelve `--add-opens jdk.compiler/…` **and** `kapt.use.worker.api=false` (the Gradle worker does not inherit the opens). Upstream has the identical problem and no reason to have found the second half. |
| F.6 | **Room → KSP** | `app/build.gradle.kts:312`, `gradle/libs.versions.toml` (commit `9bb3c0e6`) | KSP is JDK-agnostic and much faster than kapt; useful on its own merits and it shrinks the surface F.5 has to protect. State honestly in the PR that it does **not** remove kapt, because ObjectBox still needs it (objectbox-java#1075). |

**Not for upstream:** anything identity-shaped (the Masamune rename, `FUNDING.yml`,
market/update endpoints), the ObjectBox removal (our architectural choice, not a bug
fix), and the history rewrite.

**Do not** mix F.1 with any of the others in one PR.

---

## 8. Order of operations, with risk

Stages C.1, E and F are independent of everything else and cheap — do them first.
Stage A is long but low-risk if `applicationId` is held to the very end. Stage B is the
only one needing a two-release cadence. Stage D is last, or never.

| Order | Stage | What | Risk | Reversible? |
|---|---|---|---|---|
| 1 | **F.1** | Send the `catch`-clause fix upstream | none | n/a |
| 2 | **C.1** | Repoint update + market + plugin-fetch endpoints off `AAswordman`; drop the `OperitNightlyRelease` submodule; replace `FUNDING.yml` | **low code / high value** — removes a live third-party supply-chain dependency. Users lose in-app updates until our own release channel exists; say so in the release note | yes |
| 3 | **E.1/E.2** | Make the Termux aapt2 override conditional; fix or disable the workflow triggers | low — worst case the build behaves exactly as it does now | yes |
| 4 | **F.2–F.6** | Remaining upstream PRs | none | n/a |
| 5 | **C.5** | Cosmetic branding: `app_name` in the 4 remaining locales, `plugin_app_name`, about/agreement strings, the `MessageImageGenerator` "Operit AI" header, `User-Agent`s, web-chat title, `package.json`, READMEs; delete `chinese_strings_detailed.txt` | low. **Exception:** `ConfigurationStateHolder.ts` `TOKEN_KEY` logs web-chat users out — migrate the key or accept it | yes |
| 6 | **A.0** | Decide the namespace **and** whether sibling modules are in scope. Write it down. | none, but skipping it costs a second full rename | n/a |
| 7 | **A.4.1–A.4.3** | Bridge compat mapping; hardcoded-self-id → `${applicationId}`/`context.packageName`; freeze-list comments | low. The `${applicationId}` change to provider authorities is a no-op **only while** applicationId is unchanged — re-verify at A.4.10 | yes |
| 8 | **A.4.4–A.4.7** | Namespace rename: `:app`, JNI, `:quickjs`, then non-source mirrors | **medium.** Failure modes are runtime-only (`UnsatisfiedLinkError`, missing components, dead `Class.forName`), not compile-time. Budget on-device smoke testing per step, not just a green compile. Residual risk: the 212 `examples/` FQNs and any already-published third-party toolpkg — the A.4.1 compat mapping is what covers them | yes (git), but each step needs its own device test |
| 9 | **A.4.8–A.4.9** | Sibling modules + `tools/desktop`, or written deferral | medium — 105 more JNI symbols, all runtime-only failures | yes |
| 10 | **B.1–B.4** | ObjectBox → Room: entity port, converters, schema, then the 2814-line `MemoryRepository` rewrite | **high effort, medium risk.** `ToMany`→`@Relation` is not mechanical; expect the repository rewrite to dominate the whole de-fork's schedule. Freeze the repository's public API so the 8 consumers don't move | yes, until B.5 ships |
| 11 | **B.5** | Ship the ObjectBox→Room importer. **Wait for adoption.** | **high — user data.** Per-profile stores; a bug here loses the memory graph. Test upgrade-from-old-build and old-snapshot-restore on a real device before release | **no**, once users have upgraded |
| 12 | **B.6** | Delete ObjectBox, kapt, the `--add-opens` block | low, once B.5 has landed and adoption is confirmed | yes |
| 13 | **A.4.10** | `applicationId` change (only if a new app identity is actually wanted) | **high — breaking.** New install, no data carry-over, dead SAF grants, broken saved Tasker configs; OAuth redirect must be re-registered first. Requires a real release note | **no** |
| 14 | **C.4** | Rename `Download/Operit/` and the `Pictures/Operit` album | medium — strands existing user files unless migrated. Collapse all call sites onto `OperitPaths` first | with a migration |
| 15 | **D** | History rewrite (~327 MiB recovered) | **destructive and irreversible.** Rewrites every SHA, force-push to both remotes, invalidates all clones and every SHA link in docs/issues, and must be applied to all 13 other branches or the blobs come back. Tag the pre-rewrite tip somewhere safe first | **no** |

**Freeze-list — never renamed, in any stage:** the two SharedPreferences FQN literals;
the two AIDL packages plus `PROVIDER_PACKAGE_NAME`, `PROVIDER_ACTION` and manifest `:68`
(peer is a prebuilt donor APK we cannot rebuild); `OperitTerminalManager.PACKAGE_NAME`
(a third party's package id); `com.ai.assistance.shower.*` in `proguard-rules.pro`
(different namespace, dead rules — delete rather than rename); and
`MODOS_COMPONENT.yaml`'s `derived-from: repo:AAswordman/Operit` (an honest lineage
record, and the reason this document exists).

# Masamune — de-fork intent + upstream give-back

## We are NOT a fork
Masamune is its own application. The Operit codebase (package
`com.ai.assistance.operit`, its AI-harness / llama / mnn / quickjs / terminal
base) is a **donor** — exactly like Shizuku and Dhizuku are donors to Yojimbo,
or InviZible / RethinkDNS to Godwall. We absorb the useful pieces and build
superior variants; we do not track upstream as a fork. By the end, none of the
fork identity remains.

De-fork work (staged, not yet done):
- Rename the `com.ai.assistance.operit` package namespace to a Masamune
  namespace (large mechanical change — stage it; keep Gradle module names for
  build stability until the sweep is deliberate).
- Strip Operit branding/identity strings (app_name already → "Masamune").
- Shed donor dependency baggage that constrains us — e.g. **ObjectBox**, which
  is kapt-only (no KSP; objectbox-java#1075 still open) and is the reason the
  build can't fully leave kapt. Replacing it (or isolating it) is part of the
  de-fork.

## Give the fix back upstream (the right thing to do)
When our build is green and stable, contribute the generally-useful fixes back
to the original Operit upstream repo — this is a donor we took from, and the
fixes are not Masamune-specific:
- The dropped `} catch (e: Exception) {` in
  `PackageManager.kt::addPackageFileFromExternalStorage` (a real syntax error
  in the base — it doesn't compile without this).
- CI hardening that isn't identity-specific: the `bullet3` `.gitmodules`
  stanza, the vendored-wrapper allowlist, the Gradle distribution URL
  (services.gradle.org vs the Aliyun mirror), JDK 21 + the kapt toolchain fix.
Open these as separate PRs against upstream once ours is settled.

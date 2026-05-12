# TELEMETRY_POLICY.md

> Operit's stance on telemetry, analytics, and crash reports. Bilingual mirror in
> [`TELEMETRY_POLICY.zh.md`](./TELEMETRY_POLICY.zh.md).

## Position

**The app collects no telemetry.** No aggregated background metrics, no install counts,
no feature-usage analytics, no error-rate dashboards, no opt-out toggles that quietly
collect anyway.

This is a stated red line, not a configuration. The codebase contains no Firebase
Analytics, no Crashlytics, no Mixpanel, no Sentry, no first-party telemetry endpoint —
and adding one would require deleting this document and updating
[`THREAT_MODEL.md § 4.12`](./THREAT_MODEL.md) in the same PR. The release CI does not
strip telemetry code because there is none to strip.

## Specific surfaces

### Firebase ML Kit

The `firebase_ml_collection_enabled` and `com.google.firebase.ml.kit.analytics.collection.enabled`
meta-data values are set to `false` in `app/src/main/AndroidManifest.xml`. ML Kit ships
with some Android image-processing dependencies; the meta-data tells it not to phone home
even where its parent SDK would.

### Crash reports

When the app crashes, `CrashReportActivity` runs in a separate `:crash` process and
shows the stack trace to the user. The user has three buttons:

- **Copy to clipboard** — the stack trace goes to the system clipboard. Nothing leaves
  the device.
- **Save to file** — the stack trace goes to a file in the user's Downloads folder.
  Nothing leaves the device.
- **Restart app** — relaunch.

No network call, no `firebase.crashlytics.recordException`, no upload prompt.

If the user wants to share a stack trace with us they do so manually (paste into an
issue, email, etc). The path is always explicit and the content is always visible to
them first.

### Logcat export

The toolbox includes a logcat export screen. It writes logs to a file the user picks.
Same model as crash reports: local-only, user-initiated, content visible.

### Network requests

The app makes network requests for the user's actual work: AI API calls (to providers
the user configures), web searches, browser sessions, rootfs downloads (PR 2/N), MCP
servers, etc. None of these are "telemetry" — every request is a direct consequence of
something the user asked for.

## What this means in practice

- Installing the app produces zero outbound traffic. The first request goes out when
  the user does something that requires one.
- Uninstalling the app produces zero outbound traffic.
- Crashes produce zero outbound traffic.
- A long-running session produces traffic exactly proportional to the user's API usage
  with their chosen providers.

## What changes if this stance ever changes

Any future commit that adds an SDK or endpoint matching the definition above must:

1. Delete or rewrite this document.
2. Update [`THREAT_MODEL.md § 4.12`](./THREAT_MODEL.md) — move it from `closed` back to
   `partial` or `open`, with a Rule rewrite.
3. Land an in-app surface explaining the change before the first build that ships it.
4. Default the new collection to off, per `SECURITY.md` principle 1 (default-deny).

The user-facing telemetry screen at
`app/src/main/java/com/ai/assistance/operit/ui/features/telemetry/TelemetryPolicyScreen.kt`
reads this stance directly into a settings surface so the user can see the policy at
any time.

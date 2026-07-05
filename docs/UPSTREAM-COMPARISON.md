# Tracendroid vs. upstream Operit — parallel comparison

A full clone of the original **AAswordman/Operit** is kept in parallel next to this fork and
used throughout the build-out to compare, recover, and verify. Upstream ref compared against:
`bd78dff` (Operit main). Fork: `tracendroid/p0-ship-foundation` (47 commits ahead).

## How the parallel original was USED (not just cloned)
- **Capability audit** — diffed fork vs upstream to confirm exactly what the fork's security
  pass removed (Shizuku/Shower/root/autoglm UI-automation) so phone-pilot could be rebuilt on a
  SAFE transport (accessibility) instead of naively re-adding the removed stack.
- **String recovery** — 78 `R.string` refs the fork had lost (a compile-breaker) were recovered
  **verbatim** from upstream's `values/` + `values-en/` — the compile would have failed otherwise.
- **Homage verification** — confirmed the fork preserves + credits the original (NOTICE, LICENSE,
  about screen, the 青出于蓝 easter egg).

## What the fork ADDS over upstream
- Legal/homage files absent upstream: `NOTICE`, `COPYING`, `COPYING.LESSER`, `THIRD_PARTY_LICENSES.md`.
- 8 western AI vendors + pluggable OAuth infra; working local-exec terminal; avatar wifeu
  (emotion/mood/lipsync/gaze/crossfade/loadable-models); phone-pilot accessibility loop + 6 operative
  tools; 青出于蓝 easter egg; signed-release CI.
- **Made it BUILD** — upstream's own CI never compiled (died at wrapper-validation); this fork fixed
  ~11 real compile blockers and now produces a signed APK.

## Where the parallel clone lives
`scratchpad/Operit-upstream` (this session). Re-clone: `git clone --depth 1 https://github.com/AAswordman/Operit.git`.
Detailed working notes: `scratchpad/audit-fork-vs-upstream.md`, `avatar-8x-plan.md`, `phone-pilot-map.md`.

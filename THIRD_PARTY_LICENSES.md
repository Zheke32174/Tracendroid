# Third-Party Licenses

Tracendroid (LGPL-3.0-or-later) bundles the third-party components listed
below. Each is governed by its own license. None is under a "GPL-proper" or
"non-free" license, so the combined work remains distributable under LGPL-3.0
(see the FFmpeg note).

This manifest lists the load-bearing native and JVM dependencies vendored or
statically linked into a release build. It is maintained by hand; when a
submodule or vendored binary changes, update the corresponding row.

## Native libraries (compiled into the APK / vendored .so / .aar)

| Component | Version / pin | License (SPDX) | Source |
|-----------|---------------|----------------|--------|
| FFmpegKit + FFmpeg | in-house arm64 build | LGPL-3.0-only | https://github.com/arthenica/ffmpeg-kit , https://ffmpeg.org |
| MNN | submodule `mnn/src/main/cpp/MNN` | Apache-2.0 | https://github.com/alibaba/MNN |
| llama.cpp | submodule `llama/third_party/llama.cpp` | MIT | https://github.com/ggml-org/llama.cpp |
| ncnn | submodule `app/src/main/cpp/thirdparty/ncnn` | BSD-3-Clause | https://github.com/Tencent/ncnn |
| sherpa-ncnn | submodule `app/src/main/cpp/thirdparty/sherpa-ncnn` | Apache-2.0 | https://github.com/k2-fsa/sherpa-ncnn |
| QuickJS | submodule `quickjs/thirdparty/quickjs` | MIT | https://github.com/bellard/quickjs |
| ufbx | submodule `fbx/third_party/ufbx` | MIT | https://github.com/ufbx/ufbx |
| bullet3 | submodule `mmd/third_party/bullet3` | Zlib | https://github.com/bulletphysics/bullet3 |
| saba | submodule `mmd/third_party/saba` | MIT | https://github.com/benikabocha/saba |

### FFmpeg license note

FFmpeg is built **without** `--enable-gpl` and **without** `--enable-nonfree`
(see `tools/ffmpeg/build_ffmpeg_kit_wsl.sh`). No GPL-only codecs (x264, x265,
xvid, etc.) are enabled, and TLS is provided by GnuTLS (LGPL) rather than
OpenSSL. The resulting FFmpeg libraries are therefore LGPL-licensed and do not
relicense the combined work to GPL.

## JVM / Kotlin dependencies

The Gradle version catalog (`gradle/libs.versions.toml`) is the authoritative
list of JVM dependencies and their versions. The common licenses among them
are Apache-2.0 (AndroidX, Jetpack Compose, Kotlin, ObjectBox client, Retrofit,
Moshi) and MIT. Generate a full machine-readable report with a license plugin
(e.g. `com.google.android.gms.oss-licenses-plugin` or
`com.jaredsburrows.license`) as part of the release pipeline and attach it to
the GitHub release.

## Upstream

Tracendroid is a derivative of **Operit AI**
(https://github.com/AAswordman/Operit), LGPL-3.0. See `NOTICE` and `LICENSE`.

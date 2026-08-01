@file:Suppress("PackageDirectoryMismatch")

package com.arthenica.ffmpegkit

/**
 * Source compatibility for the republished ffmpeg-kit.
 *
 * This source set is compiled ONLY when the build runs with
 * `-PffmpegKitFallback=true`. See app/build.gradle.kts.
 *
 * WHY IT EXISTS
 *
 * The app consumes ffmpeg-kit as a vendored AAR in app/libs, which is gitignored
 * and published nowhere, so a clean checkout cannot compile StandardFFmpegTool
 * or FFmpegUtil. arthenica retired ffmpeg-kit and removed its releases from
 * Maven Central, so there is no first-party coordinate left to depend on.
 *
 * com.antonkarpenko:ffmpeg-kit-* is a third-party republish of that retired
 * project, and it is the only thing on Maven Central that still ships these
 * classes. It is NOT a drop-in: the republish renamed the package to
 * com.antonkarpenko.ffmpegkit, so `import com.arthenica.ffmpegkit.FFmpegKit`
 * does not resolve against it.
 *
 * Rewriting the imports in the two call sites would break the vendored-AAR path,
 * which is the supported one. Instead this file declares the arthenica package
 * and aliases each type the app actually imports. A Kotlin typealias to a Java
 * class also carries its statics, so `FFmpegKit.execute(...)` and
 * `ReturnCode.isSuccess(...)` keep working unchanged.
 *
 * Only the five types the app imports are aliased. Widening this to the whole
 * API surface would imply the republish is a supported substitute; it is one
 * escape hatch for one build, and the narrow list keeps that honest.
 */

typealias FFmpegKit = com.antonkarpenko.ffmpegkit.FFmpegKit
typealias FFmpegKitConfig = com.antonkarpenko.ffmpegkit.FFmpegKitConfig
typealias FFprobeKit = com.antonkarpenko.ffmpegkit.FFprobeKit
typealias MediaInformation = com.antonkarpenko.ffmpegkit.MediaInformation
typealias ReturnCode = com.antonkarpenko.ffmpegkit.ReturnCode

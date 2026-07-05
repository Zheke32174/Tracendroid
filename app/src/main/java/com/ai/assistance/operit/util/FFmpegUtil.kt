package com.ai.assistance.operit.util

import com.ai.assistance.operit.util.AppLogger

/**
 * Utility class for FFmpeg operations.
 *
 * NOTE (debug-apk-proof branch): the vendored ffmpeg-kit jar (the retired
 * arthenica package) is absent from this build, so every operation is
 * stubbed to fail gracefully. The real branch keeps the ffmpeg-kit-backed
 * implementation. Provide `app/libs/ffmpeg-kit.aar` to restore functionality.
 */
object FFmpegUtil {
    private const val TAG = "FFmpegUtil"

    private const val UNAVAILABLE_MESSAGE =
        "FFmpeg is not available in this build (vendored ffmpeg-kit jar absent). " +
            "Provide app/libs/ffmpeg-kit.aar to enable it."

    /**
     * Build a scale filter string that survives ffmpeg-kit argument parsing.
     * FFmpeg expressions need an escaped comma when passed without a shell.
     */
    fun scaleFilterMaxWidth(maxWidth: Int): String = "scale=min(${maxWidth}\\,iw):-2"

    /**
     * Execute an FFmpeg command and return if it was successful.
     *
     * Stubbed: FFmpeg is unavailable in this build, so this always returns false.
     */
    fun executeCommand(command: String): Boolean {
        AppLogger.w(TAG, "$UNAVAILABLE_MESSAGE (command ignored: $command)")
        return false
    }

    /**
     * Get media information for a file.
     *
     * Stubbed: FFmpeg is unavailable in this build, so this always returns null.
     * Return type is intentionally [String] (nullable) rather than the retired
     * ffmpeg-kit media-information type so nothing references that package.
     */
    fun getMediaInfo(filePath: String): String? {
        AppLogger.w(TAG, "$UNAVAILABLE_MESSAGE (media info unavailable for: $filePath)")
        return null
    }
}

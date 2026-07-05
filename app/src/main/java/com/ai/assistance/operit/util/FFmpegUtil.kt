package com.ai.assistance.operit.util

// FFmpeg support was removed: the arthenica ffmpeg-kit artifacts were retired from
// Maven Central (the project was archived). This stub keeps FFmpegUtil's API surface
// so callers (StandardFileSystemTools' media-info paths) still compile, but it reports
// no media info and executes nothing. Restoring real media probing means wiring an
// ffmpeg-kit replacement and re-fleshing getMediaInfo/executeCommand. Disclosed ceiling,
// not a silent no-op.
object FFmpegUtil {
    private const val TAG = "FFmpegUtil"

    /**
     * Minimal stand-in for the removed com.arthenica MediaInformation, exposing only
     * the fields the callers read. Always null in this build (no probe backend bundled).
     */
    data class MediaInfo(
        val format: String? = null,
        val duration: String? = null,
        val bitrate: String? = null,
        val streams: List<StreamInfo>? = null,
    )

    data class StreamInfo(
        val type: String? = null,
    )

    /**
     * Build a scale filter string that survives FFmpegKit argument parsing.
     * Kept because it is pure string work with no ffmpeg dependency.
     */
    fun scaleFilterMaxWidth(maxWidth: Int): String = """scale=min(${maxWidth}\,iw):-2"""

    /** No ffmpeg backend bundled — cannot execute. */
    fun executeCommand(command: String): Boolean {
        AppLogger.w(TAG, "FFmpeg unavailable (ffmpeg-kit removed); cannot execute: $command")
        return false
    }

    /** No ffprobe backend bundled — no media info available. */
    fun getMediaInfo(filePath: String): MediaInfo? {
        AppLogger.w(TAG, "FFmpeg unavailable (ffmpeg-kit removed); no media info for: $filePath")
        return null
    }
}

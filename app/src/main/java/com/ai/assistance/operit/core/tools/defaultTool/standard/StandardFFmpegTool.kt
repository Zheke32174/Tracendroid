package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.content.Context
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.model.ToolValidationResult

// NOTE: the upstream media tools were built on com.arthenica:ffmpeg-kit, whose
// artifacts were retired from Maven Central (the arthenica project was archived).
// Rather than vendor the ~40 MB native .so blob into this fork just to keep three
// rarely-used AI tools alive, the executors below degrade honestly: they keep the
// same class names and signatures (ToolGetter/ToolRegistration still resolve them)
// but report that ffmpeg is not bundled in this build instead of crashing at the
// unresolved reference. Restoring real media processing means adding an ffmpeg-kit
// replacement (e.g. a self-hosted maven mirror or a JNI wrapper) and re-fleshing
// these three executors. This is a deliberate, disclosed ceiling — not a silent no-op.

private const val FFMPEG_UNAVAILABLE =
    "FFmpeg is not available in this build. The ffmpeg-kit native library was removed " +
        "upstream (arthenica archived, artifacts pulled from Maven Central) and is not " +
        "bundled here. Media conversion/probe tools are disabled until an ffmpeg-kit " +
        "replacement is wired in."

private fun ffmpegUnavailable(tool: AITool): ToolResult =
    ToolResult(
        toolName = tool.name,
        success = false,
        result = StringResultData(""),
        error = FFMPEG_UNAVAILABLE,
    )

/** FFmpeg工具执行器 — disabled: ffmpeg-kit native lib not bundled. */
class StandardFFmpegToolExecutor(private val context: Context) : ToolExecutor {
    override fun invoke(tool: AITool): ToolResult = ffmpegUnavailable(tool)

    override fun validateParameters(tool: AITool): ToolValidationResult {
        val command = tool.parameters.find { it.name == "command" }?.value
        if (command.isNullOrEmpty()) {
            return ToolValidationResult(valid = false, errorMessage = "Must provide command parameter")
        }
        return ToolValidationResult(valid = true)
    }
}

/** FFmpeg信息工具执行器 — disabled: ffmpeg-kit native lib not bundled. */
class StandardFFmpegInfoToolExecutor : ToolExecutor {
    override fun invoke(tool: AITool): ToolResult = ffmpegUnavailable(tool)

    override fun validateParameters(tool: AITool): ToolValidationResult =
        ToolValidationResult(valid = true)
}

/** FFmpeg转换视频工具执行器 — disabled: ffmpeg-kit native lib not bundled. */
class StandardFFmpegConvertToolExecutor(private val context: Context) : ToolExecutor {
    override fun invoke(tool: AITool): ToolResult = ffmpegUnavailable(tool)

    override fun validateParameters(tool: AITool): ToolValidationResult {
        val inputPath = tool.parameters.find { it.name == "input_path" }?.value
        val outputPath = tool.parameters.find { it.name == "output_path" }?.value
        if (inputPath.isNullOrEmpty()) {
            return ToolValidationResult(valid = false, errorMessage = "Must provide input_path parameter")
        }
        if (outputPath.isNullOrEmpty()) {
            return ToolValidationResult(valid = false, errorMessage = "Must provide output_path parameter")
        }
        return ToolValidationResult(valid = true)
    }
}

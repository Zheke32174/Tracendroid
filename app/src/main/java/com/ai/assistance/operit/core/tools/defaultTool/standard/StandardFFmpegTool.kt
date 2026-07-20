package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.content.Context
import com.ai.assistance.operit.core.tools.FFmpegResultData
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.model.ToolValidationResult
import java.io.File

/**
 * FFmpeg unavailability message shared by all stubbed FFmpeg executors.
 *
 * NOTE (debug-apk-proof branch): the vendored ffmpeg-kit jar (the retired
 * arthenica package) is absent from this build, so every FFmpeg tool is stubbed
 * to return a graceful "not available" result. The real branch keeps the
 * ffmpeg-kit-backed implementation. Provide `app/libs/ffmpeg-kit.aar` to
 * restore functionality.
 */
private const val FFMPEG_UNAVAILABLE_MESSAGE =
        "FFmpeg is not available in this build (vendored ffmpeg-kit jar absent). " +
                "Provide app/libs/ffmpeg-kit.aar to enable it."

/** FFmpeg工具执行器 提供媒体文件处理能力，包括转换、裁剪、合并等功能 */
class StandardFFmpegToolExecutor(private val context: Context) : ToolExecutor {
    companion object {
        private const val TAG = "FFmpegToolExecutor"
    }

    override fun invoke(tool: AITool): ToolResult {
        val command = tool.parameters.find { it.name == "command" }?.value ?: ""

        if (command.isEmpty()) {
            return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Command cannot be empty"
            )
        }

        // FFmpeg is unavailable in this build; return a graceful failure.
        return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = FFMPEG_UNAVAILABLE_MESSAGE
        )
    }

    override fun validateParameters(tool: AITool): ToolValidationResult {
        val command = tool.parameters.find { it.name == "command" }?.value

        if (command.isNullOrEmpty()) {
            return ToolValidationResult(valid = false, errorMessage = "Must provide command parameter")
        }

        return ToolValidationResult(valid = true)
    }
}

/** FFmpeg信息工具执行器 获取有关系统FFmpeg配置的信息 */
class StandardFFmpegInfoToolExecutor : ToolExecutor {
    companion object {
        private const val TAG = "FFmpegInfoToolExecutor"
    }

    override fun invoke(tool: AITool): ToolResult {
        // FFmpeg is unavailable in this build; return a graceful failure.
        return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = FFMPEG_UNAVAILABLE_MESSAGE
        )
    }

    override fun validateParameters(tool: AITool): ToolValidationResult {
        // 不需要参数
        return ToolValidationResult(valid = true)
    }
}

/** FFmpeg转换视频工具执行器 提供一个简化的接口用于常见的视频转换操作 */
class StandardFFmpegConvertToolExecutor(private val context: Context) : ToolExecutor {
    companion object {
        private const val TAG = "FFmpegConvertToolExecutor"
    }

    override fun invoke(tool: AITool): ToolResult {
        val inputPath = tool.parameters.find { it.name == "input_path" }?.value ?: ""
        val outputPath = tool.parameters.find { it.name == "output_path" }?.value ?: ""
        val format = tool.parameters.find { it.name == "format" }?.value
        val resolution = tool.parameters.find { it.name == "resolution" }?.value
        val bitrate = tool.parameters.find { it.name == "bitrate" }?.value
        val audioCodec = tool.parameters.find { it.name == "audio_codec" }?.value
        val videoCodec = tool.parameters.find { it.name == "video_codec" }?.value

        if (inputPath.isEmpty() || outputPath.isEmpty()) {
            return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Input path and output path cannot be empty"
            )
        }

        val inputFile = File(inputPath)
        if (!inputFile.exists()) {
            return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Input file does not exist: $inputPath"
            )
        }

        // FFmpeg is unavailable in this build; return a graceful failure.
        return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = FFMPEG_UNAVAILABLE_MESSAGE
        )
    }

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

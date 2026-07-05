package com.ai.assistance.operit.ui.features.toolbox.screens.logcat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** 日志记录数据类 */
data class LogRecord(
        val message: String,
        val level: LogLevel,
        val timestamp: Long = System.currentTimeMillis(),
        val tag: String? = null,
        val pid: String? = null,
        val tid: String? = null
)

/** 日志级别 */
enum class LogLevel(val displayName: String, val symbol: String, val color: Color) {
    VERBOSE("Verbose", "V", Color(0xFF9E9E9E)),
    DEBUG("Debug", "D", Color(0xFF2196F3)),
    INFO("Info", "I", Color(0xFF4CAF50)),
    WARNING("Warning", "W", Color(0xFFFFC107)),
    ERROR("Error", "E", Color(0xFFF44336)),
    FATAL("Fatal", "F", Color(0xFF9C27B0)),
    SILENT("Silent", "S", Color(0xFF607D8B)),
    UNKNOWN("Unknown", "?", Color(0xFF9E9E9E))
}
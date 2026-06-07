package com.ai.assistance.operit.core.tools.defaultTool

import android.content.Context
import com.ai.assistance.operit.core.tools.defaultTool.accessbility.*
import com.ai.assistance.operit.core.tools.defaultTool.admin.*
import com.ai.assistance.operit.core.tools.defaultTool.standard.*
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.data.preferences.androidPermissionPreferences

/** 工具获取器 - 根据首选权限级别获取对应的工具实现。 */
object ToolGetter {

    fun getFileSystemTools(context: Context): StandardFileSystemTools {
        return when (androidPermissionPreferences.getPreferredPermissionLevel()) {
            AndroidPermissionLevel.ADMIN -> AdminFileSystemTools(context)
            AndroidPermissionLevel.ACCESSIBILITY -> AccessibilityFileSystemTools(context)
            AndroidPermissionLevel.STANDARD,
            null -> StandardFileSystemTools(context)
        }
    }

    fun getShellToolExecutor(context: Context): StandardShellToolExecutor {
        return StandardShellToolExecutor(context)
    }

    fun getUITools(context: Context): StandardUITools {
        return when (androidPermissionPreferences.getPreferredPermissionLevel()) {
            AndroidPermissionLevel.ADMIN -> AdminUITools(context)
            AndroidPermissionLevel.ACCESSIBILITY -> AccessibilityUITools(context)
            AndroidPermissionLevel.STANDARD,
            null -> StandardUITools(context)
        }
    }

    fun getSystemOperationTools(context: Context): StandardSystemOperationTools {
        return when (androidPermissionPreferences.getPreferredPermissionLevel()) {
            AndroidPermissionLevel.ADMIN -> AdminSystemOperationTools(context)
            AndroidPermissionLevel.ACCESSIBILITY -> AccessibilitySystemOperationTools(context)
            AndroidPermissionLevel.STANDARD,
            null -> StandardSystemOperationTools(context)
        }
    }

    fun getDeviceInfoToolExecutor(context: Context): StandardDeviceInfoToolExecutor {
        return when (androidPermissionPreferences.getPreferredPermissionLevel()) {
            AndroidPermissionLevel.ADMIN -> AdminDeviceInfoToolExecutor(context)
            AndroidPermissionLevel.ACCESSIBILITY -> AccessibilityDeviceInfoToolExecutor(context)
            AndroidPermissionLevel.STANDARD,
            null -> StandardDeviceInfoToolExecutor(context)
        }
    }

    fun getHttpTools(context: Context): StandardHttpTools {
        return StandardHttpTools(context)
    }

    fun getWebVisitTool(context: Context): StandardWebVisitTool {
        return StandardWebVisitTool(context)
    }

    fun getBrowserSessionTools(context: Context): StandardBrowserSessionTools {
        return StandardBrowserSessionTools(context)
    }

    fun getIntentToolExecutor(context: Context): StandardIntentToolExecutor {
        return StandardIntentToolExecutor(context)
    }

    fun getSendBroadcastToolExecutor(context: Context): StandardSendBroadcastToolExecutor {
        return StandardSendBroadcastToolExecutor(context)
    }

    fun getTerminalCommandExecutor(context: Context): StandardTerminalCommandExecutor {
        return StandardTerminalCommandExecutor(context)
    }

    fun getMemoryQueryToolExecutor(context: Context): MemoryQueryToolExecutor {
        return MemoryQueryToolExecutor(context)
    }

    fun getFFmpegToolExecutor(context: Context): StandardFFmpegToolExecutor {
        return StandardFFmpegToolExecutor(context)
    }

    fun getFFmpegInfoToolExecutor(): StandardFFmpegInfoToolExecutor {
        return StandardFFmpegInfoToolExecutor()
    }

    fun getFFmpegConvertToolExecutor(context: Context): StandardFFmpegConvertToolExecutor {
        return StandardFFmpegConvertToolExecutor(context)
    }

    fun getCalculator() = StandardCalculator

    fun getWorkflowTools(context: Context): StandardWorkflowTools {
        return StandardWorkflowTools(context)
    }

    fun getChatManagerTool(context: Context): StandardChatManagerTool {
        return StandardChatManagerTool(context)
    }

    fun getSoftwareSettingsModifyTools(context: Context): StandardSoftwareSettingsModifyTools {
        return StandardSoftwareSettingsModifyTools(context)
    }
}

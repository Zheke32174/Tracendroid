package com.ai.assistance.operit.core.tools.defaultTool.admin

import android.content.Context
import com.ai.assistance.operit.core.tools.defaultTool.accessbility.AccessibilityFileSystemTools

/**
 * 管理员级别的文件系统工具。
 *
 * 原继承已被安全加固移除的 DebuggerFileSystemTools（调试/Shizuku 传输，见 docs/THREAT_MODEL.md §4.4），
 * 现下沉到现存的最高安全层 AccessibilityFileSystemTools（与 AdminUITools 保持一致）。
 */
open class AdminFileSystemTools(context: Context) : AccessibilityFileSystemTools(context) {
    // 调试层移除后不新增功能，直接继承无障碍实现。
}

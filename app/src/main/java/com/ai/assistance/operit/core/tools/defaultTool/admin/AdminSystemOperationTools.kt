package com.ai.assistance.operit.core.tools.defaultTool.admin

import android.content.Context
import com.ai.assistance.operit.core.tools.defaultTool.accessbility.AccessibilitySystemOperationTools

/**
 * 管理员级别的系统操作工具。
 *
 * 原继承已被安全加固移除的 DebuggerSystemOperationTools，现下沉到现存的最高安全层
 * AccessibilitySystemOperationTools（与 AdminUITools 保持一致）。
 */
open class AdminSystemOperationTools(context: Context) : AccessibilitySystemOperationTools(context) {
    // 调试层移除后不新增功能，直接继承无障碍实现。
}

package com.ai.assistance.operit.core.tools.defaultTool.admin

import android.content.Context
import com.ai.assistance.operit.core.tools.defaultTool.accessbility.AccessibilityUITools

/**
 * 管理员级别的UI工具。
 *
 * 原继承 DebuggerUITools（调试/Shizuku 传输），该层已被安全加固移除
 * （见 docs/THREAT_MODEL.md §4.4）。此层随之下沉到现存的最高安全层
 * AccessibilityUITools —— 在无 root/Shizuku 的设备上，无障碍服务是唯一安全的
 * UI 自动化通道，管理员级别不再拥有更高权限。
 */
open class AdminUITools(context: Context) : AccessibilityUITools(context) {
    // 调试层移除后不新增功能，直接继承无障碍实现。
}

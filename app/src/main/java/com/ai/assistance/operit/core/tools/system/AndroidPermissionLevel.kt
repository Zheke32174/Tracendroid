package com.ai.assistance.operit.core.tools.system

/**
 * 工具权限层级。
 *
 * ROOT 与 DEBUGGER 通道随 § 4.4 一并移除（见 docs/THREAT_MODEL.md）。
 * 当前仅保留三档：
 * - STANDARD: 基础权限，不需要特殊权限
 * - ACCESSIBILITY: 需要无障碍服务的权限（唯一特权自动化通道）
 * - ADMIN: 需要设备管理员权限
 */
enum class AndroidPermissionLevel {
    STANDARD,
    ACCESSIBILITY,
    ADMIN;

    companion object {
        /**
         * 从字符串转换为权限等级。
         * 历史持久化值 "ROOT" 与 "DEBUGGER" 静默降级为 STANDARD —— 它们的特权通道已删除。
         */
        fun fromString(value: String?): AndroidPermissionLevel {
            return when (value?.uppercase()) {
                "ACCESSIBILITY" -> ACCESSIBILITY
                "ADMIN" -> ADMIN
                else -> STANDARD
            }
        }
    }
}

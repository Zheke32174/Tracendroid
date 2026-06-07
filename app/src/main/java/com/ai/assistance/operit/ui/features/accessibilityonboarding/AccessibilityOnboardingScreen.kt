package com.ai.assistance.operit.ui.features.accessibilityonboarding

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.tools.system.AccessibilityProviderInstaller
import com.ai.assistance.operit.data.repository.UIHierarchyManager
import com.ai.assistance.operit.ui.features.demo.wizards.AccessibilityWizardCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 无障碍引导屏幕 —— 唯一特权自动化通道的设置入口。
 *
 * 按 docs/SECURITY.md § 8 与 docs/THREAT_MODEL.md § 4.4 的设定，AccessibilityService
 * 是 v1 中唯一保留的特权自动化通道。屏幕承载 [AccessibilityWizardCard] 并提供两条
 * 行动路径：
 *   - 安装内置的 provider APK（若尚未安装或版本更旧）
 *   - 打开系统 Accessibility 设置页面，由用户在 Android 原生流程中授权
 *
 * 没有任何路径会绕开 Android 原生的授权确认（无 Shizuku 后门、无 root、无回退）。
 */
@Composable
fun AccessibilityOnboardingScreen(
    onNavigateBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isProviderInstalled by remember { mutableStateOf(false) }
    var isServiceEnabled by remember { mutableStateOf(false) }
    var installedVersion by remember { mutableStateOf<String?>(null) }
    var bundledVersion by remember { mutableStateOf("") }
    var updateNeeded by remember { mutableStateOf(false) }
    var showWizard by remember { mutableStateOf(true) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        val ctx = context.applicationContext
        withContext(Dispatchers.IO) {
            val provInstalled = UIHierarchyManager.isProviderAppInstalled(ctx)
            val installed = AccessibilityProviderInstaller.getInstalledVersion(ctx)
            val bundled = AccessibilityProviderInstaller.getBundledVersion(ctx)
            val needsUpdate = UIHierarchyManager.isUpdateNeeded(ctx)
            val serviceOn = if (provInstalled) {
                UIHierarchyManager.bindToService(ctx)
                UIHierarchyManager.isAccessibilityServiceEnabled(ctx)
            } else {
                false
            }
            withContext(Dispatchers.Main) {
                isProviderInstalled = provInstalled
                installedVersion = installed
                bundledVersion = bundled
                updateNeeded = needsUpdate
                isServiceEnabled = serviceOn
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Header()

        AccessibilityWizardCard(
            isProviderInstalled = isProviderInstalled,
            isServiceEnabled = isServiceEnabled,
            showWizard = showWizard,
            onToggleWizard = { showWizard = !showWizard },
            onInstallProvider = {
                AccessibilityProviderInstaller.launchInstall(context)
                coroutineScope.launch {
                    // Give the system installer time to update package state.
                    kotlinx.coroutines.delay(800)
                    refreshTick++
                }
            },
            onOpenAccessibilitySettings = {
                openAccessibilitySystemSettings(context)
                coroutineScope.launch {
                    kotlinx.coroutines.delay(400)
                    refreshTick++
                }
            },
            updateNeeded = updateNeeded,
            installedVersion = installedVersion,
            bundledVersion = bundledVersion,
            onUpdateProvider = {
                AccessibilityProviderInstaller.launchInstall(context)
                coroutineScope.launch {
                    kotlinx.coroutines.delay(800)
                    refreshTick++
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { refreshTick++ },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Refresh status")
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Accessibility,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = "Accessibility — privileged automation channel",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "The only privileged channel this build uses. See docs/SECURITY.md § 8.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun openAccessibilitySystemSettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

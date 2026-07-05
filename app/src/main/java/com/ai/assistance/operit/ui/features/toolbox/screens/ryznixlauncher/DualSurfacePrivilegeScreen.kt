/*
 * DualSurfacePrivilegeScreen — Tracendroid cornerstone #3: dual-surface privilege wiring.
 *
 * THE THESIS (one co-operator, two surfaces):
 *   1. BARE-METAL (the Android host): the app's existing elevation layer. Its HONEST ceiling here
 *      is NOT root. This Operit fork deliberately removed the ROOT/DEBUGGER (su/Shizuku-uid-2000)
 *      channels (see docs/THREAT_MODEL.md § 4.4); what remains is STANDARD (the app's own uid),
 *      ACCESSIBILITY (the automation channel), and ADMIN (Device Administrator / device-owner via
 *      DevicePolicyManager). So the strongest thing the co-operator can be ON THE PHONE ITSELF is
 *      a device administrator — powerful, but still sandboxed; the phone is never rooted.
 *   2. RYZNIX CONTAINER (the QEMU guest): real, VM-scoped root, brokered and enforced by ryz-ksud
 *      over 127.0.0.1:8710 (reachable only while the VM is running — see RyzKsudClient.kt). Inside
 *      the guest a granted profile really is uid=0; the enforcement is Yojimbo-authored policy.
 *
 * This screen surfaces BOTH truthfully, side by side, and proves the container tier with a live
 * GRANT/DENY demo. It reuses the app's ShellExecutorFactory for the host tier and RyzKsudClient
 * for the container tier. It fabricates nothing: a DENY is shown as DENY, an absent VM as
 * "unreachable", and it never claims host root.
 */
package com.ai.assistance.operit.ui.features.toolbox.screens.ryznixlauncher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.core.tools.system.shell.ShellExecutorFactory
import kotlinx.coroutines.launch

/** How many ryz-ksud log lines to pull for the log view. */
private const val LOG_LIMIT = 30

/** What container-tier query, if any, is in flight (drives spinners + disabled buttons). */
private enum class Busy { NONE, STATUS, DEMO, LOG }

/** Snapshot of one host (bare-metal) privilege tier, read from the app's existing shell layer. */
private data class HostTier(
    val level: AndroidPermissionLevel,
    val available: Boolean,
    val reason: String,
)

/**
 * The dual-surface privilege screen. Self-contained: host tier is read synchronously from the
 * existing ShellExecutorFactory; container tier is queried via suspend calls to RyzKsudClient.
 * No ViewModel — this is a status/proof panel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DualSurfacePrivilegeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- Bare-metal (host) state: recomputed on demand from the app's existing shell executors. --
    var hostTiers by remember { mutableStateOf(readHostTiers(context)) }

    // --- ryznix container (ryz-ksud) state. -----------------------------------------------------
    var busy by remember { mutableStateOf(Busy.NONE) }
    var ksudStatus by remember { mutableStateOf<RyzKsudStatus?>(null) }
    // A truthful line describing the last container-tier outcome (or why it was unreachable).
    var ksudNote by remember { mutableStateOf<String?>(null) }
    var demoGrant by remember { mutableStateOf<RyzKsudSuResult?>(null) }
    var demoDeny by remember { mutableStateOf<RyzKsudSuResult?>(null) }
    var demoNote by remember { mutableStateOf<String?>(null) }
    var logLines by remember { mutableStateOf<List<RyzKsudLogEntry>?>(null) }

    // Query ryz-ksud status once on entry (honestly reports unreachable if the VM is down).
    LaunchedEffect(Unit) {
        busy = Busy.STATUS
        val res = RyzKsudClient.status()
        ksudStatus = RyzKsudClient.parseStatus(res)
        ksudNote = if (res is RyzKsudResult.Unreachable) res.reason else null
        busy = Busy.NONE
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ThesisCard()

        BareMetalTierCard(
            tiers = hostTiers,
            onRefresh = { hostTiers = readHostTiers(context) },
        )

        ContainerTierCard(
            status = ksudStatus,
            note = ksudNote,
            busy = busy,
            onRefresh = {
                scope.launch {
                    busy = Busy.STATUS
                    val res = RyzKsudClient.status()
                    ksudStatus = RyzKsudClient.parseStatus(res)
                    ksudNote = if (res is RyzKsudResult.Unreachable) res.reason else null
                    busy = Busy.NONE
                }
            },
        )

        ContainerDemoCard(
            grant = demoGrant,
            deny = demoDeny,
            note = demoNote,
            busy = busy,
            onRunDemo = {
                scope.launch {
                    busy = Busy.DEMO
                    demoNote = null
                    // 1) A trusted profile — the policy is expected to GRANT (uid=0 inside the VM).
                    val grantRes = RyzKsudClient.su(RYZ_KSUD_TRUSTED_DEMO_KEY, listOf("id"))
                    // 2) An unknown profile — the policy is expected to DENY. Proves enforcement.
                    val denyRes = RyzKsudClient.su(RYZ_KSUD_UNTRUSTED_DEMO_KEY, listOf("id"))
                    demoGrant = RyzKsudClient.parseSu(grantRes)
                    demoDeny = RyzKsudClient.parseSu(denyRes)
                    demoNote = when {
                        grantRes is RyzKsudResult.Unreachable -> grantRes.reason
                        denyRes is RyzKsudResult.Unreachable -> denyRes.reason
                        else -> null
                    }
                    busy = Busy.NONE
                }
            },
        )

        ContainerLogCard(
            entries = logLines,
            busy = busy,
            onLoadLog = {
                scope.launch {
                    busy = Busy.LOG
                    val res = RyzKsudClient.getLog(LOG_LIMIT)
                    logLines = RyzKsudClient.parseLog(res)
                    if (res is RyzKsudResult.Unreachable) {
                        logLines = emptyList()
                        ksudNote = res.reason
                    }
                    busy = Busy.NONE
                }
            },
        )

        HonestyFooter()
    }
}

// -------------------------------------------------------------------------------------------------
// Thesis
// -------------------------------------------------------------------------------------------------

@Composable
private fun ThesisCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "Dual-surface privilege",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "One co-operator, two surfaces — a device-admin ceiling on bare-metal " +
                    "Android, real VM-scoped root inside ryznix; the phone is never rooted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Bare-metal (host) tier
// -------------------------------------------------------------------------------------------------

@Composable
private fun BareMetalTierCard(
    tiers: List<HostTier>,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            TierHeader(
                icon = Icons.Default.PhoneAndroid,
                title = "Bare-metal Android (host)",
                onRefresh = onRefresh,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "The app's existing elevation layer. Its ceiling here is Device " +
                    "Administrator — there is NO host root in this build (the su / uid-2000 " +
                    "channels were removed per the threat model). Highest available tier below " +
                    "is what the co-operator can do on the phone itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            tiers.forEach { tier ->
                HostTierRow(tier)
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(4.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
            val ceiling = tiers.filter { it.available }.maxByOrNull { it.level.ordinal }
            Text(
                text = "Effective host capability: " +
                    (ceiling?.let { levelLabel(it.level) } ?: "STANDARD (app uid only)") +
                    ". This is a sandboxed Android app — not root.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun HostTierRow(tier: HostTier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusIcon(tier.available)
        Spacer(Modifier.size(8.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = levelLabel(tier.level),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (tier.available) "granted — ${hostTierCapability(tier.level)}"
                else "unavailable — ${tier.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// ryznix container tier — status
// -------------------------------------------------------------------------------------------------

@Composable
private fun ContainerTierCard(
    status: RyzKsudStatus?,
    note: String?,
    busy: Busy,
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            TierHeader(
                icon = Icons.Default.Dns,
                title = "ryznix container (guest VM)",
                onRefresh = onRefresh,
                spinning = busy == Busy.STATUS,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Real, VM-scoped root brokered by ryz-ksud at $RYZ_KSUD_ENDPOINT " +
                    "(reachable only while the ryznix VM is running). Enforcement is " +
                    "Yojimbo-authored policy inside the guest.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            if (status != null) {
                KeyVal("broker", if (status.ok) "online (v${status.version})" else "reachable but not ok")
                KeyVal("mode", status.mode)
                KeyVal("enforced", if (status.enforced) "YES (policy v${status.policyVersion})" else "NO")
                KeyVal("kernel module", if (status.kernelPresent) "present" else "absent (userspace broker)")
                KeyVal("uid inside guest", status.uid)
                KeyVal("scope", status.host)
                if (status.profiles.isNotEmpty()) {
                    KeyVal("profiles", status.profiles.joinToString(", "))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusIcon(false)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "ryz-ksud unreachable",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (note != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Boot the guest from the ryznix launcher, then Refresh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// ryznix container tier — GRANT / DENY demo
// -------------------------------------------------------------------------------------------------

@Composable
private fun ContainerDemoCard(
    grant: RyzKsudSuResult?,
    deny: RyzKsudSuResult?,
    note: String?,
    busy: Busy,
    onRunDemo: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "Enforced-root proof (su demo)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Runs two SU requests through ryz-ksud: a trusted profile " +
                    "('$RYZ_KSUD_TRUSTED_DEMO_KEY') that the policy GRANTS (id -> uid=0 inside " +
                    "the guest), and an unknown one ('$RYZ_KSUD_UNTRUSTED_DEMO_KEY') that it " +
                    "DENIES. The decisions are exactly what the broker returns — never faked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onRunDemo,
                enabled = busy == Busy.NONE,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy == Busy.DEMO) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.size(6.dp))
                Text("Run GRANT / DENY demo")
            }

            if (grant != null || deny != null) {
                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(10.dp))
                grant?.let { SuDecisionRow(key = RYZ_KSUD_TRUSTED_DEMO_KEY, result = it) }
                deny?.let {
                    Spacer(Modifier.height(10.dp))
                    SuDecisionRow(key = RYZ_KSUD_UNTRUSTED_DEMO_KEY, result = it)
                }
            }
            if (note != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SuDecisionRow(key: String, result: RyzKsudSuResult) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(result.granted)
            Spacer(Modifier.size(8.dp))
            Text(
                text = "$key -> ${result.decision}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (result.granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            )
        }
        val detail = when {
            result.granted && result.stdout.isNotBlank() -> result.stdout.trim()
            result.granted -> "granted (rc=${result.rc})"
            result.stderr.isNotBlank() -> result.stderr.trim()
            else -> "denied by policy (rc=${result.rc})"
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 28.dp),
        )
    }
}

// -------------------------------------------------------------------------------------------------
// ryznix container tier — su log
// -------------------------------------------------------------------------------------------------

@Composable
private fun ContainerLogCard(
    entries: List<RyzKsudLogEntry>?,
    busy: Busy,
    onLoadLog: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "ryz-ksud su log",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedButton(onClick = onLoadLog, enabled = busy == Busy.NONE) {
                    if (busy == Busy.LOG) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Load", modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.size(6.dp))
                    Text("Load")
                }
            }
            Spacer(Modifier.height(8.dp))
            when {
                entries == null -> Text(
                    text = "Load the broker's recent GRANT/DENY decisions (last $LOG_LIMIT).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entries.isEmpty() -> Text(
                    text = "No log entries (empty, or the VM is not running).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> entries.forEach { entry ->
                    Text(
                        text = entry.line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Footer + shared bits
// -------------------------------------------------------------------------------------------------

@Composable
private fun HonestyFooter() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "What is and isn't claimed",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "• Host: sandboxed Android app; ceiling is Device Administrator, NOT root.\n" +
                    "• Container: real uid=0, but ONLY inside the ryznix guest VM, and only for " +
                    "profiles the ryz-ksud policy GRANTS.\n" +
                    "• A DENY is a real policy decision; 'unreachable' means the VM is not running. " +
                    "No grant is ever fabricated on the client.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TierHeader(
    icon: ImageVector,
    title: String,
    onRefresh: () -> Unit,
    spinning: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        OutlinedButton(onClick = onRefresh, enabled = !spinning) {
            if (spinning) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun KeyVal(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StatusIcon(ok: Boolean) {
    if (ok) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = "available",
            tint = Color(0xFF2E7D32),
            modifier = Modifier.size(18.dp),
        )
    } else {
        Icon(
            Icons.Default.Cancel,
            contentDescription = "unavailable",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Pure helpers
// -------------------------------------------------------------------------------------------------

private fun levelLabel(level: AndroidPermissionLevel): String = when (level) {
    AndroidPermissionLevel.STANDARD -> "STANDARD (app uid)"
    AndroidPermissionLevel.ACCESSIBILITY -> "ACCESSIBILITY (automation)"
    AndroidPermissionLevel.ADMIN -> "ADMIN (device administrator)"
}

private fun hostTierCapability(level: AndroidPermissionLevel): String = when (level) {
    AndroidPermissionLevel.STANDARD -> "run commands as the app's own uid"
    AndroidPermissionLevel.ACCESSIBILITY -> "drive the UI via the accessibility service"
    AndroidPermissionLevel.ADMIN -> "device-policy actions (lock, wipe, policy)"
}

/**
 * Read the host (bare-metal) privilege tiers from the app's existing shell-executor layer.
 * Synchronous and side-effect-free: it only queries availability + permission status; it does not
 * request anything. Returned lowest-to-highest so the UI can pick the ceiling.
 */
private fun readHostTiers(context: android.content.Context): List<HostTier> {
    return listOf(
        AndroidPermissionLevel.STANDARD,
        AndroidPermissionLevel.ACCESSIBILITY,
        AndroidPermissionLevel.ADMIN,
    ).map { level ->
        try {
            val executor = ShellExecutorFactory.getExecutor(context, level)
            val perm = executor.hasPermission()
            HostTier(
                level = level,
                available = executor.isAvailable() && perm.granted,
                reason = perm.reason,
            )
        } catch (e: Exception) {
            HostTier(level = level, available = false, reason = e.message ?: "unavailable")
        }
    }
}

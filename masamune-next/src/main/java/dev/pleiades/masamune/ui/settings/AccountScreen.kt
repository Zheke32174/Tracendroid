package dev.pleiades.masamune.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import dev.pleiades.masamune.R
import dev.pleiades.masamune.ai.auth.AccountSession
import dev.pleiades.masamune.ai.auth.AuthMode
import dev.pleiades.masamune.ai.auth.OAuthCatalog
import dev.pleiades.masamune.ai.auth.OAuthProfile
import dev.pleiades.masamune.ai.auth.SignInPhase
import dev.pleiades.masamune.ui.components.KeyValueRow
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.components.SectionCard
import dev.pleiades.masamune.ui.masamuneViewModel
import dev.pleiades.masamune.ui.theme.MasamuneTheme
import java.text.DateFormat
import java.util.Date

/**
 * Account. About → Account, and the person icon on the Chat header.
 *
 * This screen exists because the module had exactly one credential model — a pasted API key —
 * and that is the wrong model for someone who pays for subscriptions. It offers account
 * sign-in as the primary path and leaves the key as a fallback.
 *
 * Its shape mirrors the donor teardowns rather than being invented: the hero "identity •
 * provider" line, the "Valid until" / "Expired" status vocabulary, the "Manage" block with
 * Refresh / Run test / Sign out, and the stepwise progress dialog all come from RethinkDNS,
 * which is the only donor in the teardown set with a real subscription surface. The
 * per-account "Credentials" block follows Amaze's Cloud Connection rows and Xed Editor's Git
 * credentials screen. The API-key framing follows App Manager's VirusTotal key preference,
 * which describes what a key *enables* and never treats it as required.
 *
 * No dead controls: a provider that cannot be signed into from this device renders disabled
 * with the specific missing thing printed underneath it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountScreen() {
    val vm = masamuneViewModel { ctx -> AccountViewModel(ctx) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.refreshGrants() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {

        // --- hero: who is signed in, and until when ------------------------------------------
        val activeProfile = OAuthCatalog.byId(state.activeProfileId)
        val activeSession = activeProfile?.let { state.sessionFor(it.id) }
        SectionCard(
            // "identity • provider", the shape RethinkDNS uses for hero_plan_and_account.
            title = activeSession?.let { session ->
                stringResource(
                    R.string.account_hero_fmt,
                    session.identity?.display ?: "connected account",
                    activeProfile?.label.orEmpty(),
                )
            } ?: stringResource(R.string.account_hero_signed_out),
            subtitle = if (activeSession != null) {
                stringResource(R.string.account_subtitle)
            } else {
                stringResource(R.string.account_hero_signed_out_body)
            },
        ) {
            activeSession?.let { SessionFacts(it) }
        }

        // --- gates that would make every button below a lie ------------------------------------
        if (!state.networkGranted) {
            Notice(
                title = "NETWORK capability not granted",
                body = "Every step of a sign-in is an outbound request, and the gate denies " +
                    "those for caller \"user\" by default. Nothing is sent until this is granted.",
                tone = NoticeTone.BLOCKED,
                actionLabel = "Grant NETWORK to \"user\"",
                onAction = { vm.grantNetwork() },
            )
        }
        state.vaultReason?.let {
            Notice(
                title = "Token sealing unavailable",
                body = it,
                tone = NoticeTone.ERROR,
            )
        }

        // --- sign-in method --------------------------------------------------------------------
        SectionCard(
            title = stringResource(R.string.account_cat_method),
            subtitle = stringResource(R.string.account_cat_method_desc),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.mode == AuthMode.SUBSCRIPTION,
                    onClick = { vm.setMode(AuthMode.SUBSCRIPTION) },
                    label = { Text(stringResource(R.string.account_mode_subscription)) },
                )
                FilterChip(
                    selected = state.mode == AuthMode.API_KEY,
                    onClick = { vm.setMode(AuthMode.API_KEY) },
                    label = { Text(stringResource(R.string.account_mode_api_key)) },
                )
            }
            Text(
                stringResource(
                    if (state.mode == AuthMode.SUBSCRIPTION) {
                        R.string.account_mode_subscription_desc
                    } else {
                        R.string.account_mode_api_key_desc
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MasamuneTheme.semantic.dim,
            )
        }

        // --- one row per provider ---------------------------------------------------------------
        SectionCard(
            title = stringResource(R.string.account_cat_providers),
            subtitle = stringResource(R.string.account_cat_providers_desc),
        ) {
            OAuthCatalog.all.forEachIndexed { index, profile ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ProviderRow(
                    profile = profile,
                    state = state,
                    onSaveClient = { id, secret, issuer ->
                        vm.saveRegistration(profile, id, secret, issuer)
                    },
                    onForgetClient = { vm.forgetRegistration(profile) },
                    onDiscover = { vm.discover(it) },
                onOpenConsole = { vm.openConsole(profile.id) },
                    onSignIn = { vm.signIn(profile) },
                    onSignOut = { vm.signOut(profile) },
                    onRefresh = { vm.refreshNow(profile) },
                    onTest = { vm.runTest(profile) },
                    onUseForChat = { vm.useForChat(profile) },
                )
            }
        }

        state.message?.let {
            Notice(
                title = if (state.messageOk) {
                    stringResource(R.string.account_result_ok)
                } else {
                    stringResource(R.string.account_error_authenticate)
                },
                body = it,
                tone = if (state.messageOk) NoticeTone.SUCCESS else NoticeTone.ERROR,
                actionLabel = "Dismiss",
                onAction = { vm.dismissMessage() },
            )
        }

        Notice(
            title = stringResource(R.string.account_cat_storage),
            body = stringResource(R.string.account_storage_body),
            tone = NoticeTone.INFO,
        )
    }

    // --- the in-flight dialog -------------------------------------------------------------------
    if (state.busyProfileId != null) {
        AlertDialog(
            onDismissRequest = { /* deliberately sticky: cancel is an explicit button */ },
            title = { Text(stringResource(R.string.account_device_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        when (state.phase) {
                            SignInPhase.REGISTERING_CLIENT ->
                                stringResource(R.string.account_step_registering)
                            SignInPhase.REQUESTING_CODE ->
                                stringResource(R.string.account_device_step_requesting)
                            SignInPhase.AWAITING_APPROVAL ->
                                stringResource(R.string.account_device_step_waiting)
                            SignInPhase.EXCHANGING ->
                                stringResource(R.string.account_device_step_exchanging)
                            SignInPhase.RESOLVING_IDENTITY ->
                                stringResource(R.string.account_device_step_identity)
                            null -> stringResource(R.string.account_redirect_step_browser)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.deviceAuth?.let { auth ->
                        Text(
                            stringResource(R.string.account_device_code_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MasamuneTheme.semantic.dim,
                        )
                        SelectionContainer {
                            Text(
                                auth.userCode,
                                style = MaterialTheme.typography.headlineSmall
                                    .copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                        val target = auth.verificationUriComplete ?: auth.verificationUri
                        if (target.isNotBlank()) {
                            OutlinedButton(onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(target))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }) { Text(stringResource(R.string.account_device_open)) }
                            Text(
                                target,
                                style = MaterialTheme.typography.bodySmall,
                                color = MasamuneTheme.semantic.dim,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.account_device_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MasamuneTheme.semantic.dim,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.cancelSignIn() }) {
                    Text(stringResource(R.string.account_cancel))
                }
            },
        )
    }
}

/**
 * One provider. Renders in exactly one of three shapes: blocked (disabled, with the reason),
 * signed out (credentials + a Sign in button that is only enabled when it can actually run),
 * or signed in (facts + Refresh / Run test / Sign out).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderRow(
    profile: OAuthProfile,
    state: AccountUiState,
    onSaveClient: (String, String, String) -> Unit,
    onForgetClient: () -> Unit,
    onDiscover: (String) -> Unit,
    onOpenConsole: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onRefresh: () -> Unit,
    onTest: () -> Unit,
    onUseForChat: () -> Unit,
) {
    val registration = state.registrationFor(profile.id)
    val session = state.sessionFor(profile.id)
    var clientId by remember(registration?.clientId) { mutableStateOf(registration?.clientId.orEmpty()) }
    var clientSecret by remember(registration?.clientSecret) {
        mutableStateOf(registration?.clientSecret.orEmpty())
    }
    var issuer by remember(registration?.issuer) { mutableStateOf(registration?.issuer.orEmpty()) }
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    statusLine(profile, session, state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MasamuneTheme.semantic.dim,
                )
            }
            if (state.isBusy(profile.id)) {
                CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp))
            }
        }

        if (profile.isBlocked) {
            // The single most important control on this screen is the one that is NOT here.
            Notice(
                title = "Sign-in unavailable for ${profile.label}",
                body = profile.blockedReason.orEmpty(),
                tone = NoticeTone.BLOCKED,
            )
            return@Column
        }

        if (session == null) {
            if (profile.isCustom) {
                OutlinedTextField(
                    value = issuer,
                    onValueChange = { issuer = it },
                    label = { Text(stringResource(R.string.account_field_issuer)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    enabled = issuer.isNotBlank() && state.networkGranted && !state.isBusy(profile.id),
                    onClick = { onDiscover(issuer) },
                ) { Text(stringResource(R.string.account_action_discover)) }
                // A disabled control always names its own blocker, in place.
                DisabledReason(
                    when {
                        issuer.isBlank() -> "Discovery is disabled until an issuer URL is entered."
                        !state.networkGranted ->
                            "Discovery is disabled: NETWORK is not granted to \"user\"."
                        else -> null
                    }
                )
                state.discovered?.let {
                    KeyValueRow(stringResource(R.string.account_lbl_issuer), it.issuer, mono = true)
                    KeyValueRow(stringResource(R.string.account_lbl_grant), it.grant.name)
                }
            }

            // SIGN IN COMES FIRST. This screen used to lead with a Client ID box, which made
            // signing in a configuration task and broke the project's own rule — if it has to ask
            // you for the thing it replaces, it has not replaced it. The button is the primary
            // control now; credentials appear only for a provider that genuinely cannot issue a
            // client on request, and then as the explanation for why the button is disabled.
            val oneTap = state.isOneTap(profile.id)
            val shipped = profile.id in state.shippedClients
            val block = signInBlockReason(profile, state)
            Button(
                enabled = block == null && !state.isBusy(profile.id),
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.account_action_sign_in)) }
            // Rule: a disabled control always says why, in the same breath.
            DisabledReason(block)

            if (shipped) {
                Text(
                    stringResource(R.string.account_shipped_client),
                    style = MaterialTheme.typography.bodySmall,
                    color = MasamuneTheme.semantic.dim,
                )
            } else if (oneTap && registration == null) {
                Text(
                    stringResource(R.string.account_self_register_ready),
                    style = MaterialTheme.typography.bodySmall,
                    color = MasamuneTheme.semantic.dim,
                )
            }
            if (registration?.selfRegistered == true) {
                Text(
                    stringResource(R.string.account_self_register_done),
                    style = MaterialTheme.typography.bodySmall,
                    color = MasamuneTheme.semantic.dim,
                )
                OutlinedButton(onClick = onForgetClient) {
                    Text(stringResource(R.string.account_action_forget_client))
                }
            }

            // The credentials form. Present only when this provider will not issue a client on
            // request — where it will, showing these boxes would invite the user to do work the
            // app already does, and an empty box next to a working button reads as broken.
            if (!oneTap && registration?.isAutomatic != true) {
                // The shortest honest path for a provider that will not issue clients on request:
                // link straight at the page that creates one, instead of describing where it is.
                state.consoleUrls[profile.id]?.let {
                    OutlinedButton(
                        onClick = { onOpenConsole() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.account_action_open_console)) }
                }
                Text(
                    stringResource(R.string.account_cat_client),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    stringResource(R.string.account_cat_client_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MasamuneTheme.semantic.dim,
                )
                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text(stringResource(R.string.account_field_client_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = clientSecret,
                    onValueChange = { clientSecret = it },
                    label = { Text(stringResource(R.string.account_field_client_secret)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    profile.clientHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MasamuneTheme.semantic.dim,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSaveClient(clientId, clientSecret, issuer) }) {
                        Text(stringResource(R.string.account_action_save_client))
                    }
                    if (registration != null) {
                        OutlinedButton(onClick = onForgetClient) {
                            Text(stringResource(R.string.account_action_forget_client))
                        }
                    }
                }
            }
        } else {
            SessionFacts(session)
            if (session.isExpired() && !session.canRefresh) {
                Notice(
                    title = stringResource(R.string.account_error_token_lost),
                    body = "The stored token for ${profile.label} is past its expiry and no " +
                        "refresh token was issued with it, so it cannot be renewed in the " +
                        "background. Sign in again to get a working session.",
                    tone = NoticeTone.ERROR,
                )
            }
            if (!session.canRefresh) {
                Text(
                    stringResource(R.string.account_lbl_no_refresh_token),
                    style = MaterialTheme.typography.bodySmall,
                    color = MasamuneTheme.semantic.dim,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = session.canRefresh && state.networkGranted && !state.isBusy(profile.id),
                    onClick = onRefresh,
                ) { Text(stringResource(R.string.account_action_refresh)) }

                OutlinedButton(
                    enabled = state.networkGranted && !state.isBusy(profile.id),
                    onClick = onTest,
                ) { Text(stringResource(R.string.account_action_run_test)) }

                OutlinedButton(onClick = { confirmSignOut = true }) {
                    Text(stringResource(R.string.account_action_sign_out))
                }
            }
            DisabledReason(
                when {
                    !state.networkGranted ->
                        "Refresh and Run test are disabled: NETWORK is not granted to " +
                            "\"user\", so no request can leave the device. Sign out still works " +
                            "— it clears the local session either way."
                    !session.canRefresh ->
                        "Refresh is disabled: this session has no refresh token."
                    else -> null
                }
            )
            if (state.activeProfileId == profile.id && state.mode == AuthMode.SUBSCRIPTION) {
                Text(
                    stringResource(R.string.account_in_use),
                    style = MaterialTheme.typography.labelMedium,
                    color = MasamuneTheme.semantic.success,
                )
            } else {
                TextButton(onClick = onUseForChat) {
                    Text(stringResource(R.string.account_action_use_this))
                }
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.account_signout_title)) },
            text = { Text(stringResource(R.string.account_signout_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    onSignOut()
                }) { Text(stringResource(R.string.account_action_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) {
                    Text(stringResource(R.string.account_cancel))
                }
            },
        )
    }
}

/** One sentence saying why the control above it is disabled. Renders nothing when null. */
@Composable
private fun DisabledReason(reason: String?) {
    if (reason == null) return
    Text(
        reason,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun SessionFacts(session: AccountSession) {
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    session.identity?.let {
        KeyValueRow(stringResource(R.string.account_lbl_signed_in_as), it.display)
    }
    KeyValueRow(
        if (session.isExpired()) {
            stringResource(R.string.account_lbl_expired)
        } else {
            stringResource(R.string.account_lbl_valid_until)
        },
        format.format(Date(session.expiresAt)),
    )
    if (!session.isExpired() && session.needsRefresh()) {
        Text(
            stringResource(R.string.account_lbl_grace),
            style = MaterialTheme.typography.bodySmall,
            color = MasamuneTheme.semantic.warning,
        )
    }
    if (session.scope.isNotBlank()) {
        KeyValueRow(stringResource(R.string.account_lbl_scope), session.scope, mono = true)
    }
}

/** Status vocabulary lifted from RethinkDNS: Inactive / Expired / Signed in. */
@Composable
private fun statusLine(
    profile: OAuthProfile,
    session: AccountSession?,
    state: AccountUiState,
): String = when {
    profile.isBlocked -> stringResource(R.string.account_lbl_inactive)
    session == null -> stringResource(R.string.account_hero_signed_out)
    session.isExpired() && !session.canRefresh -> stringResource(R.string.account_lbl_expired)
    state.activeProfileId == profile.id && state.mode == AuthMode.SUBSCRIPTION ->
        stringResource(R.string.account_in_use)
    else -> stringResource(R.string.account_chat_mode_subscription)
}

/**
 * The exact reason a Sign in button is disabled, or null when it will actually run. Every
 * branch names one concrete missing thing rather than saying "not configured".
 */
private fun signInBlockReason(profile: OAuthProfile, state: AccountUiState): String? = when {
    profile.blockedReason != null -> profile.blockedReason
    state.vaultReason != null -> state.vaultReason
    !state.networkGranted ->
        "Grant NETWORK to \"user\" above first — a sign-in is a sequence of outbound requests " +
            "and the gate denies them by default."
    // A provider that issues clients on request is NOT blocked by not having one yet — the
    // sign-in itself registers. Only a provider that cannot do that needs the form filled first.
    state.registrationFor(profile.id)?.isComplete != true && !state.isOneTap(profile.id) ->
        "This provider does not hand out OAuth clients on request, so one has to be created with " +
            "it first, then pasted below. ${profile.clientHint}"
    profile.isCustom && state.registrationFor(profile.id)?.issuer.isNullOrBlank() ->
        "Enter the issuer URL and tap \"Discover endpoints\" — the endpoints are read from the " +
            "issuer's own metadata document, never guessed."
    else -> null
}

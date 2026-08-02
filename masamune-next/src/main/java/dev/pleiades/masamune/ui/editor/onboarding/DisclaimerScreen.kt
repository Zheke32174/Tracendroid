package dev.pleiades.masamune.ui.editor.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.R
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.components.SectionCard
import dev.pleiades.masamune.ui.masamuneViewModel
import dev.pleiades.masamune.ui.theme.MasamuneTheme

/**
 * Disclaimer & Consent (DONOR-SURFACES section 0, Xed — canonical).
 *
 * The five disclosure sections, the consent statement and the Decline / I Accept buttons are ported
 * one-for-one from the Xed teardown; the two sections the teardown left without body text
 * (Terminal Risks, Third-Party Extensions) carry this app's own honest statement of the real risk
 * rather than a fabricated donor claim. Consent is resettable — matching the donor's
 * `reset_consent` / `reset_consent_desc` — and its current status is shown plainly.
 */
@Composable
fun DisclaimerScreen(onDone: () -> Unit) {
    val vm = masamuneViewModel { ctx -> OnboardingViewModel(ctx) }
    val accepted by vm.accepted.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.editor_disclaimer_heading), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.editor_disclaimer_read_carefully),
            style = MaterialTheme.typography.bodyMedium,
            color = MasamuneTheme.semantic.dim,
        )

        DisclaimerSection(R.string.editor_data_loss_risk, R.string.editor_data_loss_risk_content)
        DisclaimerSection(R.string.editor_terminal_risks, R.string.editor_terminal_risks_content)
        DisclaimerSection(R.string.editor_third_party_ext, R.string.editor_third_party_ext_content)
        DisclaimerSection(R.string.editor_no_warranty, R.string.editor_no_warranty_content)
        DisclaimerSection(R.string.editor_not_liable, R.string.editor_not_liable_content)

        Text(
            stringResource(R.string.editor_consent_statement),
            style = MaterialTheme.typography.bodyMedium,
        )

        Notice(
            title = stringResource(R.string.editor_reset_consent),
            body = stringResource(
                if (accepted) R.string.editor_consent_status_accepted
                else R.string.editor_consent_status_not_accepted
            ) + " — " + stringResource(R.string.editor_reset_consent_desc),
            tone = if (accepted) NoticeTone.SUCCESS else NoticeTone.INFO,
            actionLabel = stringResource(R.string.editor_reset_consent),
            onAction = { vm.reset() },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { vm.decline(); onDone() },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.editor_decline)) }
            Button(
                onClick = { vm.accept(); onDone() },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.editor_i_accept)) }
        }

        TextButton(onClick = onDone) { Text(stringResource(R.string.editor_close)) }
    }
}

@Composable
private fun DisclaimerSection(titleRes: Int, bodyRes: Int) {
    SectionCard(title = stringResource(titleRes)) {
        Text(stringResource(bodyRes), style = MaterialTheme.typography.bodyMedium)
    }
}

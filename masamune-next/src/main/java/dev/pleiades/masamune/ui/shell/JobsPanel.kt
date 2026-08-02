package dev.pleiades.masamune.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pleiades.masamune.R
import dev.pleiades.masamune.ui.components.Notice
import dev.pleiades.masamune.ui.components.NoticeTone
import dev.pleiades.masamune.ui.theme.MasamuneTheme

/**
 * Background-jobs panel (DONOR-SURFACES §4 line 91): list · read output · exit-code summary · stop.
 *
 * The list, read-output and exit-code summary are fully backed — each dispatch is correlated by
 * its own execId. Stop is not: a background RUN_COMMAND has no clean cancellation over the one-shot
 * contract, so [Stop] renders disabled with a sentence, and only [Dismiss] (which stops tracking a
 * job here, not the process in Termux) is offered.
 */
@Composable
fun JobsPanel(
    jobs: List<ShellJob>,
    onDismissJob: (Long) -> Unit,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MasamuneTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.terminal_jobs_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (jobs.any { it.state != ShellJobState.RUNNING }) {
                TextButton(onClick = onClearFinished) { Text(stringResource(R.string.terminal_jobs_clear_finished)) }
            }
        }

        if (jobs.isEmpty()) {
            Notice(
                title = stringResource(R.string.terminal_jobs_title),
                body = stringResource(R.string.terminal_jobs_empty),
                tone = NoticeTone.INFO,
                modifier = Modifier.padding(horizontal = MasamuneTheme.spacing.md),
            )
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(MasamuneTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.sm),
        ) {
            items(jobs, key = { it.id }) { job -> JobRow(job, onDismissJob) }
        }
    }
}

@Composable
private fun JobRow(job: ShellJob, onDismiss: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MasamuneTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.xs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$ ${job.command}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (job.state == ShellJobState.RUNNING) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
                }
            }
            Text(
                "${job.sessionName}${if (job.failsafe) " · failsafe" else ""} · ${statusSummary(job)}",
                style = MaterialTheme.typography.labelSmall,
                color = summaryColor(job),
            )

            if (job.state != ShellJobState.RUNNING) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(R.string.terminal_job_read_output))
                }
                if (expanded) JobOutput(job)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.sm)) {
                // Stop is not backable: named, disabled.
                TextButton(onClick = {}, enabled = false) { Text(stringResource(R.string.terminal_job_stop)) }
                TextButton(onClick = { onDismiss(job.id) }) { Text(stringResource(R.string.terminal_job_dismiss)) }
            }
            if (job.state == ShellJobState.RUNNING) {
                Text(
                    stringResource(R.string.terminal_job_stop_gated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MasamuneTheme.semantic.dim,
                )
            }
        }
    }
}

@Composable
private fun JobOutput(job: ShellJob) {
    Column(verticalArrangement = Arrangement.spacedBy(MasamuneTheme.spacing.xs)) {
        job.failure?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (job.stdout.isNotBlank()) {
            SelectionContainer {
                Text(
                    job.stdout.trimEnd(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
        if (job.stderr.isNotBlank()) {
            SelectionContainer {
                Text(
                    job.stderr.trimEnd(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MasamuneTheme.semantic.warning,
                )
            }
        }
        if (job.failure == null && job.stdout.isBlank() && job.stderr.isBlank()) {
            Text("(no output)", style = MaterialTheme.typography.labelSmall, color = MasamuneTheme.semantic.dim)
        }
    }
}

@Composable
private fun statusSummary(job: ShellJob): String = when (job.state) {
    ShellJobState.RUNNING -> stringResource(R.string.terminal_running)
    ShellJobState.COMPLETED -> "exit ${job.exitCode}"
    ShellJobState.REFUSED -> "refused"
    ShellJobState.DISPATCH_FAILED -> "dispatch failed"
    ShellJobState.TIMED_OUT -> "timed out"
}

@Composable
private fun summaryColor(job: ShellJob) = when (job.state) {
    ShellJobState.COMPLETED -> if (job.exitCode == 0) MasamuneTheme.semantic.success else MaterialTheme.colorScheme.error
    ShellJobState.RUNNING -> MasamuneTheme.semantic.dim
    else -> MaterialTheme.colorScheme.error
}

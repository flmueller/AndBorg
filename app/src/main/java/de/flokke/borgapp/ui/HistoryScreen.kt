package de.flokke.borgapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import de.flokke.borgapp.R
import de.flokke.borgapp.work.BackupWorker
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.flokke.borgapp.data.RunStatus

@Composable
fun HistoryScreen(vm: MainViewModel, modifier: Modifier) {
    val runs by vm.history.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LazyColumn(modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.runs_count, runs.size, runs.size), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (runs.isNotEmpty()) TextButton(onClick = vm::clearHistory) { Text(stringResource(R.string.clear)) }
            }
        }
        if (runs.isEmpty()) item { Text(stringResource(R.string.no_backups)) }
        items(runs, key = { it.id }) { r ->
            val (icon, label) = when (r.status) {
                RunStatus.RUNNING -> "⏳" to stringResource(R.string.status_running)
                RunStatus.OK -> "✅" to stringResource(R.string.status_ok)
                RunStatus.WARNING -> "⚠️" to stringResource(R.string.status_warning)
                RunStatus.ERROR -> "❌" to stringResource(R.string.status_error)
            }
            // Auslöser wird intern als „geplant“/„manuell“ gespeichert
            val trigger = when (r.trigger) {
                BackupWorker.TRIGGER_SCHEDULED -> stringResource(R.string.trigger_scheduled)
                BackupWorker.TRIGGER_MANUAL -> stringResource(R.string.trigger_manual)
                else -> r.trigger
            }
            val title = stringResource(R.string.backup_title, fmt(r.id))
            val noOutput = stringResource(R.string.no_output_yet)
            Card(Modifier.fillMaxWidth().clickable {
                vm.showText(title, r.output.ifBlank { noOutput })
            }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("$icon ${fmt(r.id)} · $trigger · $label", style = MaterialTheme.typography.bodyLarge)
                    if (r.endTime > 0) {
                        val min = (r.endTime - r.id) / 60000
                        val m = if (min < 1) "< 1" else min.toString()
                        Text(r.exitCode?.let { stringResource(R.string.duration_min_exit, m, it) } ?: stringResource(R.string.duration_min, m),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text(r.shortSummary(ctx), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

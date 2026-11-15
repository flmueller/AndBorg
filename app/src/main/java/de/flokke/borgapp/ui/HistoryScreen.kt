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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.flokke.borgapp.data.RunStatus

@Composable
fun HistoryScreen(vm: MainViewModel, modifier: Modifier) {
    val runs by vm.history.collectAsStateWithLifecycle()
    LazyColumn(modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${runs.size} Läufe", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (runs.isNotEmpty()) TextButton(onClick = vm::clearHistory) { Text("Leeren") }
            }
        }
        if (runs.isEmpty()) item { Text("Noch keine Backups.") }
        items(runs, key = { it.id }) { r ->
            val (icon, label) = when (r.status) {
                RunStatus.RUNNING -> "⏳" to "läuft"
                RunStatus.OK -> "✅" to "erfolgreich"
                RunStatus.WARNING -> "⚠️" to "Warnungen"
                RunStatus.ERROR -> "❌" to "Fehler"
            }
            Card(Modifier.fillMaxWidth().clickable {
                vm.showText("Backup ${fmt(r.id)}", r.output.ifBlank { "(noch keine Ausgabe)" })
            }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("$icon ${fmt(r.id)} · ${r.trigger} · $label", style = MaterialTheme.typography.bodyLarge)
                    if (r.endTime > 0) {
                        val min = (r.endTime - r.id) / 60000
                        Text("Dauer: ${if (min < 1) "< 1" else min} min" + (r.exitCode?.let { " · Exit-Code $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text(r.shortSummary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

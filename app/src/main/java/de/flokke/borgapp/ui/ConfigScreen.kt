package de.flokke.borgapp.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.flokke.borgapp.data.BackupConfig

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfigScreen(vm: MainViewModel, modifier: Modifier) {
    val saved by vm.config.collectAsStateWithLifecycle()
    val savedPass by vm.passphrase.collectAsStateWithLifecycle()
    // Bearbeitungsstand: wird automatisch gespeichert (kurz verzögert, damit nicht bei jedem Tastendruck).
    // Gespeicherte Werte werden nur übernommen, solange lokal nichts Neueres eingegeben wurde.
    var c by remember { mutableStateOf(saved) }
    var cBase by remember { mutableStateOf(saved) }
    LaunchedEffect(saved) { if (c == cBase) c = saved; cBase = saved }
    var pass by remember { mutableStateOf(savedPass) }
    var passBase by remember { mutableStateOf(savedPass) }
    LaunchedEffect(savedPass) { if (pass == passBase) pass = savedPass; passBase = savedPass }
    var showPass by remember { mutableStateOf(false) }
    var manualPath by remember { mutableStateOf("") }
    var excludesText by remember { mutableStateOf(saved.excludes.joinToString("\n")) }
    LaunchedEffect(saved.excludes) {
        val parsed = excludesText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (parsed != saved.excludes && c.excludes == cBase.excludes) excludesText = saved.excludes.joinToString("\n")
    }
    LaunchedEffect(excludesText) { c = c.copy(excludes = excludesText.lines().map { it.trim() }.filter { it.isNotEmpty() }) }

    val latest by rememberUpdatedState(c to pass)
    LaunchedEffect(c, pass) {
        if (c != saved || pass != savedPass) {
            delay(800)
            vm.save(c, pass)
        }
    }
    // Beim Verlassen der Seite noch nicht gespeicherte Eingaben sofort sichern
    DisposableEffect(Unit) {
        onDispose { val (lc, lp) = latest; vm.saveIfChanged(lc, lp) }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { treeUriToPath(it) }?.let { p -> if (p !in c.sources) c = c.copy(sources = c.sources + p) }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard("Repository") {
            OutlinedTextField(
                c.repo, { c = c.copy(repo = it) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Repository") },
                placeholder = { Text("ssh://user@server:22/~/borg/handy") },
                supportingText = { Text("Borg-1.x-Syntax, z. B. ssh://user@host:port/pfad oder user@host:pfad") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Passphrase") },
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showPass = !showPass }) { Text(if (showPass) "Verbergen" else "Zeigen") } },
                supportingText = { Text("Wird verschlüsselt (Android Keystore) in der App gespeichert") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedTextField(
                c.remotePath, { c = c.copy(remotePath = it) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Remote-Pfad zu borg (optional)") }, placeholder = { Text("z. B. borg1 oder /usr/local/bin/borg") },
            )
            OutlinedTextField(
                c.sshOptions, { c = c.copy(sshOptions = it) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Zusätzliche ssh-Optionen (optional)") }, placeholder = { Text("-o Compression=no") },
            )
            SwitchRow(
                "Post-Quantum-Warnung ignorieren", c.ignorePqWarning,
                "Blendet die ssh-Warnung „not using a post-quantum key exchange algorithm“ aus, die bei älteren " +
                    "Servern erscheint. Die Verbindung bleibt verschlüsselt.",
            ) { c = c.copy(ignorePqWarning = it) }
        }

        SectionCard("Was wird gesichert?") {
            c.sources.forEach { src ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(src.removePrefix(BackupConfig.STORAGE).ifEmpty { "/ (gesamter interner Speicher)" }.let { if (src.startsWith(BackupConfig.STORAGE)) "📁 Speicher$it" else "📁 $src" },
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { c = c.copy(sources = c.sources - src) }) { Icon(Icons.Filled.Close, "Entfernen") }
                }
            }
            if (c.sources.isEmpty()) Text("Keine Ordner ausgewählt.", color = MaterialTheme.colorScheme.error)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { folderPicker.launch(null) }) { Text("Ordner hinzufügen") }
                OutlinedButton(onClick = {
                    if (BackupConfig.STORAGE !in c.sources) c = c.copy(sources = c.sources + BackupConfig.STORAGE)
                }) { Text("Ganzen Speicher") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(manualPath, { manualPath = it }, Modifier.weight(1f), singleLine = true, label = { Text("Pfad manuell") })
                TextButton(onClick = {
                    val p = manualPath.trim().trimEnd('/')
                    if (p.isNotEmpty() && p !in c.sources) c = c.copy(sources = c.sources + p)
                    manualPath = ""
                }) { Text("Hinzufügen") }
            }
            OutlinedTextField(
                excludesText, { excludesText = it }, Modifier.fillMaxWidth(), minLines = 3,
                label = { Text("Ausschlüsse (ein Muster pro Zeile)") },
                supportingText = { Text("Borg-Muster, z. B. sh:**/.thumbnails oder sh:/storage/emulated/0/Movies/**") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            SwitchRow(
                "Android/data + Android/obb (Shizuku)", c.includeAppData,
                "Über Shizuku als eigenes Archiv „…-appdata-…“. Läuft Shizuku nicht, wird dieser Teil mit Warnung übersprungen."
            ) { c = c.copy(includeAppData = it) }
            if (c.includeAppData) SwitchRow("Cache-Ordner der Apps auslassen", c.appDataSkipCache) { c = c.copy(appDataSkipCache = it) }
        }

        SectionCard("Archiv & Aufräumen") {
            OutlinedTextField(
                c.archivePrefix, { c = c.copy(archivePrefix = it) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Archiv-Präfix") },
                supportingText = { Text("Archive heißen ${c.prefix}-files-JJJJ-MM-TT_HH-MM") },
            )
            Text("Kompression", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackupConfig.COMPRESSIONS.forEach { comp ->
                    FilterChip(selected = c.compression == comp, onClick = { c = c.copy(compression = comp) }, label = { Text(comp) })
                }
            }
            OutlinedTextField(
                c.extraCreateArgs, { c = c.copy(extraCreateArgs = it) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Weitere Optionen für borg create (optional)") }, placeholder = { Text("--files-cache=ctime,size") },
            )
            SwitchRow("Alte Archive ausdünnen (prune)", c.pruneEnabled) { c = c.copy(pruneEnabled = it) }
            if (c.pruneEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Täglich", c.keepDaily, Modifier.weight(1f)) { c = c.copy(keepDaily = it) }
                    NumberField("Wöchentl.", c.keepWeekly, Modifier.weight(1f)) { c = c.copy(keepWeekly = it) }
                    NumberField("Monatl.", c.keepMonthly, Modifier.weight(1f)) { c = c.copy(keepMonthly = it) }
                }
                SwitchRow("Danach Platz freigeben (compact)", c.compact) { c = c.copy(compact = it) }
            }
        }

        SectionCard("Zeitplan") {
            SwitchRow("Automatisch sichern", c.scheduleEnabled) { c = c.copy(scheduleEnabled = it) }
            if (c.scheduleEnabled) {
                Text("Intervall", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6, 12, 24, 48, 168).forEach { h ->
                        FilterChip(selected = c.intervalHours == h, onClick = { c = c.copy(intervalHours = h) },
                            label = { Text(if (h == 168) "wöchentlich" else "$h h") })
                    }
                }
                val ctx = androidx.compose.ui.platform.LocalContext.current
                SwitchRow("Feste Uhrzeit", c.startMinute >= 0) { c = c.copy(startMinute = if (it) 120 else -1) }
                if (c.startMinute >= 0) {
                    OutlinedButton(onClick = {
                        android.app.TimePickerDialog(ctx, { _, h, m -> c = c.copy(startMinute = h * 60 + m) },
                            c.startMinute / 60, c.startMinute % 60, true).show()
                    }) { Text("Uhrzeit: %02d:%02d".format(c.startMinute / 60, c.startMinute % 60)) }
                }
                Text(
                    c.scheduleText() + ". Android startet das Backup ungefähr zu diesem Zeitpunkt, " +
                        "im Energiesparmodus oder wenn eine Bedingung fehlt (WLAN, Laden) auch später.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SwitchRow("Nur im WLAN (unbegrenztes Netz)", c.requireUnmetered) { c = c.copy(requireUnmetered = it) }
                SwitchRow("Nur beim Laden", c.requireCharging) { c = c.copy(requireCharging = it) }
                SwitchRow("Nicht bei niedrigem Akku", c.requireBatteryNotLow) { c = c.copy(requireBatteryNotLow = it) }
            }
        }

        SectionCard("Werkzeuge") {
            var confirmBreak by remember { mutableStateOf(false) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirmBreak = true }) { Text("Sperre aufheben") }
                OutlinedButton(onClick = vm::forgetKnownHosts) { Text("Bekannte Server vergessen") }
                OutlinedButton(onClick = { vm.newKey() }) { Text("Neuen Schlüssel erzeugen") }
            }
            if (confirmBreak) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmBreak = false },
                    title = { Text("Sperre aufheben?") },
                    text = { Text("Führt „borg break-lock“ aus. Nur verwenden, wenn gerade kein anderer Rechner auf dieses Repository sichert.") },
                    confirmButton = { TextButton(onClick = { confirmBreak = false; vm.breakLock() }) { Text("Aufheben") } },
                    dismissButton = { TextButton(onClick = { confirmBreak = false }) { Text("Abbrechen") } },
                )
            }
        }

    }
}

@Composable
private fun NumberField(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text, { t -> text = t.filter { it.isDigit() }.take(3); text.toIntOrNull()?.let(onChange) },
        modifier, singleLine = true, label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

/** content://…/tree/primary%3ADCIM → /storage/emulated/0/DCIM */
fun treeUriToPath(uri: Uri): String? {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val parts = docId.split(":", limit = 2)
    val volume = parts[0]
    val rel = parts.getOrElse(1) { "" }.trim('/')
    val base = if (volume.equals("primary", true)) BackupConfig.STORAGE else "/storage/$volume"
    return if (rel.isEmpty()) base else "$base/$rel"
}

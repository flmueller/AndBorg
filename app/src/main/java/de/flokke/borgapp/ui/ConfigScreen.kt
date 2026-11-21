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
import androidx.compose.ui.res.stringResource
import de.flokke.borgapp.R
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

    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard(stringResource(R.string.repository)) {
            OutlinedTextField(
                c.repo, { c = c.copy(repo = it) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.repository)) },
                placeholder = { Text(stringResource(R.string.repo_placeholder)) },
                supportingText = { Text(stringResource(R.string.repo_help)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.passphrase)) },
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showPass = !showPass }) { Text(stringResource(if (showPass) R.string.hide_pass else R.string.show_pass)) } },
                supportingText = { Text(stringResource(R.string.pass_help)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedTextField(
                c.remotePath, { c = c.copy(remotePath = it) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.remote_path)) }, placeholder = { Text(stringResource(R.string.remote_path_example)) },
            )
            OutlinedTextField(
                c.sshOptions, { c = c.copy(sshOptions = it) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.ssh_options)) }, placeholder = { Text("-o Compression=no") },
            )
            SwitchRow(
                stringResource(R.string.pq_ignore), c.ignorePqWarning,
                stringResource(R.string.pq_ignore_sub),
            ) { c = c.copy(ignorePqWarning = it) }
        }

        SectionCard(stringResource(R.string.sec_sources)) {
            c.sources.forEach { src ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val rel = src.removePrefix(BackupConfig.STORAGE).ifEmpty { stringResource(R.string.whole_storage_label) }
                    Text(if (src.startsWith(BackupConfig.STORAGE)) stringResource(R.string.storage_path, rel) else "📁 $src",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { c = c.copy(sources = c.sources - src) }) { Icon(Icons.Filled.Close, stringResource(R.string.remove)) }
                }
            }
            if (c.sources.isEmpty()) Text(stringResource(R.string.no_folders), color = MaterialTheme.colorScheme.error)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { folderPicker.launch(null) }) { Text(stringResource(R.string.add_folder)) }
                OutlinedButton(onClick = {
                    if (BackupConfig.STORAGE !in c.sources) c = c.copy(sources = c.sources + BackupConfig.STORAGE)
                }) { Text(stringResource(R.string.whole_storage)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(manualPath, { manualPath = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.manual_path)) })
                TextButton(onClick = {
                    val p = manualPath.trim().trimEnd('/')
                    if (p.isNotEmpty() && p !in c.sources) c = c.copy(sources = c.sources + p)
                    manualPath = ""
                }) { Text(stringResource(R.string.add)) }
            }
            OutlinedTextField(
                excludesText, { excludesText = it }, Modifier.fillMaxWidth(), minLines = 3,
                label = { Text(stringResource(R.string.excludes)) },
                supportingText = { Text(stringResource(R.string.excludes_help)) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            SwitchRow(
                stringResource(R.string.appdata), c.includeAppData,
                stringResource(R.string.appdata_sub)
            ) { c = c.copy(includeAppData = it) }
            if (c.includeAppData) SwitchRow(stringResource(R.string.skip_cache), c.appDataSkipCache) { c = c.copy(appDataSkipCache = it) }
        }

        SectionCard(stringResource(R.string.sec_archive)) {
            OutlinedTextField(
                c.archivePrefix, { c = c.copy(archivePrefix = it) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.archive_prefix)) },
                supportingText = { Text(stringResource(R.string.archive_prefix_help, c.prefix)) },
            )
            Text(stringResource(R.string.compression), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackupConfig.COMPRESSIONS.forEach { comp ->
                    FilterChip(selected = c.compression == comp, onClick = { c = c.copy(compression = comp) }, label = { Text(comp) })
                }
            }
            OutlinedTextField(
                c.extraCreateArgs, { c = c.copy(extraCreateArgs = it) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.create_args)) }, placeholder = { Text("--files-cache=ctime,size") },
            )
            SwitchRow(stringResource(R.string.prune), c.pruneEnabled) { c = c.copy(pruneEnabled = it) }
            if (c.pruneEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(stringResource(R.string.keep_daily), c.keepDaily, Modifier.weight(1f)) { c = c.copy(keepDaily = it) }
                    NumberField(stringResource(R.string.keep_weekly), c.keepWeekly, Modifier.weight(1f)) { c = c.copy(keepWeekly = it) }
                    NumberField(stringResource(R.string.keep_monthly), c.keepMonthly, Modifier.weight(1f)) { c = c.copy(keepMonthly = it) }
                }
                SwitchRow(stringResource(R.string.compact), c.compact) { c = c.copy(compact = it) }
            }
        }

        SectionCard(stringResource(R.string.sec_schedule)) {
            SwitchRow(stringResource(R.string.schedule_enabled), c.scheduleEnabled) { c = c.copy(scheduleEnabled = it) }
            if (c.scheduleEnabled) {
                Text(stringResource(R.string.interval), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6, 12, 24, 48, 168).forEach { h ->
                        FilterChip(selected = c.intervalHours == h, onClick = { c = c.copy(intervalHours = h) },
                            label = { Text(if (h == 168) stringResource(R.string.weekly_chip) else "$h h") })
                    }
                }
                SwitchRow(stringResource(R.string.fixed_time), c.startMinute >= 0) { c = c.copy(startMinute = if (it) 120 else -1) }
                if (c.startMinute >= 0) {
                    OutlinedButton(onClick = {
                        android.app.TimePickerDialog(ctx, { _, h, m -> c = c.copy(startMinute = h * 60 + m) },
                            c.startMinute / 60, c.startMinute % 60, true).show()
                    }) { Text(stringResource(R.string.time_button, "%02d:%02d".format(c.startMinute / 60, c.startMinute % 60))) }
                }
                Text(
                    stringResource(R.string.schedule_hint, c.scheduleText(ctx)),
                    style = MaterialTheme.typography.bodySmall,
                )
                SwitchRow(stringResource(R.string.req_unmetered), c.requireUnmetered) { c = c.copy(requireUnmetered = it) }
                SwitchRow(stringResource(R.string.req_charging), c.requireCharging) { c = c.copy(requireCharging = it) }
                SwitchRow(stringResource(R.string.req_battery), c.requireBatteryNotLow) { c = c.copy(requireBatteryNotLow = it) }
            }
        }

        SectionCard(stringResource(R.string.sec_tools)) {
            var confirmBreak by remember { mutableStateOf(false) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirmBreak = true }) { Text(stringResource(R.string.break_lock)) }
                OutlinedButton(onClick = vm::forgetKnownHosts) { Text(stringResource(R.string.forget_hosts)) }
                OutlinedButton(onClick = { vm.newKey() }) { Text(stringResource(R.string.new_key)) }
            }
            if (confirmBreak) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmBreak = false },
                    title = { Text(stringResource(R.string.break_lock_confirm_title)) },
                    text = { Text(stringResource(R.string.break_lock_confirm_text)) },
                    confirmButton = { TextButton(onClick = { confirmBreak = false; vm.breakLock() }) { Text(stringResource(R.string.break_lock_do)) } },
                    dismissButton = { TextButton(onClick = { confirmBreak = false }) { Text(stringResource(R.string.cancel)) } },
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

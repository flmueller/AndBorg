package de.flokke.borgapp.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.flokke.borgapp.Compat
import de.flokke.borgapp.Notifier
import de.flokke.borgapp.data.RunStatus
import de.flokke.borgapp.shizuku.ShizukuState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("BatteryLife")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatusScreen(vm: MainViewModel, modifier: Modifier, openSettings: () -> Unit) {
    val ctx = LocalContext.current
    val cfg by vm.config.collectAsStateWithLifecycle()
    val pass by vm.passphrase.collectAsStateWithLifecycle()
    val verified by vm.verifiedRepo.collectAsStateWithLifecycle()
    val setup by vm.setup.collectAsStateWithLifecycle()
    val runs by vm.history.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val nextRun by vm.nextRun.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val copy = rememberCopy()
    var confirmInit by remember { mutableStateOf(false) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.backupNow() }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Dauerhaft abgelehnt: dann bleibt nur die App-Info
        if (!granted) ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        vm.refreshLocal()
    }
    val startBackup = {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.backupNow()
    }

    val serverOk = cfg.repo.isNotBlank() && pass.isNotEmpty()
    val connOk = serverOk && verified == cfg.repo
    val allDone = setup.storage && serverOk && connOk && setup.battery
    var showSetup by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val setupExpanded = showSetup ?: !allDone

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        setup.runtimeError?.let {
            SectionCard("borg konnte nicht gestartet werden") { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        // ---- Backup ----
        SectionCard("Backup") {
            val p = progress
            if (p != null) {
                Text(p.phase + " …", style = MaterialTheme.typography.bodyLarge)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Notifier.progressText(p).takeIf { it.isNotEmpty() }?.let { Text(it) }
                if (p.path.isNotBlank()) Text(p.path, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { vm.cancelBackup() }) { Text("Abbrechen") }
            } else {
                val last = runs.firstOrNull()
                if (last == null) Text("Noch kein Backup gemacht.")
                else {
                    val icon = when (last.status) { RunStatus.OK -> "✅"; RunStatus.WARNING -> "⚠️"; RunStatus.ERROR -> "❌"; RunStatus.RUNNING -> "⏳" }
                    Text("$icon Letztes Backup: ${fmt(last.id)}", style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.clickable { vm.showText("Backup ${fmt(last.id)}", last.output) })
                    Text(last.shortSummary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    if (cfg.scheduleEnabled) "Automatisch: ${cfg.scheduleText()}" + (nextRun?.let { " · nächstes ab ${fmt(it)}" } ?: "")
                    else "Automatisches Backup ist aus (Einstellungen → Zeitplan)",
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = startBackup, enabled = serverOk && setup.storage && setup.runtimeError == null) { Text("Jetzt sichern") }
                    OutlinedButton(onClick = vm::listArchives, enabled = serverOk && busy == null) { Text("Archive anzeigen") }
                }
            }
        }

        // ---- Einrichtung ----
        SectionCard(if (allDone) "Einrichtung ✓" else "Einrichtung") {
            if (allDone) TextButton(onClick = { showSetup = !setupExpanded }) { Text(if (setupExpanded) "Ausblenden" else "Anzeigen") }
            if (setupExpanded) {
                Step(1, setup.storage, if (Compat.usesAllFilesAccess) "Zugriff auf alle Dateien" else "Zugriff auf den Speicher",
                    "Damit borg Fotos, Dokumente usw. lesen kann.") {
                    Button(onClick = {
                        if (Compat.usesAllFilesAccess) {
                            @SuppressLint("InlinedApi")
                            val action = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                            ctx.startActivity(Intent(action, Uri.parse("package:${ctx.packageName}")))
                        } else storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }) { Text("Erlauben") }
                }
                Step(2, serverOk, "Server und Passphrase eintragen",
                    if (serverOk) cfg.repo else "z. B. ssh://user@server:22/~/borg/handy") {
                    OutlinedButton(onClick = openSettings) { Text(if (serverOk) "Ändern" else "Eintragen") }
                }
                Step(3, connOk, "SSH-Schlüssel auf dem Server eintragen",
                    "Diese Zeile auf dem Server in ~/.ssh/authorized_keys einfügen:", alwaysShowActions = true) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SelectionContainer {
                            Text(setup.publicKey.ifEmpty { "(wird erzeugt …)" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { copy("SSH-Schlüssel", setup.publicKey) }, enabled = setup.publicKey.isNotEmpty()) { Text("Kopieren") }
                            OutlinedButton(onClick = { shareText(ctx, "SSH-Schlüssel AndBorg", setup.publicKey) }, enabled = setup.publicKey.isNotEmpty()) { Text("Teilen") }
                        }
                    }
                }
                Step(4, connOk, "Verbindung prüfen", "Testet Server, Schlüssel und Passphrase. Gibt es noch kein Repository: „Neu anlegen“.",
                    alwaysShowActions = true) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::testRepo, enabled = serverOk && busy == null) { Text("Verbindung testen") }
                        OutlinedButton(onClick = { confirmInit = true }, enabled = serverOk && busy == null) { Text("Neu anlegen") }
                    }
                }
                Step(5, setup.battery, "Akku-Optimierung ausschalten", "Nötig, damit geplante Backups im Hintergrund durchlaufen.") {
                    Button(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                    }) { Text("Erlauben") }
                }
            }
        }

        // ---- Shizuku ----
        SectionCard("Shizuku (optional)") {
            Text(
                "Mit Shizuku werden zusätzlich Android/data und Android/obb gesichert (Einstellungen → „Android/data + Android/obb“).",
                style = MaterialTheme.typography.bodySmall,
            )
            when (setup.shizuku) {
                ShizukuState.NOT_INSTALLED -> Text("Shizuku ist nicht installiert.")
                ShizukuState.NOT_RUNNING -> Text("Shizuku ist installiert, läuft aber nicht. Shizuku öffnen und starten.")
                ShizukuState.NO_PERMISSION -> Row { Button(onClick = vm::requestShizuku) { Text("Shizuku erlauben") } }
                ShizukuState.READY -> Text("✅ Shizuku ist bereit" + if (!cfg.includeAppData) " – in den Einstellungen einschalten." else ".")
            }
        }

        if (setup.borgVersion.isNotEmpty()) {
            Text("${setup.borgVersion} (in der App enthalten)", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (confirmInit) {
        AlertDialog(
            onDismissRequest = { confirmInit = false },
            title = { Text("Neues Repository anlegen?") },
            text = {
                Text("Unter\n${cfg.repo}\nwird ein neues, verschlüsseltes Repository (repokey-blake2) mit der eingetragenen Passphrase angelegt. " +
                    "Fehlende Ordner werden erstellt. Danach wird der Schlüssel zum Aufbewahren angezeigt.")
            },
            confirmButton = { TextButton(onClick = { confirmInit = false; vm.initRepo() }) { Text("Anlegen") } },
            dismissButton = { TextButton(onClick = { confirmInit = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun Step(
    n: Int, ok: Boolean, title: String, sub: String, alwaysShowActions: Boolean = false,
    actions: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (n > 1) HorizontalDivider(Modifier.padding(bottom = 4.dp))
        Text("${if (ok) "✅" else "$n."}  $title", style = MaterialTheme.typography.bodyLarge)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!ok || alwaysShowActions) {
            Column(Modifier.padding(top = 2.dp)) { actions() }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(2.dp))
    }
}

fun fmt(t: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(t))

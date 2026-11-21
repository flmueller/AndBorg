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
import androidx.compose.ui.res.stringResource
import de.flokke.borgapp.R
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
            SectionCard(stringResource(R.string.runtime_error_title)) { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        // ---- Backup ----
        SectionCard(stringResource(R.string.backup)) {
            val p = progress
            if (p != null) {
                Text(p.phase + " …", style = MaterialTheme.typography.bodyLarge)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Notifier.progressText(ctx, p).takeIf { it.isNotEmpty() }?.let { Text(it) }
                if (p.path.isNotBlank()) Text(p.path, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { vm.cancelBackup() }) { Text(stringResource(R.string.cancel)) }
            } else {
                val last = runs.firstOrNull()
                if (last == null) Text(stringResource(R.string.no_backup_yet))
                else {
                    val icon = when (last.status) { RunStatus.OK -> "✅"; RunStatus.WARNING -> "⚠️"; RunStatus.ERROR -> "❌"; RunStatus.RUNNING -> "⏳" }
                    val title = stringResource(R.string.backup_title, fmt(last.id))
                    Text(stringResource(R.string.last_backup, icon, fmt(last.id)), style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.clickable { vm.showText(title, last.output) })
                    Text(last.shortSummary(ctx), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    when {
                        !cfg.scheduleEnabled -> stringResource(R.string.schedule_off)
                        nextRun != null -> stringResource(R.string.schedule_auto_next, cfg.scheduleText(ctx), fmt(nextRun!!))
                        else -> stringResource(R.string.schedule_auto, cfg.scheduleText(ctx))
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = startBackup, enabled = serverOk && setup.storage && setup.runtimeError == null) { Text(stringResource(R.string.backup_now)) }
                    OutlinedButton(onClick = vm::listArchives, enabled = serverOk && busy == null) { Text(stringResource(R.string.show_archives)) }
                }
            }
        }

        // ---- Einrichtung ----
        SectionCard(stringResource(if (allDone) R.string.setup_done else R.string.setup)) {
            if (allDone) TextButton(onClick = { showSetup = !setupExpanded }) { Text(stringResource(if (setupExpanded) R.string.hide else R.string.show)) }
            if (setupExpanded) {
                Step(1, setup.storage, stringResource(if (Compat.usesAllFilesAccess) R.string.step_storage_all else R.string.step_storage),
                    stringResource(R.string.step_storage_sub)) {
                    Button(onClick = {
                        if (Compat.usesAllFilesAccess) {
                            @SuppressLint("InlinedApi")
                            val action = Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                            ctx.startActivity(Intent(action, Uri.parse("package:${ctx.packageName}")))
                        } else storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }) { Text(stringResource(R.string.allow)) }
                }
                Step(2, serverOk, stringResource(R.string.step_server),
                    if (serverOk) cfg.repo else stringResource(R.string.repo_example)) {
                    OutlinedButton(onClick = openSettings) { Text(stringResource(if (serverOk) R.string.change else R.string.enter)) }
                }
                Step(3, connOk, stringResource(R.string.step_key),
                    stringResource(R.string.step_key_sub), alwaysShowActions = true) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SelectionContainer {
                            Text(setup.publicKey.ifEmpty { stringResource(R.string.key_generating) }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val keyLabel = stringResource(R.string.ssh_key)
                            val shareTitle = stringResource(R.string.ssh_key_share_title)
                            OutlinedButton(onClick = { copy(keyLabel, setup.publicKey) }, enabled = setup.publicKey.isNotEmpty()) { Text(stringResource(R.string.copy)) }
                            OutlinedButton(onClick = { shareText(ctx, shareTitle, setup.publicKey) }, enabled = setup.publicKey.isNotEmpty()) { Text(stringResource(R.string.share)) }
                        }
                    }
                }
                Step(4, connOk, stringResource(R.string.step_test), stringResource(R.string.step_test_sub),
                    alwaysShowActions = true) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::testRepo, enabled = serverOk && busy == null) { Text(stringResource(R.string.test_connection)) }
                        OutlinedButton(onClick = { confirmInit = true }, enabled = serverOk && busy == null) { Text(stringResource(R.string.create_new)) }
                    }
                }
                Step(5, setup.battery, stringResource(R.string.step_battery), stringResource(R.string.step_battery_sub)) {
                    Button(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                    }) { Text(stringResource(R.string.allow)) }
                }
            }
        }

        // ---- Shizuku ----
        SectionCard(stringResource(R.string.shizuku_title)) {
            Text(
                stringResource(R.string.shizuku_info),
                style = MaterialTheme.typography.bodySmall,
            )
            when (setup.shizuku) {
                ShizukuState.NOT_INSTALLED -> Text(stringResource(R.string.shizuku_not_installed))
                ShizukuState.NOT_RUNNING -> Text(stringResource(R.string.shizuku_not_running))
                ShizukuState.NO_PERMISSION -> Row { Button(onClick = vm::requestShizuku) { Text(stringResource(R.string.shizuku_allow)) } }
                ShizukuState.READY -> Text(stringResource(if (!cfg.includeAppData) R.string.shizuku_ready_enable else R.string.shizuku_ready))
            }
        }

        if (setup.borgVersion.isNotEmpty()) {
            Text(stringResource(R.string.borg_version_bundled, setup.borgVersion), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (confirmInit) {
        AlertDialog(
            onDismissRequest = { confirmInit = false },
            title = { Text(stringResource(R.string.init_confirm_title)) },
            text = {
                Text(stringResource(R.string.init_confirm_text, cfg.repo))
            },
            confirmButton = { TextButton(onClick = { confirmInit = false; vm.initRepo() }) { Text(stringResource(R.string.create)) } },
            dismissButton = { TextButton(onClick = { confirmInit = false }) { Text(stringResource(R.string.cancel)) } },
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

/** Datum und Uhrzeit im Format der Gerätesprache (z. B. 21.11.2026, 02:00 oder 11/21/2026, 02:00). */
fun fmt(t: Long): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "ddMMyyyyHHmm")
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(t))
}

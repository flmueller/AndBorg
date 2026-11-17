package de.flokke.borgapp.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.flokke.borgapp.Notifier
import de.flokke.borgapp.data.BackupConfig
import de.flokke.borgapp.data.ConfigStore
import de.flokke.borgapp.data.RunStatus
import de.flokke.borgapp.runtime.Borg
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Führt das Backup als Vordergrund-Arbeit aus (manuell oder nach Zeitplan). */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private fun foregroundInfo(p: Progress?): ForegroundInfo {
        val n = Notifier.progress(applicationContext, p)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifier.ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(Notifier.ID_PROGRESS, n)
    }

    override suspend fun doWork(): Result = coroutineScope {
        val trigger = inputData.getString(KEY_TRIGGER) ?: TRIGGER_SCHEDULED
        // Ohne Vordergrunddienst beendet Android die Arbeit nach ~10 Minuten. Aus dem Hintergrund klappt der
        // Start nur, wenn die Akku-Optimierung für die App abgeschaltet ist.
        val fg = runCatching { setForeground(foregroundInfo(null)) }
        val updater = launch {
            BackupEngine.progress.collectLatest { p ->
                if (p != null && fg.isSuccess) runCatching { setForeground(foregroundInfo(p)) }
                kotlinx.coroutines.delay(1500)
            }
        }
        val rec = BackupEngine.run(applicationContext, trigger)
        updater.cancel()
        // Geplante Läufe bei vorübergehenden Fehlern (Server/Netz weg, Repository gesperrt) mit wachsendem
        // Abstand wiederholen (30 min, 1 h, 2 h, 4 h). Danach gilt wieder der normale Zeitplan.
        val retry = rec != null && trigger == TRIGGER_SCHEDULED && rec.status == RunStatus.ERROR &&
            runAttemptCount < MAX_RETRIES && Borg.isTransient(rec.output)
        if (rec != null) {
            val next = if (retry) RETRY_MINUTES shl runAttemptCount else null
            Notifier.backupFinished(applicationContext, rec, retryInMinutes = next)
        }
        if (retry) return@coroutineScope Result.retry()
        if (trigger == TRIGGER_SCHEDULED) BackupScheduler.scheduleNext(applicationContext, ConfigStore(applicationContext).load())
        Result.success()
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
        const val TRIGGER_SCHEDULED = "geplant"
        const val MAX_RETRIES = 4
        const val RETRY_MINUTES = 30L
    }
}

/**
 * Zeitplan: Jeder geplante Lauf ist ein einzelner Auftrag, der am Ende den nächsten Termin einplant.
 * So bleibt eine feste Uhrzeit erhalten (periodische Arbeit würde sich mit jeder Verspätung verschieben).
 */
object BackupScheduler {
    private const val WORK_LEGACY_PERIODIC = "borg_periodic_backup" // bis 0.2.x
    private const val WORK_SCHEDULED = "borg_scheduled_backup"
    private const val WORK_MANUAL = "borg_manual_backup"
    private const val PREFS = "scheduler"
    private const val KEY_SIGNATURE = "signature"
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    private fun signature(cfg: BackupConfig) = listOf(
        cfg.intervalHours, cfg.startMinute, cfg.requireUnmetered, cfg.requireCharging, cfg.requireBatteryNotLow,
    ).joinToString(",")

    /** Nächster Termin nach [from] (Ende des letzten Laufs bzw. jetzt). */
    fun nextSlot(cfg: BackupConfig, from: Long): Long {
        val interval = cfg.intervalHours.coerceAtLeast(1) * HOUR
        if (cfg.startMinute < 0) return from + interval
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, cfg.startMinute / 60)
            set(Calendar.MINUTE, cfg.startMinute % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return if (interval < DAY) {
            // Raster ab der Startzeit, z. B. 02:00, 08:00, 14:00, 20:00
            var t = cal.timeInMillis
            while (t - interval > from) t -= interval
            while (t <= from) t += interval
            t
        } else {
            // frühestens (Intervall − 1 Tag) später, dann zur gewählten Uhrzeit
            val base = from + interval - DAY
            cal.timeInMillis = base
            cal.set(Calendar.HOUR_OF_DAY, cfg.startMinute / 60)
            cal.set(Calendar.MINUTE, cfg.startMinute % 60)
            if (cal.timeInMillis <= base) cal.add(Calendar.DAY_OF_MONTH, 1)
            cal.timeInMillis
        }
    }

    private fun request(cfg: BackupConfig, at: Long): OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (cfg.requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(cfg.requireCharging)
            .setRequiresBatteryNotLow(cfg.requireBatteryNotLow)
            .build()
        return OneTimeWorkRequestBuilder<BackupWorker>()
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setInputData(workDataOf(BackupWorker.KEY_TRIGGER to BackupWorker.TRIGGER_SCHEDULED))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BackupWorker.RETRY_MINUTES, TimeUnit.MINUTES)
            .build()
    }

    /** Übernimmt geänderte Zeitplan-Einstellungen. Ohne Änderung bleibt der geplante Termin unverändert. */
    @Synchronized
    fun apply(ctx: Context, cfg: BackupConfig) {
        val wm = WorkManager.getInstance(ctx)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!cfg.scheduleEnabled) {
            wm.cancelUniqueWork(WORK_SCHEDULED)
            wm.cancelUniqueWork(WORK_LEGACY_PERIODIC)
            prefs.edit().remove(KEY_SIGNATURE).apply()
            return
        }
        val sig = signature(cfg)
        val pending = wm.getWorkInfosForUniqueWork(WORK_SCHEDULED).get().filter { !it.state.isFinished }
        if (sig == prefs.getString(KEY_SIGNATURE, null) && pending.isNotEmpty()) return
        prefs.edit().putString(KEY_SIGNATURE, sig).apply()
        // Läuft gerade ein geplantes Backup, plant es am Ende selbst den nächsten Termin (mit den neuen Einstellungen)
        if (pending.any { it.state == WorkInfo.State.RUNNING }) return

        val now = System.currentTimeMillis()
        // Umstieg von 0.2.x: nächsten Termin des alten periodischen Auftrags übernehmen
        val legacyNext = wm.getWorkInfosForUniqueWork(WORK_LEGACY_PERIODIC).get()
            .firstOrNull { !it.state.isFinished }?.nextScheduleTimeMillis?.takeIf { it in (now + 1)..<Long.MAX_VALUE }
        wm.cancelUniqueWork(WORK_LEGACY_PERIODIC)
        val at = if (cfg.startMinute < 0 && legacyNext != null) legacyNext else nextSlot(cfg, now)
        wm.enqueueUniqueWork(WORK_SCHEDULED, ExistingWorkPolicy.REPLACE, request(cfg, at))
    }

    /** Vom laufenden geplanten Backup aufgerufen: hängt den nächsten Termin an. */
    fun scheduleNext(ctx: Context, cfg: BackupConfig) {
        if (!cfg.scheduleEnabled) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SIGNATURE, signature(cfg)).apply()
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(WORK_LEGACY_PERIODIC) // falls der Lauf noch vom alten periodischen Auftrag kam
        wm.enqueueUniqueWork(
            WORK_SCHEDULED, ExistingWorkPolicy.APPEND_OR_REPLACE, request(cfg, nextSlot(cfg, System.currentTimeMillis())),
        )
    }

    fun runNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(BackupWorker.KEY_TRIGGER to "manuell"))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_MANUAL, ExistingWorkPolicy.KEEP, req)
    }

    /** Bricht das laufende Backup ab. Danach apply() aufrufen, damit der nächste Termin wieder eingeplant wird. */
    fun cancelRunning(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(WORK_MANUAL)
        wm.getWorkInfosForUniqueWork(WORK_SCHEDULED).get().filter { it.state == WorkInfo.State.RUNNING }
            .forEach { wm.cancelWorkById(it.id) }
    }

    fun workInfoFlow(ctx: Context) = WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(WORK_SCHEDULED)
}

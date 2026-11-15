package de.flokke.borgapp.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import de.flokke.borgapp.Notifier
import de.flokke.borgapp.data.BackupConfig
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
        val trigger = inputData.getString(KEY_TRIGGER) ?: "geplant"
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
        if (rec != null) Notifier.backupFinished(applicationContext, rec)
        Result.success()
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
    }
}

object BackupScheduler {
    private const val WORK_PERIODIC = "borg_periodic_backup"
    private const val WORK_MANUAL = "borg_manual_backup"

    fun apply(ctx: Context, cfg: BackupConfig) {
        val wm = WorkManager.getInstance(ctx)
        if (!cfg.scheduleEnabled) {
            wm.cancelUniqueWork(WORK_PERIODIC)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (cfg.requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(cfg.requireCharging)
            .setRequiresBatteryNotLow(cfg.requireBatteryNotLow)
            .build()
        val hours = cfg.intervalHours.coerceAtLeast(1).toLong()
        val req = PeriodicWorkRequestBuilder<BackupWorker>(hours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInputData(workDataOf(BackupWorker.KEY_TRIGGER to "geplant"))
            .build()
        wm.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun runNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(BackupWorker.KEY_TRIGGER to "manuell"))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_MANUAL, ExistingWorkPolicy.KEEP, req)
    }

    /** Bricht das laufende Backup ab (der geplante Zeitplan bleibt bestehen). */
    fun cancelRunning(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(WORK_MANUAL)
        // Den periodischen Auftrag nur stoppen, nicht löschen: neu einplanen
        wm.getWorkInfosForUniqueWork(WORK_PERIODIC).get().filter { it.state == androidx.work.WorkInfo.State.RUNNING }
            .forEach { wm.cancelWorkById(it.id) }
    }

    fun workInfoFlow(ctx: Context) = WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(WORK_PERIODIC)
}

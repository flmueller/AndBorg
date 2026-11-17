package de.flokke.borgapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.flokke.borgapp.data.RunRecord
import de.flokke.borgapp.data.RunStatus
import de.flokke.borgapp.ui.MainActivity
import de.flokke.borgapp.work.Progress

object Notifier {
    private const val CHANNEL_STATUS = "backup_status"
    private const val CHANNEL_RESULT = "backup_result"
    private const val ID_BACKUP = 1001
    const val ID_PROGRESS = 1002

    fun createChannels(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Laufendes Backup", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Backup-Ergebnis", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun contentIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun progress(ctx: Context, p: Progress?) = NotificationCompat.Builder(ctx, CHANNEL_STATUS)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("Borg-Backup: ${p?.phase ?: "startet"}")
        .setContentText(p?.let { progressText(it) } ?: "")
        .setProgress(0, 0, true)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(contentIntent(ctx))
        .build()

    fun progressText(p: Progress): String = buildList {
        if (p.files > 0) add("${p.files} Dateien")
        if (p.bytes > 0) add(formatBytes(p.bytes))
    }.joinToString(" · ")

    fun formatBytes(b: Long): String {
        val units = listOf("B", "kB", "MB", "GB", "TB")
        var v = b.toDouble(); var i = 0
        while (v >= 1000 && i < units.size - 1) { v /= 1000; i++ }
        return if (i == 0) "$b B" else String.format(java.util.Locale.GERMANY, "%.1f %s", v, units[i])
    }

    fun backupFinished(ctx: Context, r: RunRecord, retryInMinutes: Long? = null) {
        val (title, icon) = when (r.status) {
            RunStatus.OK -> "Backup erfolgreich" to android.R.drawable.stat_sys_upload_done
            RunStatus.WARNING -> "Backup mit Warnungen" to android.R.drawable.stat_sys_warning
            else -> "Backup fehlgeschlagen" to android.R.drawable.stat_notify_error
        }
        val text = r.shortSummary() + (retryInMinutes?.let { "\nNeuer Versuch in etwa ${formatMinutes(it)}." } ?: "")
        post(
            ctx, NotificationCompat.Builder(ctx, CHANNEL_RESULT)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(contentIntent(ctx))
        )
    }

    private fun formatMinutes(m: Long) = if (m < 60) "$m Minuten" else if (m == 60L) "1 Stunde" else "${m / 60} Stunden"

    fun error(ctx: Context, text: String) = post(
        ctx, NotificationCompat.Builder(ctx, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Backup nicht gestartet")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(ctx))
    )

    private fun post(ctx: Context, b: NotificationCompat.Builder) {
        try {
            NotificationManagerCompat.from(ctx).notify(ID_BACKUP, b.build())
        } catch (_: SecurityException) {
            // Benachrichtigungen nicht erlaubt
        }
    }
}

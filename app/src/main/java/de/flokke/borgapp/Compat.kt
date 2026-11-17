package de.flokke.borgapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment

/** Kleine Helfer, damit die App auch auf Android 7–10 läuft. */
object Compat {
    /** Ab Android 11 gibt es „Zugriff auf alle Dateien“, davor reicht die normale Speicherberechtigung. */
    val usesAllFilesAccess get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasStorageAccess(ctx: Context): Boolean =
        if (usesAllFilesAccess) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    fun versionCode(pi: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()

    /** Process.isAlive gibt es erst ab Android 8. */
    fun isAlive(p: Process): Boolean = try {
        p.exitValue(); false
    } catch (_: IllegalThreadStateException) {
        true
    }

    /** Wartet höchstens [ms] Millisekunden auf das Prozessende. */
    fun waitFor(p: Process, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (isAlive(p)) {
            if (System.currentTimeMillis() >= end) return false
            Thread.sleep(100)
        }
        return true
    }

    fun destroyForcibly(p: Process) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) p.destroyForcibly() else p.destroy()
    }
}

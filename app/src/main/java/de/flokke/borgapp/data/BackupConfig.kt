package de.flokke.borgapp.data

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

data class BackupConfig(
    /** z. B. ssh://user@host:22/~/borg/handy oder user@host:borg/handy */
    val repo: String = "",
    /** --remote-path auf dem Server, z. B. "borg1" oder "/usr/local/bin/borg" */
    val remotePath: String = "",
    /** zusätzliche ssh-Optionen, z. B. "-o Compression=no" */
    val sshOptions: String = "",
    val sources: List<String> = DEFAULT_SOURCES,
    val excludes: List<String> = DEFAULT_EXCLUDES,
    val compression: String = "lz4",
    val archivePrefix: String = defaultPrefix(),
    val extraCreateArgs: String = "",
    /** Android/data + Android/obb über Shizuku als zweites Archiv sichern */
    val includeAppData: Boolean = false,
    val appDataSkipCache: Boolean = true,
    val pruneEnabled: Boolean = true,
    val keepDaily: Int = 7,
    val keepWeekly: Int = 4,
    val keepMonthly: Int = 6,
    val compact: Boolean = true,
    val wakeLock: Boolean = true,
    val scheduleEnabled: Boolean = false,
    val intervalHours: Int = 24,
    val requireUnmetered: Boolean = true,
    val requireCharging: Boolean = false,
    val requireBatteryNotLow: Boolean = true,
) {
    val prefix: String get() = sanitizePrefix(archivePrefix)

    fun problems(): List<String> = buildList {
        if (repo.isBlank()) add("Kein Repository angegeben")
        if (sources.isEmpty() && !includeAppData) add("Keine Ordner zum Sichern ausgewählt")
    }

    fun toJson(): String = JSONObject().apply {
        put("repo", repo); put("remotePath", remotePath); put("sshOptions", sshOptions)
        put("sources", JSONArray(sources)); put("excludes", JSONArray(excludes))
        put("compression", compression); put("archivePrefix", archivePrefix)
        put("extraCreateArgs", extraCreateArgs)
        put("includeAppData", includeAppData); put("appDataSkipCache", appDataSkipCache)
        put("pruneEnabled", pruneEnabled); put("keepDaily", keepDaily); put("keepWeekly", keepWeekly)
        put("keepMonthly", keepMonthly); put("compact", compact); put("wakeLock", wakeLock)
        put("scheduleEnabled", scheduleEnabled); put("intervalHours", intervalHours)
        put("requireUnmetered", requireUnmetered); put("requireCharging", requireCharging)
        put("requireBatteryNotLow", requireBatteryNotLow)
    }.toString()

    companion object {
        const val STORAGE = "/storage/emulated/0"
        val DEFAULT_SOURCES = listOf(
            "/storage/emulated/0/DCIM",
            "/storage/emulated/0/Pictures",
            "/storage/emulated/0/Documents",
            "/storage/emulated/0/Download",
        )
        val DEFAULT_EXCLUDES = listOf(
            "sh:**/.thumbnails",
            "sh:**/.trashed-*",
            "sh:**/.Trash*",
        )
        val COMPRESSIONS = listOf("lz4", "zstd,3", "zstd,10", "auto,zstd,6", "zlib,6", "none")

        fun defaultPrefix(): String = sanitizePrefix(Build.MODEL ?: "android").ifBlank { "android" }

        fun sanitizePrefix(s: String): String =
            s.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "android" }

        fun fromJson(s: String?): BackupConfig {
            if (s.isNullOrBlank()) return BackupConfig()
            val o = JSONObject(s)
            val d = BackupConfig()
            fun list(key: String, def: List<String>): List<String> =
                o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: def
            return BackupConfig(
                repo = o.optString("repo", d.repo),
                remotePath = o.optString("remotePath", d.remotePath),
                sshOptions = o.optString("sshOptions", d.sshOptions),
                sources = list("sources", d.sources),
                excludes = list("excludes", d.excludes),
                compression = o.optString("compression", d.compression),
                archivePrefix = o.optString("archivePrefix", d.archivePrefix),
                extraCreateArgs = o.optString("extraCreateArgs", d.extraCreateArgs),
                includeAppData = o.optBoolean("includeAppData", d.includeAppData),
                appDataSkipCache = o.optBoolean("appDataSkipCache", d.appDataSkipCache),
                pruneEnabled = o.optBoolean("pruneEnabled", d.pruneEnabled),
                keepDaily = o.optInt("keepDaily", d.keepDaily),
                keepWeekly = o.optInt("keepWeekly", d.keepWeekly),
                keepMonthly = o.optInt("keepMonthly", d.keepMonthly),
                compact = o.optBoolean("compact", d.compact),
                wakeLock = o.optBoolean("wakeLock", d.wakeLock),
                scheduleEnabled = o.optBoolean("scheduleEnabled", d.scheduleEnabled),
                intervalHours = o.optInt("intervalHours", d.intervalHours),
                requireUnmetered = o.optBoolean("requireUnmetered", d.requireUnmetered),
                requireCharging = o.optBoolean("requireCharging", d.requireCharging),
                requireBatteryNotLow = o.optBoolean("requireBatteryNotLow", d.requireBatteryNotLow),
            )
        }
    }
}

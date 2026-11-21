package de.flokke.borgapp.work

import android.content.Context
import de.flokke.borgapp.R
import de.flokke.borgapp.data.BackupConfig
import de.flokke.borgapp.data.ConfigStore
import de.flokke.borgapp.data.HistoryRepo
import de.flokke.borgapp.data.RunRecord
import de.flokke.borgapp.data.RunStatus
import de.flokke.borgapp.runtime.Borg
import de.flokke.borgapp.runtime.BorgRuntime
import de.flokke.borgapp.shizuku.ShizukuHelper
import de.flokke.borgapp.shizuku.ShizukuState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Progress(
    val phase: String,
    val files: Long = 0,
    val bytes: Long = 0,
    val path: String = "",
)

/** Führt ein komplettes Backup aus: create → (appdata über Shizuku) → prune → compact. */
object BackupEngine {
    private val mutex = Mutex()
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    val running get() = mutex.isLocked

    /** Liefert null, wenn bereits ein Backup läuft. */
    suspend fun run(ctx: Context, trigger: String): RunRecord? {
        if (!mutex.tryLock()) return null
        val rec = HistoryRepo.start(trigger)
        val log = StringBuilder()
        var worst = 0
        fun line(s: String) = synchronized(log) {
            log.appendLine(s)
            if (log.length > 200_000) log.delete(0, log.length - 150_000)
        }
        fun note(rc: Int) { worst = maxOf(worst, classify(rc)) }
        fun s(id: Int, vararg args: Any): String = ctx.getString(id, *args)
        try {
            _progress.value = Progress(s(R.string.phase_prepare))
            val store = ConfigStore(ctx)
            val cfg = store.load()
            val pass = store.loadPassphrase()
            val problems = cfg.problems(ctx) + buildList {
                if (pass.isEmpty()) add(s(R.string.err_no_pass))
                if (!de.flokke.borgapp.Compat.hasStorageAccess(ctx)) add(s(R.string.err_no_storage))
            }
            if (problems.isNotEmpty()) {
                problems.forEach { line(s(R.string.log_error, it)) }
                worst = 2
                return finish(ctx, rec, worst, log)
            }
            BorgRuntime.ensure(ctx)
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date())

            // 1) Dateien
            val sources = cfg.sources.filter { File(it).exists() }
            (cfg.sources - sources.toSet()).forEach { line(s(R.string.log_missing_folder, it)); note(1) }
            if (sources.isNotEmpty()) {
                _progress.value = Progress(s(R.string.phase_files))
                line(s(R.string.log_files_header, "${cfg.prefix}-files-$stamp"))
                val args = mutableListOf("create", "--log-json", "--progress", "--stats", "--compression", cfg.compression)
                (cfg.excludes + ALWAYS_EXCLUDE).forEach { args += listOf("--exclude", it) }
                args += Borg.splitArgs(cfg.extraCreateArgs)
                args += "::${cfg.prefix}-files-$stamp"
                args += sources
                val rc = borg(ctx, cfg, pass, args, s(R.string.phase_files), null, ::line)
                line(s(R.string.log_exit, "borg create", rc)); note(rc)
            }

            // 2) Android/data + obb über Shizuku
            if (worst < 2 && cfg.includeAppData) {
                if (ShizukuHelper.state(ctx) != ShizukuState.READY) {
                    line(s(R.string.log_shizuku_skip))
                    note(1)
                } else {
                    _progress.value = Progress(s(R.string.phase_appdata))
                    line(s(R.string.log_appdata_header, "${cfg.prefix}-appdata-$stamp"))
                    ShizukuHelper.bind(ctx).use { b ->
                        val tarArgs = buildList {
                            add("-cf"); add("-")
                            if (cfg.appDataSkipCache) { add("--exclude=*/cache"); add("--exclude=*/code_cache") }
                            add("-C"); add("/storage/emulated/0/Android"); add("data"); add("obb")
                        }
                        val pfd = b.service.startTar(tarArgs.toTypedArray())
                        val args = listOf(
                            "import-tar", "--log-json", "--progress", "--stats", "--compression", cfg.compression,
                            "::${cfg.prefix}-appdata-$stamp", "-",
                        )
                        val rc = borg(ctx, cfg, pass, args, s(R.string.phase_appdata),
                            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd), ::line)
                        line(s(R.string.log_exit, "borg import-tar", rc)); note(rc)
                        val tar = runCatching { b.service.finishTar() }.getOrElse { "-1\n${it.message}" }
                        val tarRc = tar.substringBefore('\n').trim().toIntOrNull() ?: -1
                        val tarErr = tar.substringAfter('\n', "").lines().filter { it.isNotBlank() }
                        if (tarRc != 0 || tarErr.isNotEmpty()) {
                            line(s(R.string.log_tar_warn, tarRc))
                            tarErr.take(30).forEach { line("  " + it.removePrefix("tar: ")) }
                            if (tarErr.size > 30) line("  " + s(R.string.log_more, tarErr.size - 30))
                            note(1)
                        }
                    }
                }
            }

            // 3) Ausdünnen + Platz freigeben
            if (worst < 2 && cfg.pruneEnabled) {
                val kinds = listOf("files") + if (cfg.includeAppData) listOf("appdata") else emptyList()
                for (kind in kinds) {
                    _progress.value = Progress(s(R.string.phase_prune))
                    line("=== prune ${cfg.prefix}-$kind-*")
                    val rc = borg(ctx, cfg, pass, listOf(
                        "prune", "--log-json", "--list", "--glob-archives", "${cfg.prefix}-$kind-*",
                        "--keep-daily", cfg.keepDaily.toString(), "--keep-weekly", cfg.keepWeekly.toString(),
                        "--keep-monthly", cfg.keepMonthly.toString(),
                    ), s(R.string.phase_prune), null, ::line)
                    line(s(R.string.log_exit, "borg prune", rc)); note(rc)
                }
                if (worst < 2 && cfg.compact) {
                    _progress.value = Progress(s(R.string.phase_compact))
                    line("=== compact")
                    val rc = borg(ctx, cfg, pass, listOf("compact", "--log-json"), s(R.string.phase_compact), null, ::line)
                    line(s(R.string.log_exit, "borg compact", rc)); note(rc)
                }
            }
            return finish(ctx, rec, worst, log)
        } catch (e: CancellationException) {
            line(s(R.string.log_cancelled))
            withContext(NonCancellable) { finish(ctx, rec, 2, log, cancelled = true) }
            throw e
        } catch (e: Exception) {
            line(s(R.string.log_error, e.message ?: e.javaClass.simpleName))
            return finish(ctx, rec, 2, log)
        } finally {
            _progress.value = null
            mutex.unlock()
        }
    }

    private val ALWAYS_EXCLUDE = listOf(
        // für die App ohnehin nicht lesbar – wird ggf. über Shizuku separat gesichert
        "pp:/storage/emulated/0/Android/data",
        "pp:/storage/emulated/0/Android/obb",
    )

    /** 0 = OK, 1 = Warnung, 2 = Fehler (auch für borgs "modern" Exit-Codes). */
    private fun classify(rc: Int) = when (rc) {
        0 -> 0
        1, in 100..127 -> 1
        else -> 2
    }

    private fun finish(ctx: Context, rec: RunRecord, worst: Int, log: StringBuilder, cancelled: Boolean = false): RunRecord {
        val status = when {
            cancelled -> RunStatus.ERROR
            worst == 0 -> RunStatus.OK
            worst == 1 -> RunStatus.WARNING
            else -> RunStatus.ERROR
        }
        val text = synchronized(log) { log.toString().trimEnd() }
        val hint = if (status == RunStatus.ERROR) Borg.explain(ctx, text) else null
        return HistoryRepo.finish(rec.id, status, worst, if (hint != null) "${ctx.getString(R.string.hint_prefix)} $hint\n\n$text" else text)
    }

    /** Führt borg mit --log-json aus, setzt die Fortschrittsanzeige und schreibt Meldungen ins Log. */
    private suspend fun borg(
        ctx: Context, cfg: BackupConfig, pass: String, args: List<String>, phase: String,
        stdin: java.io.InputStream?, line: (String) -> Unit,
    ): Int {
        var lastUpdate = 0L
        return BorgRuntime.runBorg(BorgRuntime.borgProcess(ctx, cfg, pass, args), ctx, stdin) { raw ->
            val o = if (raw.startsWith("{")) runCatching { JSONObject(raw) }.getOrNull() else null
            if (o == null) {
                line(raw)
                return@runBorg
            }
            when (o.optString("type")) {
                "archive_progress" -> {
                    val now = System.currentTimeMillis()
                    if (!o.optBoolean("finished") && now - lastUpdate > 500) {
                        lastUpdate = now
                        _progress.value = Progress(phase, o.optLong("nfiles"), o.optLong("original_size"), o.optString("path"))
                    }
                }
                "progress_message", "progress_percent" -> {
                    val msg = o.optString("message")
                    if (msg.isNotBlank() && !o.optBoolean("finished")) _progress.value = Progress(phase, path = msg)
                }
                "log_message" -> {
                    val level = o.optString("levelname")
                    val msg = o.optString("message")
                    line(if (level == "WARNING" || level == "ERROR" || level == "CRITICAL") "$level: $msg" else msg)
                }
                "file_status" -> line("${o.optString("status")} ${o.optString("path")}")
                else -> line(raw)
            }
        }
    }
}

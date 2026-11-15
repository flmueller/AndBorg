package de.flokke.borgapp.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class RunStatus { RUNNING, OK, WARNING, ERROR }

data class RunRecord(
    val id: Long,
    val trigger: String,
    val status: RunStatus,
    val endTime: Long = 0,
    val exitCode: Int? = null,
    val output: String = "",
) {
    /** Kurzer Text für Benachrichtigung und Liste */
    fun shortSummary(): String {
        // Nur die Zusammenfassung oben, nicht den angehängten Log-Auszug (sonst doppelt gezählt)
        val all = output.lines()
        val lines = all.takeWhile { !it.startsWith("--- Log") }.ifEmpty { all }
        fun find(prefix: String) = lines.firstOrNull { it.trimStart().startsWith(prefix) }?.trim()
        return when (status) {
            RunStatus.RUNNING -> "Läuft …"
            RunStatus.OK, RunStatus.WARNING -> listOfNotNull(
                find("Duration:")?.replace("Duration:", "Dauer:"),
                lines.filter { it.trimStart().startsWith("Number of files:") }
                    .sumOf { it.substringAfter(":").trim().toLongOrNull() ?: 0 }
                    .takeIf { it > 0 }?.let { "Dateien: $it" },
                lines.filter { it.trimStart().startsWith("This archive:") }.mapNotNull { sizes(it) }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" + ") { (orig, dedup) -> "$orig gesichert, davon neu: $dedup" },
                if (status == RunStatus.WARNING) "Warnungen – Details im Verlauf" else null,
            ).joinToString("\n").ifBlank { "Exit-Code ${exitCode ?: "?"}" }
            RunStatus.ERROR -> all.firstOrNull { it.startsWith("Hinweis: ") }?.removePrefix("Hinweis: ") ?: (all.firstOrNull { l -> !l.contains("terminating") && ERROR_HINTS.any { l.contains(it, true) } }
                ?: all.lastOrNull { it.contains("terminating") })
                ?.trim()?.removePrefix("Remote: ") ?: "Exit-Code ${exitCode ?: "?"}"
        }
    }

    /** "This archive:  900.01 kB  900.18 kB  0 B" → (Original, Dedupliziert) */
    private fun sizes(line: String): Pair<String, String>? {
        val m = Regex("""([\d.]+ [kMGTP]?B)""").findAll(line).map { it.value }.toList()
        return if (m.size >= 3) m[0] to m[2] else null
    }

    fun toJson() = JSONObject().apply {
        put("id", id); put("trigger", trigger); put("status", status.name); put("endTime", endTime)
        exitCode?.let { put("exitCode", it) }; put("output", output)
    }

    companion object {
        private val ERROR_HINTS = listOf(
            "error", "fehler", "refused", "denied", "failed", "not found", "timed out", "does not exist",
            "Could not resolve", "No route", "passphrase", "Is borg working",
        )

        fun fromJson(o: JSONObject) = RunRecord(
            id = o.getLong("id"),
            trigger = o.optString("trigger"),
            status = runCatching { RunStatus.valueOf(o.getString("status")) }.getOrDefault(RunStatus.ERROR),
            endTime = o.optLong("endTime"),
            exitCode = if (o.has("exitCode")) o.getInt("exitCode") else null,
            output = o.optString("output"),
        )
    }
}

/** Verlauf der Backup-Läufe als JSON-Datei im App-Verzeichnis. */
object HistoryRepo {
    private const val MAX = 50
    private lateinit var file: File
    private val _runs = MutableStateFlow<List<RunRecord>>(emptyList())
    val runs: StateFlow<List<RunRecord>> = _runs

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "history.json")
        _runs.value = runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { RunRecord.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList()).map {
            // Prozess wurde während eines Laufs beendet
            if (it.status == RunStatus.RUNNING) it.copy(status = RunStatus.ERROR, output = it.output + "\nUnterbrochen (App wurde beendet).") else it
        }
    }

    @Synchronized
    private fun update(transform: (List<RunRecord>) -> List<RunRecord>) {
        val list = transform(_runs.value).sortedByDescending { it.id }.take(MAX)
        _runs.value = list
        runCatching { file.writeText(JSONArray(list.map { it.toJson() }).toString()) }
    }

    fun start(trigger: String): RunRecord {
        val r = RunRecord(id = System.currentTimeMillis(), trigger = trigger, status = RunStatus.RUNNING)
        update { it + r }
        return r
    }

    fun finish(id: Long, status: RunStatus, exitCode: Int, output: String): RunRecord {
        var out: RunRecord? = null
        update { l ->
            val rec = (l.firstOrNull { it.id == id } ?: RunRecord(id = id, trigger = "?", status = status)).copy(
                status = status, endTime = System.currentTimeMillis(), exitCode = exitCode, output = output
            )
            out = rec
            l.filter { it.id != rec.id } + rec
        }
        return out!!
    }

    fun clear() = update { emptyList() }
}

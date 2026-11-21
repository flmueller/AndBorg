package de.flokke.borgapp.runtime

import android.content.Context
import de.flokke.borgapp.R
import de.flokke.borgapp.data.BackupConfig

data class BorgResult(val exitCode: Int, val output: String) {
    val ok get() = exitCode == 0
}

/** Einzelne borg-Befehle für die Oberfläche (Verbindung testen, Repo anlegen, Archive anzeigen). */
object Borg {
    private suspend fun exec(ctx: Context, cfg: BackupConfig, pass: String, vararg args: String): BorgResult {
        val (rc, out) = BorgRuntime.runBorgCollect(BorgRuntime.borgProcess(ctx, cfg, pass, args.toList()), ctx)
        return BorgResult(rc, out)
    }

    suspend fun version(ctx: Context): String =
        exec(ctx, BackupConfig(), "", "--version").output.lineSequence().lastOrNull { it.startsWith("borg") } ?: "?"

    suspend fun info(ctx: Context, cfg: BackupConfig, pass: String) = exec(ctx, cfg, pass, "info")

    suspend fun init(ctx: Context, cfg: BackupConfig, pass: String): BorgResult {
        val r = exec(ctx, cfg, pass, "init", "--encryption=repokey-blake2", "--make-parent-dirs")
        if (!r.ok) return r
        val key = exec(ctx, cfg, pass, "key", "export", "--paper")
        return BorgResult(0, ctx.getString(R.string.init_done, key.output))
    }

    suspend fun list(ctx: Context, cfg: BackupConfig, pass: String) =
        exec(ctx, cfg, pass, "list", "--format", "{archive}{NL}  {time}{NL}")

    /** Hebt die Sperre im Repository auf (nur wenn sicher kein anderer Rechner gerade darauf sichert). */
    suspend fun breakLock(ctx: Context, cfg: BackupConfig, pass: String): BorgResult {
        val r = exec(ctx, cfg, pass, "break-lock")
        return if (r.ok) BorgResult(0, ctx.getString(R.string.lock_broken, r.output)) else r
    }

    /** Verständlicher Hinweis zu typischen Fehlermeldungen (oder null). */
    /**
     * Vorübergehende Fehler, bei denen sich ein neuer Versuch lohnt: Server/Netz kurz weg,
     * Verbindung abgebrochen, Repository gerade von einem anderen Rechner gesperrt.
     */
    fun isTransient(output: String): Boolean {
        val o = output.lowercase()
        // Konfigurationsfehler: erneut versuchen ändert nichts
        val permanent = listOf(
            "borg: not found", "borg: command not found", "permission denied (publickey",
            "host key verification failed", "remote host identification has changed",
            "passphrase supplied in borg_passphrase", "passphrase is incorrect",
            "is not a valid repository",
        )
        if (permanent.any { it in o }) return false
        if ("does not exist" in o && "repository" in o) return false
        return listOf(
            "connection refused", "could not resolve", "name or service not known", "no address associated",
            "timed out", "no route to host", "network is unreachable", "connection reset", "connection closed",
            "broken pipe", "connection lost", "kex_exchange_identification", "closed by remote host",
            "failed to create/acquire the lock", "lock.exclusive", "lock timeout",
        ).any { it in o }
    }

    fun explain(ctx: Context, output: String): String? {
        val o = output.lowercase()
        val id = when {
            "borg: not found" in o || "borg: command not found" in o || "borg: no such file" in o -> R.string.hint_borg_not_found
            "permission denied (publickey" in o -> R.string.hint_publickey
            "host key verification failed" in o || "remote host identification has changed" in o -> R.string.hint_hostkey
            "connection refused" in o -> R.string.hint_refused
            "could not resolve" in o || "name or service not known" in o || "no address associated" in o -> R.string.hint_resolve
            "timed out" in o || "no route to host" in o || "network is unreachable" in o -> R.string.hint_unreachable
            "passphrase supplied in borg_passphrase" in o || "passphrase is incorrect" in o -> R.string.hint_passphrase
            "does not exist" in o && "repository" in o -> R.string.hint_no_repo
            "is not a valid repository" in o -> R.string.hint_not_repo
            "already exists" in o && "repository" in o -> R.string.hint_exists
            "failed to create/acquire the lock" in o || "lock.exclusive" in o -> R.string.hint_locked
            else -> return null
        }
        return ctx.getString(id)
    }

    /** Zerlegt eine Kommandozeile in Argumente (einfache und doppelte Anführungszeichen). */
    fun splitArgs(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var has = false
        for (ch in s) {
            when {
                quote != null -> if (ch == quote) quote = null else cur.append(ch)
                ch == '\'' || ch == '"' -> { quote = ch; has = true }
                ch.isWhitespace() -> if (has || cur.isNotEmpty()) { out += cur.toString(); cur.clear(); has = false }
                else -> cur.append(ch)
            }
        }
        if (has || cur.isNotEmpty()) out += cur.toString()
        return out
    }
}

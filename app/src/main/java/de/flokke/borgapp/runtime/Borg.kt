package de.flokke.borgapp.runtime

import android.content.Context
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
        return BorgResult(
            0,
            "Repository angelegt.\n\nBitte diesen Schlüssel (zusammen mit der Passphrase) sicher aufbewahren – " +
                "ohne ihn ist das Backup bei Beschädigung des Repositorys verloren:\n\n" + key.output
        )
    }

    suspend fun list(ctx: Context, cfg: BackupConfig, pass: String) =
        exec(ctx, cfg, pass, "list", "--format", "{archive}{NL}  {time}{NL}")

    /** Hebt die Sperre im Repository auf (nur wenn sicher kein anderer Rechner gerade darauf sichert). */
    suspend fun breakLock(ctx: Context, cfg: BackupConfig, pass: String): BorgResult {
        val r = exec(ctx, cfg, pass, "break-lock")
        return if (r.ok) BorgResult(0, "Sperre aufgehoben.\n" + r.output) else r
    }

    /** Verständlicher Hinweis zu typischen Fehlermeldungen (oder null). */
    fun explain(output: String): String? {
        val o = output.lowercase()
        return when {
            "borg: not found" in o || "borg: command not found" in o || "borg: no such file" in o ->
                "Auf dem Server wurde borg nicht gefunden. Auf dem Server „which borg“ ausführen und den Pfad " +
                    "in den Einstellungen unter „Remote-Pfad zu borg“ eintragen (z. B. /usr/local/bin/borg)."
            "permission denied (publickey" in o ->
                "Der Server lehnt den SSH-Schlüssel ab. Den öffentlichen Schlüssel (Status → Einrichtung) " +
                    "auf dem Server in ~/.ssh/authorized_keys eintragen."
            "host key verification failed" in o || "remote host identification has changed" in o ->
                "Der Host-Schlüssel des Servers hat sich geändert. In den Einstellungen „Bekannte Server vergessen“ wählen, falls das gewollt ist."
            "connection refused" in o -> "Der Server nimmt keine Verbindung an – Adresse und Port prüfen."
            "could not resolve" in o || "name or service not known" in o || "no address associated" in o ->
                "Der Servername konnte nicht aufgelöst werden – Adresse und Netzwerk prüfen."
            "timed out" in o || "no route to host" in o || "network is unreachable" in o ->
                "Der Server ist nicht erreichbar – Netzwerk, Adresse und Port prüfen."
            "passphrase supplied in borg_passphrase" in o || "passphrase is incorrect" in o ->
                "Die Passphrase passt nicht zu diesem Repository."
            "does not exist" in o && "repository" in o ->
                "Unter dieser Adresse gibt es noch kein Repository. Mit „Neu anlegen“ erstellen."
            "is not a valid repository" in o -> "Unter dieser Adresse liegt kein Borg-Repository."
            "already exists" in o && "repository" in o -> "Dort existiert bereits ein Repository – „Verbindung testen“ verwenden."
            "failed to create/acquire the lock" in o || "lock.exclusive" in o ->
                "Das Repository ist gesperrt. Läuft gerade auf einem anderen Rechner ein Backup, abwarten. " +
                    "Sonst ist es eine verwaiste Sperre: Einstellungen → „Sperre aufheben“."
            else -> null
        }
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

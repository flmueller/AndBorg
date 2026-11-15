package de.flokke.borgapp.runtime

import android.content.Context
import android.os.Build
import de.flokke.borgapp.data.BackupConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Die mitgelieferte borg-Laufzeit (aus Termux-Paketen):
 * - Programme (python, ssh, ssh-keygen) liegen als lib*_exec.so im nativeLibraryDir – nur dort dürfen
 *   Apps ab Android 10 Programme starten.
 * - Bibliotheken, Python-Standardbibliothek und borg kommen aus assets/runtime-<abi>.zip und werden
 *   beim ersten Start nach files/rt entpackt.
 */
object BorgRuntime {
    private const val PY = "libpython_exec.so"
    private const val SSH = "libssh_exec.so"
    private const val KEYGEN = "libsshkeygen_exec.so"
    /** Bewusst: msgpack 1.0.8 ohne C-Erweiterung (die Termux-Version ist für borg 1.4 zu neu). */
    private const val PURE_MSGPACK = "Using a pure-python msgpack"
    private const val BORG_MAIN = "import sys; sys.argv[0] = 'borg'; from borg.archiver import main; main()"

    fun root(ctx: Context) = File(ctx.filesDir, "rt")
    fun home(ctx: Context) = File(ctx.filesDir, "home")
    fun keyFile(ctx: Context) = File(home(ctx), ".ssh/id_ed25519")
    private fun exe(ctx: Context, name: String) = File(ctx.applicationInfo.nativeLibraryDir, name)

    /** Entpackt die Laufzeit, falls nötig (nach Installation oder Update der App). */
    @Synchronized
    fun ensure(ctx: Context): File {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val stamp = "${pi.longVersionCode}-${pi.lastUpdateTime}"
        val r = root(ctx)
        val marker = File(r, ".stamp")
        if (marker.exists() && marker.readText() == stamp) return r
        val abi = Build.SUPPORTED_ABIS.firstOrNull { exe(ctx, PY).exists() && assetExists(ctx, "runtime-$it.zip") }
            ?: error("borg ist für diese Geräte-Architektur nicht enthalten (${Build.SUPPORTED_ABIS.joinToString()})")
        r.deleteRecursively()
        r.mkdirs()
        ZipInputStream(ctx.assets.open("runtime-$abi.zip").buffered()).use { zis ->
            val base = r.canonicalPath + File.separator
            while (true) {
                val e = zis.nextEntry ?: break
                val f = File(r, e.name)
                if (!f.canonicalPath.startsWith(base)) continue
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zis.copyTo(it) }
                }
            }
        }
        marker.writeText(stamp)
        return r
    }

    private fun assetExists(ctx: Context, name: String) =
        runCatching { ctx.assets.open(name).close(); true }.getOrDefault(false)

    private fun baseEnv(ctx: Context): MutableMap<String, String> {
        val r = root(ctx)
        val tmp = File(ctx.cacheDir, "tmp").apply { mkdirs() }
        home(ctx).mkdirs()
        return mutableMapOf(
            "HOME" to home(ctx).path,
            "TMPDIR" to tmp.path,
            "LD_LIBRARY_PATH" to "${r.path}/lib",
            "PYTHONHOME" to r.path,
            "SSL_CERT_FILE" to "${r.path}/etc/tls/cert.pem",
            "LANG" to "C.UTF-8",
            "PATH" to "${ctx.applicationInfo.nativeLibraryDir}:/system/bin",
        )
    }

    /** Kommando, mit dem borg ssh aufruft (wird von borg mit shlex zerlegt). */
    private fun rsh(ctx: Context, cfg: BackupConfig): String {
        val ssh = home(ctx).resolve(".ssh")
        return listOf(
            exe(ctx, SSH).path, "-F", "/dev/null",
            "-i", keyFile(ctx).path,
            "-o", "UserKnownHostsFile=${ssh.path}/known_hosts",
            "-o", "StrictHostKeyChecking=accept-new",
            "-o", "BatchMode=yes",
            "-o", "ConnectTimeout=20",
            "-o", "ServerAliveInterval=30",
            "-o", "ServerAliveCountMax=6",
        ).joinToString(" ") + if (cfg.sshOptions.isNotBlank()) " " + cfg.sshOptions.trim() else ""
    }

    fun borgProcess(ctx: Context, cfg: BackupConfig, passphrase: String, args: List<String>): ProcessBuilder {
        ensure(ctx)
        val pb = ProcessBuilder(listOf(exe(ctx, PY).path, "-c", BORG_MAIN) + args)
        pb.directory(home(ctx))
        pb.environment().apply {
            clear()
            putAll(baseEnv(ctx))
            put("BORG_BASE_DIR", File(ctx.filesDir, "borg").path)
            put("BORG_REPO", cfg.repo.trim())
            put("BORG_PASSPHRASE", passphrase)
            put("BORG_RSH", rsh(ctx, cfg))
            if (cfg.remotePath.isNotBlank()) put("BORG_REMOTE_PATH", cfg.remotePath.trim())
            put("BORG_HOSTNAME_IS_UNIQUE", "yes")
            // Auf Android liefert borg sonst bei jedem Start eine andere (zufällige) Host-ID und kann eigene,
            // verwaiste Sperren dann nie als verwaist erkennen.
            put("BORG_HOST_ID", hostId(ctx))
            put("BORG_RELOCATED_REPO_ACCESS_IS_OK", "yes")
            put("BORG_UNKNOWN_UNENCRYPTED_REPO_ACCESS_IS_OK", "no")
            put("BORG_DISPLAY_PASSPHRASE", "no")
        }
        return pb
    }

    private val borgMutex = Mutex()

    /** Feste, zufällig erzeugte ID dieses Geräts für borgs Sperren. */
    @Synchronized
    fun hostId(ctx: Context): String {
        val f = File(ctx.filesDir, "host_id")
        val id = runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: java.util.UUID.randomUUID().toString().also { f.writeText(it) }
        return "${BackupConfig.sanitizePrefix(Build.MODEL ?: "android")}@borgapp-$id"
    }

    /**
     * Lokale Sperren im borg-Cache entfernen. Nur aufrufen, während [borgMutex] gehalten wird: Dann läuft
     * garantiert kein anderer borg-Prozess der App, jede vorhandene lokale Sperre ist also verwaist
     * (z. B. weil Android die App während eines Backups beendet hat).
     */
    private fun clearLocalLocks(ctx: Context): Int {
        var n = 0
        File(ctx.filesDir, "borg").walkBottomUp().forEach { f ->
            val name = f.name
            if (name == "lock.roster" || name == "lock.exclusive" || f.parentFile?.name == "lock.exclusive") {
                if (f.deleteRecursively()) n++
            }
        }
        return n
    }

    /** borg-Prozess ausführen – immer nur einer gleichzeitig, vorher verwaiste lokale Sperren entfernen. */
    suspend fun runBorg(pb: ProcessBuilder, ctx: Context, stdin: InputStream? = null, onLine: (String) -> Unit): Int =
        borgMutex.withLock {
            withContext(Dispatchers.IO) { clearLocalLocks(ctx) }
            run(pb, stdin, onLine)
        }

    suspend fun runBorgCollect(pb: ProcessBuilder, ctx: Context): Pair<Int, String> {
        val sb = StringBuilder()
        val rc = runBorg(pb, ctx) { synchronized(sb) { sb.appendLine(it) } }
        return rc to sb.toString().trimEnd()
    }

    /** Erzeugt den SSH-Schlüssel, falls noch keiner existiert; liefert den öffentlichen Schlüssel. */
    suspend fun ensureKey(ctx: Context): String = withContext(Dispatchers.IO) {
        val key = keyFile(ctx)
        val pub = File(key.path + ".pub")
        if (!key.exists() || !pub.exists()) {
            ensure(ctx)
            key.parentFile!!.mkdirs()
            key.delete(); pub.delete()
            val model = BackupConfig.sanitizePrefix(Build.MODEL ?: "android")
            val pb = ProcessBuilder(exe(ctx, KEYGEN).path, "-q", "-t", "ed25519", "-N", "", "-C", "andborg@$model", "-f", key.path)
            pb.environment().apply { clear(); putAll(baseEnv(ctx)) }
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0 || !pub.exists()) error("ssh-keygen fehlgeschlagen: $out")
        }
        pub.readText().trim()
    }

    /**
     * Startet den Prozess und liefert jede Ausgabezeile (stdout+stderr) an [onLine].
     * [stdin] wird – falls angegeben – in einem eigenen Thread in den Prozess geschrieben.
     * Wird die Coroutine abgebrochen, wird der Prozess beendet.
     */
    suspend fun run(pb: ProcessBuilder, stdin: InputStream? = null, onLine: (String) -> Unit): Int =
        withContext(Dispatchers.IO) {
            pb.redirectErrorStream(true)
            val p = pb.start()
            val feeder = if (stdin != null) Thread {
                try {
                    stdin.use { input -> p.outputStream.use { input.copyTo(it, 256 * 1024) } }
                } catch (_: Exception) {
                    runCatching { p.outputStream.close() }
                }
            }.apply { isDaemon = true; start() } else {
                p.outputStream.close(); null
            }
            val reader = Thread {
                runCatching {
                    p.inputStream.bufferedReader().forEachLine { if (!it.contains(PURE_MSGPACK)) onLine(it) }
                }
            }.apply { isDaemon = true; start() }
            try {
                while (p.isAlive) delay(250)
                reader.join(5000)
                p.exitValue()
            } finally {
                if (p.isAlive) {
                    p.destroy() // SIGTERM: borg beendet sich sauber
                    if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly()
                }
                feeder?.interrupt()
                runCatching { stdin?.close() }
            }
        }

    /** Einfaches Kommando ausführen und die gesamte Ausgabe zurückgeben. */
    suspend fun runCollect(pb: ProcessBuilder): Pair<Int, String> {
        val sb = StringBuilder()
        val rc = run(pb) { synchronized(sb) { sb.appendLine(it) } }
        return rc to sb.toString().trimEnd()
    }
}

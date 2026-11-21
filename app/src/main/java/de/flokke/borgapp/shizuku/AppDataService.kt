package de.flokke.borgapp.shizuku

import android.os.ParcelFileDescriptor
import kotlin.system.exitProcess

/**
 * Shizuku-UserService: läuft in einem eigenen Prozess als Shell-Benutzer (uid 2000).
 * Dieser darf unter Android 11+ Android/data und Android/obb lesen, die App selbst nicht.
 */
class AppDataService : IAppDataService.Stub() {
    @Volatile private var proc: Process? = null
    @Volatile private var pump: Thread? = null
    private val err = StringBuilder()

    override fun destroy() {
        proc?.destroy()
        exitProcess(0)
    }

    override fun startTar(args: Array<String>): ParcelFileDescriptor {
        proc?.destroy()
        synchronized(err) { err.setLength(0) }
        val p = ProcessBuilder(listOf("tar") + args).start()
        proc = p
        val pipe = ParcelFileDescriptor.createPipe()
        pump = Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out -> p.inputStream.copyTo(out, 256 * 1024) }
            } catch (e: Exception) {
                // Leser (borg) hat abgebrochen
                synchronized(err) { err.appendLine("Pipe: ${e.message}") }
                p.destroy()
            }
        }.apply { start() }
        Thread {
            runCatching {
                p.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(err) { if (err.length < 64_000) err.appendLine(line) }
                }
            }
        }.apply { isDaemon = true; start() }
        return pipe[0]
    }

    override fun finishTar(): String {
        val p = proc ?: return "-1\nno tar process"
        pump?.join()
        val rc = p.waitFor()
        Thread.sleep(200)
        proc = null
        return "$rc\n" + synchronized(err) { err.toString() }
    }
}

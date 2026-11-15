package de.flokke.borgapp.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import de.flokke.borgapp.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

object ShizukuHelper {
    const val PACKAGE = "moe.shizuku.privileged.api"
    const val REQUEST_CODE = 4711

    fun state(ctx: Context): ShizukuState {
        val running = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)
        if (!running) {
            val installed = runCatching { ctx.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)
            return if (installed) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
        }
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        return if (granted) ShizukuState.READY else ShizukuState.NO_PERMISSION
    }

    fun requestPermission() = runCatching { Shizuku.requestPermission(REQUEST_CODE) }

    class Bound(val service: IAppDataService, private val unbind: () -> Unit) : AutoCloseable {
        override fun close() = unbind()
    }

    /** Startet den AppDataService im Shizuku-Prozess und verbindet sich damit. */
    suspend fun bind(ctx: Context): Bound = withContext(Dispatchers.Main) {
        val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, AppDataService::class.java.name))
            .daemon(false)
            .processNameSuffix("appdata")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        var conn: ServiceConnection? = null
        val unbind = { conn?.let { c -> runCatching { Shizuku.unbindUserService(args, c, true) } }; Unit }
        val service = try { withTimeout(30_000) {
            suspendCancellableCoroutine<IAppDataService> { cont ->
                val c = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (binder != null && binder.pingBinder() && cont.isActive) {
                            cont.resume(IAppDataService.Stub.asInterface(binder))
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {}
                }
                conn = c
                Shizuku.bindUserService(args, c)
            }
        } } catch (e: Throwable) {
            unbind()
            throw e
        }
        Bound(service, unbind)
    }
}

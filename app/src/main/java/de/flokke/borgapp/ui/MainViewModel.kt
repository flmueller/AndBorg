package de.flokke.borgapp.ui

import android.app.Application
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.flokke.borgapp.data.BackupConfig
import de.flokke.borgapp.data.ConfigStore
import de.flokke.borgapp.data.HistoryRepo
import de.flokke.borgapp.data.RunRecord
import de.flokke.borgapp.runtime.Borg
import de.flokke.borgapp.runtime.BorgResult
import de.flokke.borgapp.runtime.BorgRuntime
import de.flokke.borgapp.shizuku.ShizukuHelper
import de.flokke.borgapp.shizuku.ShizukuState
import de.flokke.borgapp.work.BackupEngine
import de.flokke.borgapp.work.BackupScheduler
import de.flokke.borgapp.work.Progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import de.flokke.borgapp.R
import rikka.shizuku.Shizuku
import java.io.File

data class SetupState(
    val storage: Boolean = false,
    val battery: Boolean = false,
    val shizuku: ShizukuState = ShizukuState.NOT_INSTALLED,
    val publicKey: String = "",
    val borgVersion: String = "",
    val runtimeError: String? = null,
)

data class OutputDialog(val title: String, val text: String, val success: Boolean? = null)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val store = ConfigStore(app)
    private fun str(id: Int, vararg args: Any): String = ctx.getString(id, *args)

    val config: StateFlow<BackupConfig> = store.config.stateIn(viewModelScope, SharingStarted.Eagerly, BackupConfig())
    val passphrase = MutableStateFlow("")
    val verifiedRepo: StateFlow<String> = store.verifiedRepo.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val history: StateFlow<List<RunRecord>> = HistoryRepo.runs
    val progress: StateFlow<Progress?> = BackupEngine.progress
    val nextRun: StateFlow<Long?> = BackupScheduler.workInfoFlow(app)
        .map { l -> l.firstOrNull { !it.state.isFinished }?.nextScheduleTimeMillis?.takeIf { it > 0 && it < Long.MAX_VALUE } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val setup = MutableStateFlow(SetupState())
    val busy = MutableStateFlow<String?>(null)
    val dialog = MutableStateFlow<OutputDialog?>(null)
    val snackbar = MutableStateFlow<String?>(null)

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshLocal() }
    private val binderListener = Shizuku.OnBinderReceivedListener { refreshLocal() }

    init {
        viewModelScope.launch { passphrase.value = store.loadPassphrase() }
        // Zeitplan nach App-Updates mit aktuellen Einstellungen (z. B. Wiederholungsregeln) neu anwenden;
        // UPDATE behält den nächsten Termin bei.
        viewModelScope.launch(Dispatchers.IO) { BackupScheduler.apply(ctx, store.load()) }
        runCatching {
            Shizuku.addRequestPermissionResultListener(shizukuListener)
            Shizuku.addBinderReceivedListenerSticky(binderListener)
        }
        refreshLocal()
        prepareRuntime()
    }

    override fun onCleared() {
        runCatching {
            Shizuku.removeRequestPermissionResultListener(shizukuListener)
            Shizuku.removeBinderReceivedListener(binderListener)
        }
    }

    /** Entpackt borg (beim ersten Start einige Sekunden) und erzeugt den SSH-Schlüssel. */
    private fun prepareRuntime() = viewModelScope.launch {
        busy.value = str(R.string.busy_preparing)
        try {
            val key = BorgRuntime.ensureKey(ctx)
            val ver = Borg.version(ctx)
            setup.value = setup.value.copy(publicKey = key, borgVersion = ver, runtimeError = null)
        } catch (e: Exception) {
            setup.value = setup.value.copy(runtimeError = e.message ?: e.toString())
        } finally {
            busy.value = null
        }
    }

    fun refreshLocal() {
        val pm = ctx.getSystemService(PowerManager::class.java)
        setup.value = setup.value.copy(
            storage = de.flokke.borgapp.Compat.hasStorageAccess(ctx),
            battery = pm.isIgnoringBatteryOptimizations(ctx.packageName),
            shizuku = ShizukuHelper.state(ctx),
        )
    }

    private val saveMutex = kotlinx.coroutines.sync.Mutex()

    /** Speichert die Einstellungen (automatisch aus der Einstellungsseite) und übernimmt den Zeitplan. */
    fun save(cfg: BackupConfig, pass: String) = viewModelScope.launch(Dispatchers.IO) {
        saveMutex.withLock {
            if (cfg != config.value) store.save(cfg)
            if (pass != passphrase.value) {
                store.savePassphrase(pass)
                passphrase.value = pass
            }
            BackupScheduler.apply(ctx, cfg)
        }
    }

    fun saveIfChanged(cfg: BackupConfig, pass: String) {
        if (cfg != config.value || pass != passphrase.value) save(cfg, pass)
    }

    private fun borgOp(label: Int, title: Int, op: suspend (BackupConfig, String) -> BorgResult, onOk: suspend () -> Unit = {}) {
        if (busy.value != null) return
        if (BackupEngine.running) {
            snackbar.value = str(R.string.backup_running_wait)
            return
        }
        val cfg = config.value
        val pass = passphrase.value
        if (cfg.repo.isBlank() || pass.isEmpty()) {
            snackbar.value = str(R.string.need_repo_pass)
            return
        }
        viewModelScope.launch {
            busy.value = str(label)
            try {
                val r = withContext(Dispatchers.IO) { op(cfg, pass) }
                if (r.ok) onOk()
                val hint = if (!r.ok) Borg.explain(ctx, r.output) else null
                dialog.value = OutputDialog(
                    str(title),
                    (if (hint != null) "$hint\n\n${str(R.string.borg_output_header)}\n" else "") + r.output.ifBlank { str(R.string.no_output_exit, r.exitCode) },
                    r.ok,
                )
            } catch (e: Exception) {
                dialog.value = OutputDialog(str(title), e.message ?: e.toString(), false)
            } finally {
                busy.value = null
            }
        }
    }

    fun testRepo() = borgOp(R.string.busy_testing, R.string.test_connection, { c, p -> Borg.info(ctx, c, p) }) {
        store.setVerified(config.value.repo)
    }

    fun initRepo() = borgOp(R.string.busy_init, R.string.title_init, { c, p -> Borg.init(ctx, c, p) }) {
        store.setVerified(config.value.repo)
    }

    fun breakLock() = borgOp(R.string.busy_break, R.string.break_lock, { c, p -> Borg.breakLock(ctx, c, p) })

    fun listArchives() = borgOp(R.string.busy_list, R.string.title_archives, { c, p -> Borg.list(ctx, c, p) })

    fun backupNow() {
        if (BackupEngine.running) {
            snackbar.value = str(R.string.backup_already_running)
            return
        }
        BackupScheduler.runNow(ctx)
        snackbar.value = str(R.string.backup_started)
    }

    fun cancelBackup() = viewModelScope.launch(Dispatchers.IO) {
        BackupScheduler.cancelRunning(ctx)
        BackupScheduler.apply(ctx, config.value) // Zeitplan wieder einplanen
    }

    fun forgetKnownHosts() {
        File(BorgRuntime.home(ctx), ".ssh/known_hosts").delete()
        snackbar.value = str(R.string.hosts_forgotten)
    }

    fun newKey() = viewModelScope.launch {
        BorgRuntime.keyFile(ctx).delete()
        File(BorgRuntime.keyFile(ctx).path + ".pub").delete()
        runCatching { BorgRuntime.ensureKey(ctx) }
            .onSuccess { setup.value = setup.value.copy(publicKey = it); snackbar.value = str(R.string.key_created) }
            .onFailure { snackbar.value = it.message }
    }

    fun requestShizuku() {
        ShizukuHelper.requestPermission()
    }

    fun clearHistory() = HistoryRepo.clear()
    fun showText(title: String, text: String) { dialog.value = OutputDialog(title, text) }
    fun dismissDialog() { dialog.value = null }
    fun snackbarShown() { snackbar.value = null }
}

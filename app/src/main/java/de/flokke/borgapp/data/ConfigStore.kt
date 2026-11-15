package de.flokke.borgapp.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore("settings")

/** Speichert die Konfiguration; die Passphrase AES-GCM-verschlüsselt mit einem Android-Keystore-Schlüssel. */
class ConfigStore(private val ctx: Context) {
    private val keyConfig = stringPreferencesKey("config_json")
    private val keyPass = stringPreferencesKey("passphrase_enc")
    private val keyVerified = stringPreferencesKey("verified_repo")

    val config: Flow<BackupConfig> = ctx.dataStore.data.map { BackupConfig.fromJson(it[keyConfig]) }

    suspend fun load(): BackupConfig = config.first()

    suspend fun save(cfg: BackupConfig) {
        ctx.dataStore.edit { it[keyConfig] = cfg.toJson() }
    }

    suspend fun loadPassphrase(): String {
        val enc = ctx.dataStore.data.first()[keyPass] ?: return ""
        return runCatching { Crypto.decrypt(enc) }.getOrDefault("")
    }

    suspend fun savePassphrase(pass: String) {
        ctx.dataStore.edit { it[keyPass] = Crypto.encrypt(pass) }
    }

    /** Repository-Adresse, für die zuletzt "Verbindung testen" bzw. "Neu anlegen" geklappt hat. */
    val verifiedRepo: Flow<String> = ctx.dataStore.data.map { it[keyVerified] ?: "" }

    suspend fun setVerified(repo: String) {
        ctx.dataStore.edit { it[keyVerified] = repo }
    }
}

private object Crypto {
    private const val ALIAS = "borgapp_passphrase"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String {
        val data = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return String(c.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }
}

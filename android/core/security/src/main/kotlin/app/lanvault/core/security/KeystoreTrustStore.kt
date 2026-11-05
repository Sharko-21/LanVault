package app.lanvault.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.lanvault.core.model.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class KeystoreTrustStore(context: Context, storeId: String = "v1") : TrustStore {
    init {
        require(storeId.matches(Regex("[a-z0-9-]{1,64}")))
    }

    private val file = AtomicFile(File(context.noBackupFilesDir, "desktop-trust-$storeId.enc"))
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }
    private val alias = "app.lanvault.desktop-trust.$storeId"
    private val aad = "LanVault:desktop-trust:$storeId".toByteArray()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .setKeySize(256)
                        .build()
                )
            }
            .generateKey()
    }

    @Synchronized
    fun reset() {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias)
        }
    }

    @Synchronized
    override fun read(): TrustState {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
            return TrustState()
        val encrypted =
            file.openRead().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    check(out.size() + n <= 65536) { "TRUST_STORE_TOO_LARGE" }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        check(encrypted.size >= 4 + 12 + 16 && ByteBuffer.wrap(encrypted, 0, 4).int == 1) {
            "TRUST_STORE_INVALID"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(4, 16)))
        cipher.updateAAD(aad)
        val clear = cipher.doFinal(encrypted, 16, encrypted.size - 16)
        return try {
            json.decodeFromString<TrustState>(clear.toString(Charsets.UTF_8))
        } finally {
            clear.fill(0)
        }
    }

    @Synchronized
    override fun write(state: TrustState) {
        val clear = json.encodeToString(state).toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val encrypted =
            try {
                cipher.doFinal(clear)
            } finally {
                clear.fill(0)
            }
        check(cipher.iv.size == 12 && encrypted.size <= 65520)
        val bytes =
            ByteBuffer.allocate(16 + encrypted.size).putInt(1).put(cipher.iv).put(encrypted).array()
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }
}

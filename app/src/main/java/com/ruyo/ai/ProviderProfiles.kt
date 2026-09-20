package com.ruyo.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ProviderKind(val label: String, val endpoint: String) {
    OPENAI("OpenAI", "https://api.openai.com/v1"),
    COMPATIBLE("OpenAI compatible", "https://api.deepseek.com/v1"),
    CLAUDE("Claude", "https://api.anthropic.com/v1"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta"),
}

data class ProviderProfile(val id: String = UUID.randomUUID().toString(), val name: String,
    val kind: ProviderKind, val baseUrl: String, val model: String, val hasKey: Boolean = false) {
    fun validate(): ProviderProfile {
        require(runCatching { UUID.fromString(id) }.isSuccess) { "Invalid profile ID." }
        require(name.isNotBlank() && name.length <= 60) { "Use a profile name of 1–60 characters." }
        require(model.isNotBlank() && model.length <= 120 && model.none { it.isWhitespace() || it.isISOControl() }) { "Enter the exact model ID from your provider." }
        if (kind == ProviderKind.GEMINI) require(model.removePrefix("models/").matches(Regex("[a-zA-Z0-9._-]+"))) { "Enter a Gemini model ID, without a URL." }
        val address = endpoint(baseUrl)
        return copy(name = name.trim(), model = model.trim(), baseUrl = address.toString().trimEnd('/'))
    }
    companion object {
        fun endpoint(value: String): URI {
            val uri = runCatching { URI(value.trim()) }.getOrNull()
            require(uri != null && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.port in -1..65535 && uri.port != 0) { "Enter an API base URL without credentials, query, or fragment." }
            require(uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1")) { "Use HTTPS. HTTP is available only for a model running on this phone at localhost or 127.0.0.1." }
            require(!uri.rawPath.orEmpty().contains('%') && uri.normalize() == uri) { "Use a plain API base path." }
            return uri
        }
    }
}

class ProviderSecret(val profile: ProviderProfile, val apiKey: String) {
    override fun toString() = "ProviderSecret(redacted)"
}
interface ProfileStore {
    fun list(): List<ProviderProfile>
    fun get(id: String): ProviderSecret
    fun save(profile: ProviderProfile, replacementKey: String?, removeKey: Boolean = false)
    fun delete(id: String)
}

/** Whole-file authenticated encryption. Keys and profiles are excluded from device backups. */
class EncryptedProfileStore(private val file: File, private val key: () -> SecretKey) : ProfileStore {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "providers.vault"), { androidKey() })

    @Synchronized override fun list() = read().map { it.profile }
    @Synchronized override fun get(id: String) = read().firstOrNull { it.profile.id == id } ?: error("Choose a saved provider profile.")
    @Synchronized override fun save(profile: ProviderProfile, replacementKey: String?, removeKey: Boolean) {
        val clean = profile.validate()
        val entries = read().toMutableList()
        val previous = entries.firstOrNull { it.profile.id == clean.id }
        require(previous != null || entries.size < 24) { "You can store up to 24 profiles." }
        require(previous == null || previous.profile.baseUrl == clean.baseUrl && previous.profile.kind == clean.kind || !replacementKey.isNullOrBlank() || removeKey) { "Re-enter the key when changing provider or endpoint." }
        val secret = if (removeKey) "" else replacementKey?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.apiKey.orEmpty()
        require(secret.length <= 4096 && secret.all { it.code in 33..126 }) { "The API key contains unsupported characters." }
        require(secret.isNotEmpty() || clean.kind == ProviderKind.COMPATIBLE) { "Enter an API key for this provider." }
        entries.removeAll { it.profile.id == clean.id }
        entries += ProviderSecret(clean.copy(hasKey = secret.isNotEmpty()), secret)
        write(entries)
    }
    @Synchronized override fun delete(id: String) { write(read().filterNot { it.profile.id == id }) }

    private fun read(): List<ProviderSecret> {
        if (!file.exists() && !File(file.path + ".bak").exists()) return emptyList()
        try {
            require(file.length() <= 256 * 1024)
            val bytes = AtomicFile(file).readFully()
            require(bytes.size in 30..(256 * 1024) && bytes[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD("com.ruyo.providers.v1".toByteArray())
            val plaintext = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
            val array = try { JSONArray(plaintext.toString(Charsets.UTF_8)) } finally { plaintext.fill(0) }
            require(array.length() <= 24)
            return (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i); val secret = obj.getString("key")
                ProviderSecret(ProviderProfile(obj.getString("id"), obj.getString("name"), ProviderKind.valueOf(obj.getString("kind")), obj.getString("url"), obj.getString("model"), secret.isNotEmpty()).validate(), secret)
            }
        } catch (_: Exception) { throw IllegalStateException("The encrypted provider store could not be opened. Your saved data has not been replaced.") }
    }
    private fun write(entries: List<ProviderSecret>) {
        val plaintext = JSONArray().apply { entries.forEach { e -> put(JSONObject().put("id", e.profile.id).put("name", e.profile.name)
            .put("kind", e.profile.kind.name).put("url", e.profile.baseUrl).put("model", e.profile.model).put("key", e.apiKey)) } }.toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD("com.ruyo.providers.v1".toByteArray())
            require(cipher.iv.size == 12)
            val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
            val atomic = AtomicFile(file); val output = atomic.startWrite()
            try { output.write(encrypted); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw error }
        } catch (_: Exception) { throw IllegalStateException("Could not securely save the provider. Please try again.") }
        finally { plaintext.fill(0) }
    }
    companion object {
        @Synchronized fun androidKey(alias: String = "ruyo.providers.v1"): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        }
    }
}

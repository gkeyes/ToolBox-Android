package io.toolbox.host.runtime

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.tool.runtime.RpcValue
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

internal class AndroidKeyStoreCipher(
    private val toolId: String,
) : SecureValueCipher {
    private val alias = aliasFor(toolId)

    fun hasKey(): Boolean = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }.containsAlias(alias)

    override fun encrypt(plaintext: String, associatedData: ByteArray?): String = try {
        withKey(createIfMissing = true) { key ->
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            associatedData?.let(cipher::updateAAD)
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            JSONObject()
                .put("v", formatVersion(associatedData))
                .put("iv", encoder.encodeToString(cipher.iv))
                .put("ciphertext", encoder.encodeToString(ciphertext))
                .toString()
        }
    } catch (failure: RuntimeHandlerException) {
        throw failure
    } catch (_: Exception) {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Secure storage is unavailable")
    }

    override fun decrypt(encoded: String, associatedData: ByteArray?): String = try {
        val value = JSONTokener(encoded).nextValue() as? JSONObject
            ?: throw IllegalArgumentException("ciphertext")
        require(value.optInt("v", -1) == formatVersion(associatedData))
        val iv = decoder.decode(value.getString("iv"))
        val ciphertext = decoder.decode(value.getString("ciphertext"))
        require(iv.size == GCM_IV_BYTES && ciphertext.isNotEmpty())
        withKey(createIfMissing = false) { key ->
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            associatedData?.let(cipher::updateAAD)
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }
    } catch (failure: RuntimeHandlerException) {
        throw failure
    } catch (_: Exception) {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.INTERNAL_ERROR, "Secure storage data cannot be read")
    }

    /** Reuses the loaded key; a stale handle (for example after deletion) is reloaded once. */
    private fun <T> withKey(createIfMissing: Boolean, use: (SecretKey) -> T): T {
        val cached = keys[alias]
        if (cached != null) {
            try {
                return use(cached)
            } catch (_: java.security.GeneralSecurityException) {
                keys.remove(alias, cached)
            } catch (_: java.security.ProviderException) {
                keys.remove(alias, cached)
            }
        }
        val loaded = key(createIfMissing)
        keys[alias] = loaded
        return use(loaded)
    }

    private fun key(createIfMissing: Boolean): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!createIfMissing) throw RuntimeHandlerException(
            RuntimeRpcErrorCode.INTERNAL_ERROR,
            "Secure storage key is missing; existing ciphertext was not changed",
        )
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        /** 1: legacy whole document; 2: one value bound to its row key as associated data. */
        private fun formatVersion(associatedData: ByteArray?): Int = if (associatedData == null) 1 else 2
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()
        // Process-wide, so deleting a tool's key also drops the handle every session holds.
        private val keys = ConcurrentHashMap<String, SecretKey>()

        fun isAvailable(): Boolean = runCatching {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        }.isSuccess

        fun deleteForTool(toolId: String): Boolean = runCatching {
            keys.remove(aliasFor(toolId))
            KeyStore.getInstance(ANDROID_KEY_STORE).apply {
                load(null)
                if (containsAlias(aliasFor(toolId))) deleteEntry(aliasFor(toolId))
            }
        }.isSuccess

        private fun aliasFor(toolId: String): String = "toolbox.runtime.secure.v1.${toolId.sha256Hex()}"
    }
}

private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

/** Reads the pre-v2 single encrypted document for migration. Callers hold the secure namespace lock. */
internal class RuntimeSecureEnvelopeStorage(
    private val toolId: String,
    private val repository: ToolKvRepository,
) {
    suspend fun read(): String? {
        val encoded = repository.observe(toolId, ROOT_KEY).first()?.valueJson ?: return null
        val header = RuntimeValueJson.decodeObject(encoded) ?: unreadable()
        // Existing AES-GCM envelopes stay readable until the next successful write.
        if (header.keys == setOf("v", "iv", "ciphertext")) return encoded
        if (header.keys != setOf("format", "v", "chunks", "length") ||
            header["format"] != RpcValue.StringValue(FORMAT) || header["v"] != RpcValue.Number(1.0)) unreadable()
        val length = positiveInt(header["length"])
        val count = positiveInt(header["chunks"])
        if (count != (length - 1) / CHUNK_CHARS + 1) unreadable()
        return buildString {
            repeat(count) { index ->
                val chunk = repository.observe(toolId, "$CHUNK_PREFIX$index").first()?.valueJson ?: unreadable()
                val expected = if (index == count - 1) length - index * CHUNK_CHARS else CHUNK_CHARS
                if (chunk.length != expected) unreadable()
                append(chunk)
            }
        }
    }

    private fun positiveInt(value: RpcValue?): Int {
        val number = (value as? RpcValue.Number)?.value ?: unreadable()
        if (!number.isFinite() || number < 1 || number > Int.MAX_VALUE || number != number.toInt().toDouble()) unreadable()
        return number.toInt()
    }

    private fun unreadable(): Nothing = throw RuntimeHandlerException(
        RuntimeRpcErrorCode.INTERNAL_ERROR, "Secure storage data cannot be read",
    )

    private companion object {
        val ROOT_KEY = ToolStorageNamespace.Secure.documentKey
        val CHUNK_PREFIX = "$ROOT_KEY.chunk."
        const val FORMAT = "toolbox.secure.chunks"
        const val CHUNK_CHARS = 128 * 1024
    }
}

internal suspend fun clearRuntimeSecureStorage(
    toolId: String,
    repository: ToolKvRepository,
    deleteKey: () -> Boolean = { AndroidKeyStoreCipher.deleteForTool(toolId) },
): Boolean = withContext(Dispatchers.IO) {
    ToolRuntimeStorageLocks.mutexFor(toolId, ToolStorageNamespace.Secure).withLock {
        try {
            when (RuntimeSecureStore(toolId, repository, AndroidKeyStoreCipher(toolId)).clearRows()) {
                is DataResult.Success,
                is DataResult.Failure.NotFound,
                -> Unit

                is DataResult.Failure -> error("secure storage cleanup failed")
            }
            check(deleteKey())
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}

package io.toolbox.host.runtime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.widget.Toast
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.PermissionGrantRepository
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.host.BuildConfig
import io.toolbox.host.HostRuntimeM2HandlerFactory
import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.api.ToolBoxApiV1
import io.toolbox.tool.api.ToolBoxCapabilityId
import io.toolbox.tool.runtime.AndroidRuntimeSystemPermissionChecker
import io.toolbox.tool.runtime.DefaultRuntimeAuthorizationPolicy
import io.toolbox.tool.runtime.PreparedToolRuntime
import io.toolbox.tool.runtime.RpcValue
import io.toolbox.tool.runtime.RuntimeBasicDeviceInfo
import io.toolbox.tool.runtime.RuntimeBridgeConfiguration
import io.toolbox.tool.runtime.RuntimeBridgeProvider
import io.toolbox.tool.runtime.RuntimeClipboardWriteHandler
import io.toolbox.tool.runtime.RuntimeDeviceBasicHandler
import io.toolbox.tool.runtime.RuntimeGrantStateSource
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeHapticsHandler
import io.toolbox.tool.runtime.RuntimeM1Handlers
import io.toolbox.tool.runtime.RuntimeM2Handlers
import io.toolbox.tool.runtime.RuntimePolicyDecision
import io.toolbox.tool.runtime.RuntimeQuotaChecker
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import io.toolbox.tool.runtime.RuntimeSessionIdentity
import io.toolbox.tool.runtime.RuntimeStorageHandler
import io.toolbox.tool.runtime.RuntimeToastHandler
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal class HostRuntimeBridgeProvider(
    context: Context,
    repositories: CoreDataRepositories,
    private val m2HandlerFactory: HostRuntimeM2HandlerFactory,
    private val hostVersion: String = BuildConfig.VERSION_NAME,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : RuntimeBridgeProvider {
    private val applicationContext = context.applicationContext
    private val grantState = RepositoryRuntimeGrantStateSource(repositories.catalog, repositories.grants)
    private val systemPermissions = AndroidRuntimeSystemPermissionChecker(applicationContext)
    private val quota = HostRuntimeQuotaChecker
    private val keyValues = repositories.keyValues

    override fun create(runtime: PreparedToolRuntime): RuntimeBridgeConfiguration {
        val authorization = DefaultRuntimeAuthorizationPolicy(
            state = grantState,
            systemPermissions = systemPermissions,
            quota = quota,
            clockMillis = SystemClock::elapsedRealtime,
        )
        return RuntimeBridgeConfiguration(
            authorization = authorization,
            handlers = createM1Handlers(runtime.toolId),
            m2Handlers = m2HandlerFactory.createHandlers(runtime),
            m3Handlers = ForegroundCapabilityBroker.activeHandlers(
                toolId = runtime.toolId,
                toolName = runtime.installedManifest.name,
            ),
            hostVersion = hostVersion,
            generation = "${runtime.toolId}:${runtime.versionCode}:${UUID.randomUUID()}",
        )
    }

    private fun createM1Handlers(toolId: String): RuntimeM1Handlers = RuntimeM1Handlers(
        toast = AndroidToastHandler(applicationContext),
        storage = JsonToolKvStorageHandler(
            toolId = toolId,
            repository = keyValues,
            namespace = ToolStorageNamespace.Standard,
            nowMillis = nowMillis,
        ),
        secureStorage = AndroidKeyStoreCipher.isAvailable().takeIf { it }?.let {
            JsonToolKvStorageHandler(
                toolId = toolId,
                repository = keyValues,
                namespace = ToolStorageNamespace.Secure,
                nowMillis = nowMillis,
                cipher = AndroidKeyStoreCipher(toolId),
            )
        },
        deviceBasic = AndroidBasicDeviceHandler(applicationContext),
        haptics = AndroidHapticsHandler(applicationContext),
        clipboardWrite = AndroidClipboardWriteHandler(applicationContext),
    )
}

internal class RepositoryRuntimeGrantStateSource(
    private val catalog: CatalogRepository,
    private val grants: PermissionGrantRepository,
) : RuntimeGrantStateSource {
    override suspend fun currentVersionCode(toolId: String): Int? =
        catalog.observeTool(toolId).first()?.currentVersion?.versionCode

    override suspend fun isGranted(toolId: String, capability: ToolBoxCapabilityId): Boolean =
        grants.observeGrants(toolId)
            .first()
            .firstOrNull { it.capability == ToolBoxApiV1.capability(capability).wireName }
            ?.granted == true
}

internal suspend fun clearRuntimeSecureStorage(
    toolId: String,
    repository: ToolKvRepository,
): Boolean = withContext(Dispatchers.IO) {
    try {
        when (repository.remove(toolId, ToolStorageNamespace.Secure.documentKey)) {
            is DataResult.Success,
            is DataResult.Failure.NotFound,
            -> Unit

            is DataResult.Failure -> error("secure storage cleanup failed")
        }
        check(AndroidKeyStoreCipher.deleteForTool(toolId))
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

private object HostRuntimeQuotaChecker : RuntimeQuotaChecker {
    override suspend fun admit(
        identity: RuntimeSessionIdentity,
        method: MethodDescriptor,
        encodedBytes: Int,
    ): RuntimePolicyDecision = if (encodedBytes <= MAX_RPC_BYTES) {
        RuntimePolicyDecision.Allowed
    } else {
        RuntimePolicyDecision.Denied(RuntimeRpcErrorCode.QUOTA_EXCEEDED, "ToolBox request is too large")
    }

    private const val MAX_RPC_BYTES = 256 * 1024
}

private class AndroidToastHandler(
    private val context: Context,
) : RuntimeToastHandler {
    override suspend fun show(message: String) {
        withContext(Dispatchers.Main.immediate) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}

private class AndroidBasicDeviceHandler(
    private val context: Context,
) : RuntimeDeviceBasicHandler {
    override suspend fun getBasicInfo(): RuntimeBasicDeviceInfo {
        val configuration = context.resources.configuration
        val screenWidth = configuration.smallestScreenWidthDp
        val screenClass = when {
            screenWidth >= 840 -> "expanded"
            screenWidth >= 600 -> "medium"
            else -> "compact"
        }
        return RuntimeBasicDeviceInfo(
            apiLevel = Build.VERSION.SDK_INT,
            locale = configuration.locales[0].toLanguageTag(),
            timeZone = TimeZone.getDefault().id,
            screenClass = screenClass,
        )
    }
}

private class AndroidHapticsHandler(
    private val context: Context,
) : RuntimeHapticsHandler {
    override suspend fun perform(effect: String) {
        val effectId = when (effect) {
            "click" -> VibrationEffect.EFFECT_CLICK
            "confirm" -> VibrationEffect.EFFECT_DOUBLE_CLICK
            "reject" -> VibrationEffect.EFFECT_HEAVY_CLICK
            else -> throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_REQUEST, "Unsupported haptic effect")
        }
        withContext(Dispatchers.Main.immediate) {
            context.getSystemService(VibratorManager::class.java)
                ?.defaultVibrator
                ?.vibrate(VibrationEffect.createPredefined(effectId))
                ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Haptics are unavailable")
        }
    }
}

private class AndroidClipboardWriteHandler(
    private val context: Context,
) : RuntimeClipboardWriteHandler {
    override suspend fun writeText(text: String) {
        withContext(Dispatchers.Main.immediate) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
                ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Clipboard is unavailable")
            clipboard.setPrimaryClip(ClipData.newPlainText("ToolBox", text))
        }
    }
}

private enum class ToolStorageNamespace(val documentKey: String) {
    Standard("toolbox.runtime.v1.standard.values"),
    Secure("toolbox.runtime.v1.secure.values"),
}

private class JsonToolKvStorageHandler(
    private val toolId: String,
    private val repository: ToolKvRepository,
    private val namespace: ToolStorageNamespace,
    private val nowMillis: () -> Long,
    private val cipher: AndroidKeyStoreCipher? = null,
) : RuntimeStorageHandler {
    private val mutex = ToolRuntimeStorageLocks.mutexFor(toolId, namespace)

    override suspend fun get(key: String): RpcValue? = mutex.withLock {
        validateLogicalKey(key)
        loadDocument()[key]
    }

    override suspend fun set(key: String, value: RpcValue) = mutex.withLock {
        validateLogicalKey(key)
        val document = loadDocument()
        document[key] = value
        saveDocument(document)
    }

    override suspend fun remove(key: String) = mutex.withLock {
        validateLogicalKey(key)
        val document = loadDocument()
        if (document.remove(key) != null) saveDocument(document)
    }

    override suspend fun keys(): List<String> = mutex.withLock { loadDocument().keys.toList() }

    override suspend fun clear() = mutex.withLock {
        deletePhysical(namespace.documentKey)
    }

    private suspend fun loadDocument(): LinkedHashMap<String, RpcValue> {
        val encoded = repository.observe(toolId, namespace.documentKey).first()?.valueJson ?: return linkedMapOf()
        val json = cipher?.decrypt(encoded) ?: encoded
        val document = RuntimeValueJson.decodeObject(json)
            ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.INTERNAL_ERROR, "Stored values cannot be read")
        if (document.keys.any { !isValidLogicalKey(it) }) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.INTERNAL_ERROR, "Stored values are invalid")
        }
        return LinkedHashMap(document)
    }

    private suspend fun saveDocument(document: Map<String, RpcValue>) {
        if (document.keys.any { !isValidLogicalKey(it) }) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.INTERNAL_ERROR, "Storage index is invalid")
        }
        val json = RuntimeValueJson.encodeObject(document)
        write(namespace.documentKey, cipher?.encrypt(json) ?: json)
    }

    private suspend fun write(key: String, valueJson: String) {
        when (val result = repository.put(toolId, key, valueJson, nowMillis())) {
            is DataResult.Success -> Unit
            is DataResult.Failure.QuotaExceeded -> throw RuntimeHandlerException(
                RuntimeRpcErrorCode.QUOTA_EXCEEDED,
                "Tool storage quota exceeded",
            )

            is DataResult.Failure.NotFound -> throw RuntimeHandlerException(
                RuntimeRpcErrorCode.NOT_FOUND,
                "Tool was removed",
            )

            is DataResult.Failure -> throw RuntimeHandlerException(
                RuntimeRpcErrorCode.INTERNAL_ERROR,
                "Tool storage is unavailable",
            )
        }
    }

    private suspend fun deletePhysical(key: String) {
        when (val result = repository.remove(toolId, key)) {
            is DataResult.Success,
            is DataResult.Failure.NotFound,
            -> Unit

            is DataResult.Failure -> throw RuntimeHandlerException(
                RuntimeRpcErrorCode.INTERNAL_ERROR,
                "Tool storage is unavailable",
            )
        }
    }

    private fun validateLogicalKey(key: String) {
        if (!isValidLogicalKey(key)) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_REQUEST, "Invalid storage key")
        }
    }

    private fun isValidLogicalKey(key: String): Boolean =
        key.isNotBlank() && key.length <= MAX_LOGICAL_KEY_CHARS && key.none(Char::isISOControl)

    private companion object {
        const val MAX_LOGICAL_KEY_CHARS = 128
    }
}

private object ToolRuntimeStorageLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun mutexFor(toolId: String, namespace: ToolStorageNamespace): Mutex =
        locks.getOrPut("${namespace.name}:$toolId") { Mutex() }
}

private class AndroidKeyStoreCipher(
    private val toolId: String,
) {
    private val alias = aliasFor(toolId)

    fun encrypt(plaintext: String): String = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        JSONObject()
            .put("v", FORMAT_VERSION)
            .put("iv", encoder.encodeToString(cipher.iv))
            .put("ciphertext", encoder.encodeToString(ciphertext))
            .toString()
    } catch (failure: RuntimeHandlerException) {
        throw failure
    } catch (_: Exception) {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Secure storage is unavailable")
    }

    fun decrypt(encoded: String): String = try {
        val value = JSONTokener(encoded).nextValue() as? JSONObject
            ?: throw IllegalArgumentException("ciphertext")
        require(value.optInt("v", -1) == FORMAT_VERSION)
        val iv = decoder.decode(value.getString("iv"))
        val ciphertext = decoder.decode(value.getString("ciphertext"))
        require(iv.size == GCM_IV_BYTES && ciphertext.isNotEmpty())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    } catch (failure: RuntimeHandlerException) {
        throw failure
    } catch (_: Exception) {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.INTERNAL_ERROR, "Secure storage data cannot be read")
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
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
        private const val FORMAT_VERSION = 1
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        fun isAvailable(): Boolean = runCatching {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        }.isSuccess

        fun deleteForTool(toolId: String): Boolean = runCatching {
            KeyStore.getInstance(ANDROID_KEY_STORE).apply {
                load(null)
                if (containsAlias(aliasFor(toolId))) deleteEntry(aliasFor(toolId))
            }
        }.isSuccess

        private fun aliasFor(toolId: String): String = "toolbox.runtime.secure.v1.${toolId.sha256Hex()}"
    }
}

private object RuntimeValueJson {
    fun encode(value: RpcValue): String = toJson(value).toString()

    fun decode(encoded: String): RpcValue? = runCatching {
        fromJson(JSONTokener(encoded).nextValue())
    }.getOrNull()

    fun encodeObject(values: Map<String, RpcValue>): String = toJson(RpcValue.ObjectValue(values)).toString()

    fun decodeObject(encoded: String): Map<String, RpcValue>? =
        (decode(encoded) as? RpcValue.ObjectValue)?.value

    private fun toJson(value: RpcValue): Any = when (value) {
        RpcValue.Null -> JSONObject.NULL
        is RpcValue.Bool -> value.value
        is RpcValue.Number -> value.value.also { require(it.isFinite()) }
        is RpcValue.StringValue -> value.value
        is RpcValue.ArrayValue -> JSONArray().also { output -> value.value.forEach { output.put(toJson(it)) } }
        is RpcValue.ObjectValue -> JSONObject().also { output ->
            value.value.forEach { (key, child) -> output.put(key, toJson(child)) }
        }
    }

    private fun fromJson(value: Any?): RpcValue = when (value) {
        null, JSONObject.NULL -> RpcValue.Null
        is Boolean -> RpcValue.Bool(value)
        is Number -> RpcValue.Number(value.toDouble().also { require(it.isFinite()) })
        is String -> RpcValue.StringValue(value)
        is JSONArray -> RpcValue.ArrayValue((0 until value.length()).map { fromJson(value.get(it)) })
        is JSONObject -> RpcValue.ObjectValue(value.keys().asSequence().associateWith { fromJson(value.get(it)) })
        else -> throw IllegalArgumentException("json")
    }
}

private fun String.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

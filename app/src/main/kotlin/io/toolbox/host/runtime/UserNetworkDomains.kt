package io.toolbox.host.runtime

import io.toolbox.core.data.DataResult
import io.toolbox.core.data.ToolKvRepository
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeNetworkDomainHandler
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Physical host namespace: mini-app storage APIs only read their separate JSON documents. */
internal class UserNetworkDomainStore(private val repository: ToolKvRepository) {
    fun observe(toolId: String, versionCode: Int): Flow<List<String>> =
        repository.observe(toolId, KEY).map { decode(it?.valueJson, versionCode) }

    suspend fun list(toolId: String, versionCode: Int): List<String> = observe(toolId, versionCode).first()

    suspend fun authorize(toolId: String, versionCode: Int, domain: String, epoch: Long, validate: suspend () -> Unit) =
        state(toolId).mutex.withLock {
            if (state(toolId).epoch != epoch) denied("域名授权已变更，请重新确认。")
            val domains = list(toolId, versionCode)
            validate()
            if (domain !in domains) {
                if (domains.size >= 32) throw RuntimeHandlerException(RuntimeRpcErrorCode.QUOTA_EXCEEDED, "最多授权 32 个自定义域名。")
                save(toolId, versionCode, (domains + domain).sorted())
            }
        }

    suspend fun revoke(toolId: String, versionCode: Int, domain: String) = state(toolId).mutex.withLock {
        state(toolId).epoch++
        NetworkDomainInvalidation.cancel(toolId)
        val domains = list(toolId, versionCode)
        if (domain in domains) save(toolId, versionCode, domains.filterNot { it == domain })
    }

    suspend fun clear(toolId: String) = state(toolId).mutex.withLock {
        state(toolId).epoch++
        NetworkDomainInvalidation.cancel(toolId)
        when (repository.remove(toolId, KEY)) {
            is DataResult.Success, is DataResult.Failure.NotFound -> Unit
            is DataResult.Failure -> throw RuntimeHandlerException(RuntimeRpcErrorCode.INTERNAL_ERROR, "自定义域名未清除，请重试。")
        }
    }

    fun epoch(toolId: String): Long = state(toolId).epoch

    private suspend fun save(toolId: String, versionCode: Int, domains: List<String>) {
        val encoded = JsonArray(listOf(JsonPrimitive(versionCode)) + domains.map(::JsonPrimitive)).toString()
        if (repository.put(toolId, KEY, encoded, System.currentTimeMillis()) !is DataResult.Success) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.INTERNAL_ERROR, "自定义域名未保存，请重试。")
        }
    }

    private fun decode(encoded: String?, versionCode: Int): List<String> = try {
        if (encoded == null) emptyList() else {
            val values = Json.parseToJsonElement(encoded).jsonArray
            if (values.firstOrNull()?.jsonPrimitive?.content != versionCode.toString()) emptyList()
            else values.drop(1).map { normalizeUserNetworkDomain(it.jsonPrimitive.content) }.distinct().sorted()
        }
    } catch (_: Exception) { emptyList() }

    private class State { val mutex = Mutex(); @Volatile var epoch = 0L }
    private companion object {
        const val KEY = "toolbox.host.v1.network.domains"
        val states = ConcurrentHashMap<String, State>()
        fun state(toolId: String): State = states.getOrPut(toolId) { State() }
    }
}

internal fun normalizeUserNetworkDomain(value: String): String {
    val domain = value.lowercase(Locale.ROOT)
    val labels = domain.split('.')
    if (domain.length !in 3..253 || labels.size < 2 || labels.any {
            it.length !in 1..63 || !it.first().isLetterOrDigit() || !it.last().isLetterOrDigit() ||
                it.any { char -> char !in 'a'..'z' && char !in '0'..'9' && char != '-' }
        } || labels.last().none { it in 'a'..'z' } || domain.endsWith(".localhost") ||
        domain.endsWith(".local") || domain.endsWith(".internal") ||
        labels.all { it.toLongOrNull() != null || it.startsWith("0x") }) {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_REQUEST, "请输入完整 HTTPS 域名，不含协议、路径、端口、通配符或 IP 地址。")
    }
    return domain
}

/** Cancellation is process-local; durable per-call checks remain authoritative after restart. */
internal object NetworkDomainInvalidation {
    private val listeners = ConcurrentHashMap<String, ConcurrentHashMap<Any, () -> Unit>>()
    fun register(toolId: String, owner: Any, cancel: () -> Unit) { listeners.getOrPut(toolId) { ConcurrentHashMap() }[owner] = cancel }
    fun unregister(toolId: String, owner: Any) { listeners[toolId]?.remove(owner) }
    fun cancel(toolId: String) { listeners[toolId]?.values?.forEach { it() } }
}

internal class HostNetworkDomainHandler(
    private val toolId: String,
    private val toolName: String,
    private val versionCode: Int,
    private val store: UserNetworkDomainStore,
    private val foreground: () -> Boolean,
    private val confirm: suspend (String, String) -> Boolean,
    /** Checks latest verified manifest, installed version, and network grant; returns grant revision. */
    private val validate: suspend () -> Long,
) : RuntimeNetworkDomainHandler {
    private val active = AtomicBoolean(true)
    override fun isForegroundAvailable(): Boolean = active.get() && foreground()
    fun close() { active.set(false) }

    override suspend fun authorizeDomain(domain: String): Boolean {
        val normalized = normalizeUserNetworkDomain(domain)
        checkForeground()
        val epoch = store.epoch(toolId)
        val revision = validate()
        if (!confirm(toolName, normalized)) return false
        store.authorize(toolId, versionCode, normalized, epoch) {
            checkForeground()
            if (validate() != revision) denied("网络权限已变更，请重新确认。")
            checkForeground()
        }
        return true
    }

    override suspend fun listDomains(): List<String> {
        if (!active.get()) denied("工具会话已结束。")
        validate()
        return store.list(toolId, versionCode)
    }

    private fun checkForeground() {
        if (!isForegroundAvailable()) throw RuntimeHandlerException(RuntimeRpcErrorCode.SESSION_ENDED, "请在当前工具页面授权域名。")
    }
}

private fun denied(message: String): Nothing = throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, message)

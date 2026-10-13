package io.toolbox.host.runtime

import io.toolbox.core.data.CoreDataRepositories
import io.toolbox.core.data.DataResult
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Persists background sessions and alarms in host-reserved rows of each tool's key-value store. */
internal class RuntimeSessionStore(
    private val repositories: CoreDataRepositories,
    private val nowMillis: () -> Long,
) {
    suspend fun persistSessions(toolId: String, snapshot: List<StoredRuntimeSession>) {
        withContext(Dispatchers.IO) {
            val result = if (snapshot.isEmpty()) {
                repositories.keyValues.remove(toolId, RUNTIME_SESSIONS_KEY)
            } else {
                repositories.keyValues.put(
                    toolId,
                    RUNTIME_SESSIONS_KEY,
                    JSONArray().also { array -> snapshot.forEach { array.put(it.toJson()) } }.toString(),
                    nowMillis(),
                )
            }
            requirePersistence(result, allowMissingTool = snapshot.isEmpty())
        }
    }

    suspend fun persistAlarms(toolId: String, snapshot: List<StoredAlarm>) {
        withContext(Dispatchers.IO) {
            val result = if (snapshot.isEmpty()) {
                repositories.keyValues.remove(toolId, RUNTIME_ALARMS_KEY)
            } else {
                repositories.keyValues.put(
                    toolId,
                    RUNTIME_ALARMS_KEY,
                    JSONArray().also { array -> snapshot.forEach { array.put(it.toJson()) } }.toString(),
                    nowMillis(),
                )
            }
            requirePersistence(result, allowMissingTool = snapshot.isEmpty())
        }
    }

    private fun requirePersistence(result: DataResult<Unit>, allowMissingTool: Boolean) {
        when (result) {
            is DataResult.Success -> Unit
            is DataResult.Failure.NotFound -> if (!allowMissingTool) {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.NOT_FOUND, "工具已不存在")
            }
            is DataResult.Failure.QuotaExceeded -> throw RuntimeHandlerException(
                RuntimeRpcErrorCode.QUOTA_EXCEEDED,
                "工具存储空间不足，无法保存后台状态",
            )
            is DataResult.Failure -> throw RuntimeHandlerException(
                RuntimeRpcErrorCode.INTERNAL_ERROR,
                "后台状态未能保存",
            )
        }
    }

    suspend fun readPersistedState(): List<PersistedToolState> {
        return repositories.catalog.observeTools().first().mapNotNull { tool ->
            val toolId = tool.metadata.id
            val sessions = repositories.keyValues.observe(toolId, RUNTIME_SESSIONS_KEY).first()?.valueJson
                ?.let(::parseSessions).orEmpty()
            val alarms = repositories.keyValues.observe(toolId, RUNTIME_ALARMS_KEY).first()?.valueJson
                ?.let(::parseAlarms).orEmpty()
            if (sessions.isEmpty() && alarms.isEmpty()) null else PersistedToolState(
                toolId = toolId,
                toolName = tool.metadata.name,
                versionCode = tool.currentVersion.versionCode,
                sessions = sessions,
                alarms = alarms,
            )
        }
    }

    suspend fun loadAlarms(toolId: String, versionCode: Int): List<StoredAlarm> =
        withContext(Dispatchers.IO) {
            val installed = repositories.catalog.observeTool(toolId).first() ?: return@withContext emptyList()
            if (installed.currentVersion.versionCode != versionCode) return@withContext emptyList()
            repositories.keyValues.observe(toolId, RUNTIME_ALARMS_KEY).first()?.valueJson
                ?.let(::parseAlarms)
                .orEmpty()
        }

    private fun parseSessions(value: String): List<StoredRuntimeSession> = runCatching {
        val array = JSONArray(value)
        List(array.length()) { index -> StoredRuntimeSession.fromJson(array.getJSONObject(index)) }
    }.getOrDefault(emptyList())

    private fun parseAlarms(value: String): List<StoredAlarm> = runCatching {
        val array = JSONArray(value)
        List(array.length()) { index -> StoredAlarm.fromJson(array.getJSONObject(index)) }
    }.getOrDefault(emptyList())

    private companion object {
        const val RUNTIME_SESSIONS_KEY = "__toolbox.host.runtime-sessions.v1"
        const val RUNTIME_ALARMS_KEY = "__toolbox.host.alarms.v1"
    }
}

package io.toolbox.tool.runtime

import io.toolbox.tool.api.ToolBoxCapabilityId
import java.util.Base64

data class RuntimeFileToken(
    val token: String,
    val name: String,
    val mimeType: String,
    val size: Long,
)

interface RuntimeFilesHandler {
    suspend fun open(mimeTypes: List<String>): RuntimeFileToken?
    suspend fun save(suggestedName: String, mimeType: String, content: ByteArray): RuntimeFileToken?
    suspend fun capabilityFor(token: String): ToolBoxCapabilityId
    suspend fun consume(token: String, maxBytes: Int): ByteArray
}

internal suspend fun RuntimeRpcDispatcher.invokeFiles(
    method: String,
    params: RpcValue.ObjectValue,
    requestId: String,
): RpcValue = when (method) {
    "files.open" -> requireHandler(systemHandlers.files)
        .open(params.optionalMimeTypes())
        ?.toRpcValue()
        ?: RpcValue.Null
    "files.save" -> {
        params.requireOnly("suggestedName", "mimeType", "content")
        requireHandler(systemHandlers.files).save(
            suggestedName = params.requiredFileName(),
            mimeType = params.requiredMimeType("mimeType"),
            content = params.requiredBytes("content", rpcMaxResponseBytes),
        )?.toRpcValue() ?: RpcValue.Null
    }
    "files.read" -> {
        params.requireOnly("token")
        val content = requireHandler(systemHandlers.files).consume(
            params.requiredIdentifier("token"),
            runtimeFileReadRawBudget(requestId, rpcMaxResponseBytes),
        )
        require(content.size <= runtimeFileReadRawBudget(requestId, rpcMaxResponseBytes))
        RpcValue.ObjectValue(
            mapOf("base64" to RpcValue.StringValue(Base64.getEncoder().encodeToString(content))),
        )
    }
    else -> noNativeHandler()
}

internal fun RuntimeFileToken.toRpcValue(): RpcValue.ObjectValue {
    requireIdentifier(token)
    require(name.isNotBlank() && name.none(Char::isISOControl))
    requireMimeType(mimeType)
    require(size in 0..MAX_SAFE_INTEGER)
    return RpcValue.ObjectValue(
        mapOf(
            "token" to RpcValue.StringValue(token),
            "name" to RpcValue.StringValue(name),
            "mimeType" to RpcValue.StringValue(mimeType),
            "size" to RpcValue.Number(size.toDouble()),
        ),
    )
}

private fun RpcValue.ObjectValue.requiredFileName(): String {
    val name = requiredString("suggestedName")
    require(name == name.trim() && name !in setOf(".", ".."))
    require(name.none { it == '/' || it == '\\' || it.isISOControl() })
    return name
}

private fun RpcValue.ObjectValue.requiredMimeType(name: String): String =
    requiredString(name).also(::requireMimeType)

private fun RpcValue.ObjectValue.optionalMimeTypes(): List<String> {
    requireOnly("mimeTypes")
    val raw = value["mimeTypes"] ?: return emptyList()
    val types = (raw as? RpcValue.ArrayValue)?.value ?: throw IllegalArgumentException("mimeTypes")
    return types.map {
        ((it as? RpcValue.StringValue)?.value ?: throw IllegalArgumentException("mimeTypes"))
            .also(::requireMimeType)
    }.distinct()
}

private fun requireMimeType(value: String) {
    require(MIME_TYPE.matches(value))
}

internal fun runtimeFileReadRawBudget(requestId: String, maxResponseBytes: Int): Int {
    require(isSafeRuntimeRequestId(requestId))
    require(maxResponseBytes >= 0)
    val availableBase64Bytes = maxResponseBytes - FILE_READ_RESPONSE_FIXED_BYTES - requestId.length
    return availableBase64Bytes.coerceAtLeast(0) / 4 * 3
}

private const val FILE_READ_RESPONSE_FIXED_BYTES = 42

private val MIME_TYPE = Regex("^[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+*\\-]+$")

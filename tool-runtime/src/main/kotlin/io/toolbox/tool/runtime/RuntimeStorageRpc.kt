package io.toolbox.tool.runtime

interface RuntimeStorageHandler {
    suspend fun get(key: String): RpcValue?
    suspend fun set(key: String, value: RpcValue)
    suspend fun remove(key: String)
    suspend fun keys(): List<String>
    suspend fun clear()
}

data class RuntimeStorageSet(val key: String, val value: RpcValue)

data class RuntimeStorageMutation(
    val set: List<RuntimeStorageSet> = emptyList(),
    val remove: List<String> = emptyList(),
)

/** Ordinary storage only: secure storage deliberately has no batch interface. */
interface RuntimeBatchStorageHandler : RuntimeStorageHandler {
    suspend fun getMany(keys: List<String>): List<RpcValue?>
    suspend fun apply(mutation: RuntimeStorageMutation)
}

internal suspend fun RuntimeRpcDispatcher.invokeStorage(
    method: String,
    params: RpcValue.ObjectValue,
): RpcValue = when (method) {
    "storage.get" -> requireHandler(handlers.storage).get(params.requiredKey()) ?: RpcValue.Null
    "storage.getMany" -> {
        params.requireOnly("keys")
        val keys = params.storageKeys("keys")
        RpcValue.ArrayValue(requireHandler(handlers.storage as? RuntimeBatchStorageHandler).getMany(keys).map { it ?: RpcValue.Null })
    }
    "storage.apply" -> {
        val mutation = params.storageMutation()
        requireHandler(handlers.storage as? RuntimeBatchStorageHandler).apply(mutation)
        RpcValue.Null
    }
    "storage.set" -> {
        requireHandler(handlers.storage).set(params.requiredKey(), params.required("value"))
        RpcValue.Null
    }
    "storage.remove" -> {
        requireHandler(handlers.storage).remove(params.requiredKey())
        RpcValue.Null
    }
    "storage.keys" -> RpcValue.ArrayValue(
        requireHandler(handlers.storage).keys().map(RpcValue::StringValue),
    )
    "storage.clear" -> {
        requireHandler(handlers.storage).clear()
        RpcValue.Null
    }
    "storage.secure.get" -> requireHandler(handlers.secureStorage).get(params.requiredKey()) ?: RpcValue.Null
    "storage.secure.set" -> {
        requireHandler(handlers.secureStorage).set(params.requiredKey(), params.required("value"))
        RpcValue.Null
    }
    "storage.secure.remove" -> {
        requireHandler(handlers.secureStorage).remove(params.requiredKey())
        RpcValue.Null
    }
    else -> noNativeHandler()
}

private fun RpcValue.ObjectValue.storageKeys(name: String): List<String> {
    val entries = (required(name) as? RpcValue.ArrayValue)?.value ?: throw IllegalArgumentException(name)

    return entries.map { entry ->
        val key = (entry as? RpcValue.StringValue)?.value ?: throw IllegalArgumentException(name)
        require(key.isNotBlank() && key.none(Char::isISOControl))
        key
    }
}

private fun RpcValue.ObjectValue.storageMutation(): RuntimeStorageMutation {
    requireOnly("set", "remove")
    val set = value["set"]?.let { raw ->
        val entries = (raw as? RpcValue.ArrayValue)?.value ?: throw IllegalArgumentException("set")

        entries.map { entry ->
            val item = entry as? RpcValue.ObjectValue ?: throw IllegalArgumentException("set")
            item.requireOnly("key", "value")
            val key = item.requiredKey()
            require(key.isNotBlank())
            val value = item.required("value")
            requireStorageJson(value)
            RuntimeStorageSet(key, value)
        }
    }.orEmpty()
    val remove = if ("remove" in value) storageKeys("remove") else emptyList()
    val keys = set.map { it.key } + remove
    require(keys.distinct().size == keys.size)
    return RuntimeStorageMutation(set, remove)
}

private fun requireStorageJson(value: RpcValue) {
    val pending = ArrayDeque<RpcValue>()
    pending.addLast(value)
    while (pending.isNotEmpty()) {
        when (val item = pending.removeLast()) {
            is RpcValue.Number -> require(item.value.isFinite())
            is RpcValue.ArrayValue -> pending.addAll(item.value)
            is RpcValue.ObjectValue -> pending.addAll(item.value.values)
            else -> Unit
        }
    }
}

private fun RpcValue.ObjectValue.requiredKey(): String {
    val key = requiredString("key")
    require(!key.any(Char::isISOControl))
    return key
}

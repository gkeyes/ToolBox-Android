package io.toolbox.tool.runtime

import io.toolbox.tool.api.ToolBoxCapabilityId

fun interface RuntimeClipboardWriteHandler {
    suspend fun writeText(text: String)
}

fun interface RuntimeClipboardReadHandler {
    suspend fun readText(): String
}

fun interface RuntimeShareTextHandler {
    suspend fun shareText(text: String)
}

/** The native launcher must invoke beforeLaunch immediately before its foreground side effect. */
fun interface RuntimeBrowserOpenHandler {
    suspend fun open(url: String, beforeLaunch: suspend () -> Unit)
}

fun interface RuntimeSessionCleanupHandler {
    fun close()
}

fun interface RuntimeShortcutHandler {
    suspend fun pin(name: String?): Boolean
}

fun interface RuntimeCameraHandler {
    suspend fun capture(): RuntimeFileToken?
}

internal suspend fun RuntimeRpcDispatcher.invokeSystem(
    method: String,
    params: RpcValue.ObjectValue,
): RpcValue = when (method) {
    "clipboard.writeText" -> {
        requireHandler(handlers.clipboardWrite).writeText(params.requiredText("text", rpcMaxResponseBytes))
        RpcValue.Null
    }
    "clipboard.readText" -> {
        params.requireOnly()
        RpcValue.StringValue(requireHandler(systemHandlers.clipboardRead).readText())
    }
    "share.text" -> {
        params.requireOnly("text")
        requireHandler(systemHandlers.shareText).shareText(params.requiredText("text", rpcMaxResponseBytes))
        RpcValue.Null
    }
    "share.image.begin" -> {
        params.requireOnly("transferId", "mimeType", "length")
        val format = RuntimeImageShareFormat.entries.firstOrNull { it.mimeType == params.requiredString("mimeType", 30) }
            ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_MIME, "The image format is unsupported")
        val length = params.requiredLong("length", 1, MAX_SAFE_INTEGER)
        if (length > RUNTIME_IMAGE_SHARE_MAX_BYTES) {
            throw RuntimeHandlerException(RuntimeRpcErrorCode.QUOTA_EXCEEDED, "The image exceeds the share size limit")
        }
        requireHandler(systemHandlers.shareImage).begin(params.imageShareId(), format, length.toInt())
        RpcValue.Null
    }
    "share.image.write" -> {
        params.requireOnly("transferId", "offset", "content")
        val offset = params.requiredLong("offset", 0, RUNTIME_IMAGE_SHARE_MAX_BYTES.toLong()).toInt()
        val bytes = decodeRuntimeBase64Bytes(params.requiredObject("content"), IMAGE_SHARE_CHUNK_BYTES)
            ?: throw IllegalArgumentException("Invalid image chunk")
        require(bytes.size in 1..IMAGE_SHARE_CHUNK_BYTES)
        requireHandler(systemHandlers.shareImage).write(params.imageShareId(), offset, bytes)
        RpcValue.Null
    }
    "share.image.finish" -> {
        params.requireOnly("transferId")
        requireHandler(systemHandlers.shareImage).finish(params.imageShareId()) {
            if (!authorization.isCurrent(identity)) {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "The installed tool version changed")
            }
            if (!authorization.isGranted(identity, ToolBoxCapabilityId.SHARE)) {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "Sharing permission was disabled before launch")
            }
            if (!authorization.isCurrent(identity)) {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "The installed tool version changed")
            }
            foregroundInteractionGuard()
        }
        RpcValue.Null
    }
    "share.image.cancel" -> {
        params.requireOnly("transferId")
        requireHandler(systemHandlers.shareImage).cancel(params.imageShareId())
        RpcValue.Null
    }
    "browser.open" -> {
        params.requireOnly("url")
        val url = validateRuntimeBrowserUrl(params.requiredString("url", rpcMaxResponseBytes))
        requireHandler(systemHandlers.browserOpen).open(url) {
            if (!authorization.isCurrent(identity)) {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "The installed tool version changed")
            }
            if (!authorization.isGranted(identity, ToolBoxCapabilityId.BROWSER)) {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "Browser permission was disabled before launch")
            }
            if (!authorization.isCurrent(identity)) {
                throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "The installed tool version changed")
            }
            // Runs after suspending authorization reads, on the launcher's Main dispatcher.
            browserLaunchGuard()
        }
        RpcValue.Null
    }
    "browser.openEmbedded" -> {
        params.requireOnly("url", *EMBEDDED_BROWSER_BOUND_FIELDS)
        val url = normalizedYouTubeEmbedUrl(params.requiredString("url", rpcMaxResponseBytes))
        val bounds = params.embeddedBrowserBounds()
        val handler = requireHandler(systemHandlers.browserEmbedded)
        val id = handler.open(url, bounds) { authorizeEmbeddedBrowserPresentation() }
        require(isEmbeddedBrowserId(id))
        RpcValue.ObjectValue(mapOf("id" to RpcValue.StringValue(id)))
    }
    "browser.updateEmbedded" -> {
        params.requireOnly("id", *EMBEDDED_BROWSER_BOUND_FIELDS)
        val id = params.requiredIdentifier("id").also { require(isEmbeddedBrowserId(it)) }
        requireHandler(systemHandlers.browserEmbedded).update(id, params.embeddedBrowserBounds()) {
            authorizeEmbeddedBrowserPresentation()
        }
        RpcValue.Null
    }
    "browser.closeEmbedded" -> {
        params.requireOnly("id")
        val id = params.requiredIdentifier("id").also { require(isEmbeddedBrowserId(it)) }
        requireHandler(systemHandlers.browserEmbedded).close(id)
        RpcValue.Null
    }
    "shortcuts.pin" -> {
        params.requireOnly("name")
        RpcValue.Bool(requireHandler(systemHandlers.shortcuts).pin(params.optionalDisplayName("name")))
    }
    "camera.capture" -> {
        params.requireOnly()
        requireHandler(systemHandlers.camera).capture()?.toRpcValue() ?: RpcValue.Null
    }
    else -> noNativeHandler()
}

private fun RpcValue.ObjectValue.imageShareId(): String = requiredString("transferId", 38).also {
    require(Regex("image-[a-f0-9]{32}").matches(it))
}

/** Invoked on Main by the native surface, immediately before changing any visible player. */
private suspend fun RuntimeRpcDispatcher.authorizeEmbeddedBrowserPresentation() {
    if (!authorization.isCurrent(identity)) {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "The installed tool version changed")
    }
    if (!authorization.isGranted(identity, ToolBoxCapabilityId.BROWSER)) {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.PERMISSION_DENIED, "Browser permission was disabled before playback")
    }
    if (!authorization.isCurrent(identity)) {
        throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_SESSION, "The installed tool version changed")
    }
    browserLaunchGuard()
}

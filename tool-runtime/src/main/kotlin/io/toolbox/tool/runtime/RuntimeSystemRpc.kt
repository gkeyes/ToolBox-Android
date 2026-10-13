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

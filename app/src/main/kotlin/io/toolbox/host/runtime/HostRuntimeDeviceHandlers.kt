package io.toolbox.host.runtime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import android.widget.Toast
import io.toolbox.tool.api.MethodDescriptor
import io.toolbox.tool.runtime.RuntimeBasicDeviceInfo
import io.toolbox.tool.runtime.RuntimeClipboardWriteHandler
import io.toolbox.tool.runtime.RuntimeDeviceBasicHandler
import io.toolbox.tool.runtime.RuntimeHandlerException
import io.toolbox.tool.runtime.RuntimeHapticsHandler
import io.toolbox.tool.runtime.RuntimePolicyDecision
import io.toolbox.tool.runtime.RuntimeQuotaChecker
import io.toolbox.tool.runtime.RuntimeRpcErrorCode
import io.toolbox.tool.runtime.RuntimeSessionIdentity
import io.toolbox.tool.runtime.RuntimeToastHandler
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class HostRuntimeQuotaChecker : RuntimeQuotaChecker {
    override suspend fun admit(
        identity: RuntimeSessionIdentity,
        method: MethodDescriptor,
        encodedBytes: Int,
    ): RuntimePolicyDecision = if (encodedBytes.toLong() * Char.SIZE_BYTES <= io.toolbox.core.data.ResourceCapacity.availableHeapBytes()) {
        RuntimePolicyDecision.Allowed
    } else {
        RuntimePolicyDecision.Denied(RuntimeRpcErrorCode.QUOTA_EXCEEDED, "Insufficient memory for this request")
    }
}

internal class AndroidToastHandler(
    private val context: Context,
) : RuntimeToastHandler {
    override suspend fun show(message: String) {
        withContext(Dispatchers.Main.immediate) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}

internal class AndroidBasicDeviceHandler(
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

internal class AndroidHapticsHandler(
    private val context: Context,
    private val requireForegroundRuntime: () -> Unit,
) : RuntimeHapticsHandler {
    override suspend fun perform(effect: String) {
        val effectId = when (effect) {
            "click" -> VibrationEffect.EFFECT_CLICK
            "confirm" -> VibrationEffect.EFFECT_DOUBLE_CLICK
            "reject" -> VibrationEffect.EFFECT_HEAVY_CLICK
            else -> throw RuntimeHandlerException(RuntimeRpcErrorCode.INVALID_REQUEST, "Unsupported haptic effect")
        }
        performForegroundEffect(requireForegroundRuntime) {
            context.getSystemService(VibratorManager::class.java)
                ?.defaultVibrator
                ?.vibrate(VibrationEffect.createPredefined(effectId))
                ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Haptics are unavailable")
        }
    }
}

internal class AndroidClipboardWriteHandler(
    private val context: Context,
    private val requireForegroundRuntime: () -> Unit,
) : RuntimeClipboardWriteHandler {
    override suspend fun writeText(text: String) {
        performForegroundEffect(requireForegroundRuntime) {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
                ?: throw RuntimeHandlerException(RuntimeRpcErrorCode.UNSUPPORTED, "Clipboard is unavailable")
            clipboard.setPrimaryClip(ClipData.newPlainText("ToolBox", text))
        }
    }
}

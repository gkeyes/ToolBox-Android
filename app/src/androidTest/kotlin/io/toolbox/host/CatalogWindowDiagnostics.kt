package io.toolbox.host

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean

private val recordedCatalogWindowDiagnostics = AtomicBoolean(false)

/** Preserve system focus ownership on failure without changing window state or retrying input. */
internal fun recordCatalogWindowDiagnostics() {
    if (!recordedCatalogWindowDiagnostics.compareAndSet(false, true)) return
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    for (command in listOf("dumpsys window displays", "dumpsys input", "dumpsys activity activities")) {
        runCatching {
            val output = automation.executeShellCommand(command)
            ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().useLines { lines ->
                Log.e("CatalogWindowDiagnostics", command)
                lines.forEach { Log.e("CatalogWindowDiagnostics", it) }
            }
        }.onFailure { Log.e("CatalogWindowDiagnostics", "Could not collect $command", it) }
    }
}

package io.toolbox.host.runtime

import android.app.Application
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import io.toolbox.core.data.CoreDataFactory
import io.toolbox.host.HostImportResult
import io.toolbox.host.ProductionHostDependenciesFactory
import io.toolbox.tool.packagekit.PackageInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener

/** Real host session fixture, with a unique catalog/settings namespace and installed package. */
internal class UpgradeFixture {
    val application = ApplicationProvider.getApplicationContext<Application>()
    private val suffix = UUID.randomUUID().toString().replace("-", "")
    private val toolId = "io.toolbox.theme.t$suffix"
    private val databaseName = "theme-$suffix.db"
    val stores = CoreDataFactory.create(application, databaseName, "theme-$suffix")
    val dependencies = ProductionHostDependenciesFactory.create(application, stores)

    suspend fun install(version: Int) {
        val manifest = """{"schemaVersion":1,"id":"$toolId","name":"Theme fixture","version":"1.0.$version","versionCode":$version,"entry":"index.html","apiVersion":"1.0","minHostVersion":"0.3.3","permissions":[],"securityProfile":"strict"}"""
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                for ((path, text) in mapOf(
                    "manifest.json" to manifest,
                    "index.html" to "<!doctype html><html><head><meta name='color-scheme' content='light dark'></head><body>Theme fixture</body></html>",
                )) {
                    zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
            output.toByteArray()
        }
        val result = dependencies.packageOperations.importPackage(object : PackageInput {
            override val displayName = "theme-fixture.tbx"
            override fun openStream() = ByteArrayInputStream(bytes)
        })
        check(result is HostImportResult.Installed) { "Fixture install failed: $result" }
    }

    suspend fun page(): WebView {
        dependencies.runtimeSessions.openForeground(toolId)
        return withTimeout(20_000) {
            val state = dependencies.runtimeSessions.state(toolId).first {
                it is RuntimeUiState.Error || it is RuntimeUiState.Ready && it.mainEntryLoaded
            }
            check(state is RuntimeUiState.Ready) { "Fixture runtime failed: $state" }
            state.webView
        }
    }

    suspend fun close() {
        dependencies.runtimeSessions.releaseTool(toolId)
        dependencies.packageOperations.deleteTool(toolId)
        stores.close()
        application.deleteDatabase(databaseName)
    }
}

internal suspend fun evaluate(webView: WebView, script: String): Any? = withContext(Dispatchers.Main) {
    val result = CompletableDeferred<String>()
    webView.evaluateJavascript(script) { result.complete(it) }
    val value = JSONTokener(withTimeout(5_000) { result.await() }).nextValue()
    if (value == JSONObject.NULL) null else value
}

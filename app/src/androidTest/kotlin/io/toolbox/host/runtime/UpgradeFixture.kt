package io.toolbox.host.runtime

import android.app.Application
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import io.toolbox.core.data.CoreDataFactory
import io.toolbox.host.HostImportResult
import io.toolbox.host.HostPackageOperations
import io.toolbox.host.ProductionHostDependenciesFactory
import io.toolbox.tool.packagekit.PackageInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Isolated fixtures only. The page uses the production bridge and reports no credential contents. */
internal class UpgradeFixture(val suffix: String = UUID.randomUUID().toString().replace("-", "")) {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val toolId = "io.toolbox.upgrade.t$suffix"
    val databaseName = "upgrade-$suffix.db"
    val stores = CoreDataFactory.create(application, databaseName, "upgrade-$suffix")
    val dependencies = ProductionHostDependenciesFactory.create(application, stores)
    val packages = dependencies.packageOperations
    val envelope get() = RuntimeSecureEnvelopeStorage(toolId, stores.repositories.keyValues)
    val alias get() = "toolbox.runtime.secure.v1.${sha(toolId)}"
    fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    fun hasKey(): Boolean = keyStore().containsAlias(alias)

    suspend fun install(version: Int, marker: String = "$version", installer: HostPackageOperations = packages) {
        val result = installer.importPackage(bytes(version, marker))
        val done = if (result is HostImportResult.ConfirmationRequired) installer.confirmImport(result.confirmation.id) else result
        assertTrue(done.toString(), done is HostImportResult.Installed)
    }

    suspend fun page(): WebView = withTimeout(30_000) {
        dependencies.runtimeSessions.openForeground(toolId)
        val state = dependencies.runtimeSessions.state(toolId).first {
            it is RuntimeUiState.Ready && it.mainEntryLoaded || it is RuntimeUiState.Error
        }
        check(state is RuntimeUiState.Ready) { "Runtime preparation failed: $state" }
        awaitPage(state.webView) { it.optBoolean("ready") }
        state.webView
    }

    suspend fun login(page: WebView) {
        evaluate(page, "window.fixtureLogin();true")
        awaitPage(page) { it.optBoolean("loggedIn") }
    }

    suspend fun assertLogin(page: WebView, marker: String) {
        val state = awaitPage(page) { it.optBoolean("ready") }
        assertEquals(marker, state.getString("marker"))
        assertTrue("Persisted login was not restored", state.getBoolean("loggedIn"))
        assertEquals(7, state.getInt("userId"))
        assertEquals("keep-settings", state.getString("settings"))
    }

    suspend fun close(remove: Boolean = true) {
        dependencies.runtimeSessions.releaseTool(toolId)
        if (remove) packages.deleteTool(toolId)
        stores.close()
        if (remove) application.deleteDatabase(databaseName)
    }

    fun bytes(version: Int, marker: String = "$version"): PackageInput {
        val manifest = """{"schemaVersion":1,"id":"$toolId","name":"Upgrade fixture","version":"1.0.$version","versionCode":$version,"entry":"index.html","apiVersion":"1.0","minHostVersion":"0.3.3","permissions":[{"name":"storage","reason":"Fixture settings"},{"name":"storage.secure","reason":"Synthetic credentials"},{"name":"network","reason":"Keep disabled choice"}],"securityProfile":"strict"}"""
        val js = """
            (function () {
              const state = window.fixtureState = { ready: false, loggedIn: false, marker: ${JSONObject.quote(marker)} };
              function fail(error) { state.error = error.code || 'START_FAILED'; }
              async function restore() {
                const auth = await ToolBox.storage.secure.get('fixture.auth');
                state.settings = await ToolBox.storage.get('fixture.settings');
                state.loggedIn = !!auth && auth.serverUrl === 'https://fixture.invalid' && auth.userId === 7 && auth.token === 'synthetic-upgrade-token';
                state.userId = state.loggedIn ? auth.userId : null;
                state.ready = true;
              }
              window.fixtureLogin = async function () {
                try {
                  await ToolBox.storage.set('fixture.settings', 'keep-settings');
                  await ToolBox.storage.secure.set('fixture.auth', {serverUrl:'https://fixture.invalid',userId:7,token:'synthetic-upgrade-token'});
                  await restore();
                } catch (e) { fail(e); }
              };
              ToolBox.ready().then(restore).catch(fail);
            })();
        """.trimIndent()
        val data = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                for ((path, content) in mapOf("manifest.json" to manifest,
                    "index.html" to "<!doctype html><html><head><script src=\"app.js\" defer></script></head><body>Upgrade fixture</body></html>", "app.js" to js)) {
                    zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry()
                }
            }
            output.toByteArray()
        }
        return object : PackageInput {
            override val displayName = "fixture-$version.tbx"
            override fun openStream() = ByteArrayInputStream(data)
        }
    }
}

internal suspend fun evaluate(page: WebView, script: String): Any? = withContext(Dispatchers.Main.immediate) {
    suspendCancellableCoroutine { continuation ->
        page.evaluateJavascript(script) { result ->
            if (continuation.isActive) continuation.resume(JSONTokener(result).nextValue())
        }
    }
}

internal suspend fun awaitPage(page: WebView, predicate: (JSONObject) -> Boolean): JSONObject = withTimeout(30_000) {
    while (true) {
        val value = evaluate(page, "JSON.stringify(window.fixtureState || {})")
        val state = if (value is String) JSONObject(value) else JSONObject()
        check(!state.has("error")) { "Fixture page error: ${state.optString("error")}" }
        if (predicate(state)) return@withTimeout state
        delay(50)
    }
    @Suppress("UNREACHABLE_CODE") error("unreachable")
}

internal fun sha(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    .joinToString("") { "%02x".format(it.toInt() and 255) }

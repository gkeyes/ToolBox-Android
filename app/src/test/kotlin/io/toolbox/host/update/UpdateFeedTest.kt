package io.toolbox.host.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateFeedTest {
    private val stableJson = """
        {"tag_name":"v0.8.32","published_at":"2026-10-12T10:00:00Z","body":"notes","assets":[
          {"name":"toolbox-v0.8.32-release.apk","size":100,"browser_download_url":"https://example.test/app.apk","digest":"sha256:${"a".repeat(64)}"},
          {"name":"BUILD_RECEIPT.txt","size":10,"browser_download_url":"https://example.test/receipt"},
          {"name":"nextflux-v1.0.50.tbx","size":10,"browser_download_url":"https://example.test/nextflux.tbx","digest":null},
          {"name":"game-2048-v1.0.1.tbx","size":10,"browser_download_url":"https://example.test/2048.tbx"},
          {"name":"SHA256SUMS.txt","size":10,"browser_download_url":"https://example.test/sums"}
        ]}
    """.trimIndent()

    @Test
    fun parsesReleaseAssetsDigestsAndChecksumFallback() {
        val release = UpdateFeedParser.release(stableJson)
        assertEquals("v0.8.32", release.tag)
        assertEquals("a".repeat(64), release.assets.first().sha256)
        assertNull(release.assets.first { it.name.startsWith("nextflux") }.sha256)
        val filled = UpdateFeedParser.withChecksums(release, "${"b".repeat(64)}  nextflux-v1.0.50.tbx\n")
        assertEquals("b".repeat(64), filled.assets.first { it.name.startsWith("nextflux") }.sha256)
        assertEquals("a".repeat(64), filled.assets.first().sha256)
    }

    @Test
    fun readsAppVersionFromReceiptAndToolsFromFileNames() {
        val release = UpdateFeedParser.release(stableJson)
        val receipt = UpdateFeedParser.receipt("VERSION_NAME=0.8.32\nVERSION_CODE=65\nBUILD_COMMIT=abc\nWORKFLOW_RUN=123\n")
        val app = UpdateFeedParser.app(release, receipt)!!
        assertEquals(65, app.versionCode)
        assertEquals(123L, app.runId)
        assertEquals("0.8.32", UpdateFeedParser.app(release, null)!!.versionName)
        val tools = UpdateFeedParser.tools(release).associateBy { it.slug }
        assertEquals("1.0.50", tools.getValue("nextflux").version)
        assertTrue(tools.getValue("game-2048").matches("io.toolbox.game2048"))
        assertFalse(tools.getValue("nextflux").matches("io.toolbox.game2048"))
        assertFalse(tools.getValue("nextflux").matches("com.someone.nextflux"))
    }

    @Test
    fun matchesToolsImportedUnderTheOwnersOriginalId() {
        val asset = ReleaseAsset("zhuzhang-v1.2.0.tbx", "u", 0, null)
        val zhuzhang = ToolPackage("zhuzhang", "1.2.0", asset)
        assertTrue(zhuzhang.matches("io.gkeyes.zhuzhang"))
        assertFalse(zhuzhang.matches("io.toolbox.zhuzhang"))
        assertFalse(zhuzhang.matches("com.someone.zhuzhang"))
        // Other examples ship as io.toolbox.*, so an unrelated io.gkeyes tool must not be offered their update.
        assertFalse(ToolPackage("game-2048", "1.0.0", asset).matches("io.gkeyes.game2048"))
    }

    @Test
    fun testChannelKeepsTheNewerPackagePerTool() {
        val asset = ReleaseAsset("x", "u", 0, null)
        val merged = UpdateFeedParser.mergeTools(
            listOf(ToolPackage("nextflux", "1.0.50", asset), ToolPackage("rss-scout", "1.4.5", asset)),
            listOf(ToolPackage("nextflux", "1.0.51", asset), ToolPackage("rss-scout", "1.4.3", asset)),
        ).associate { it.slug to it.version }
        assertEquals(mapOf("nextflux" to "1.0.51", "rss-scout" to "1.4.5"), merged)
    }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(compareVersions("1.0.10", "1.0.9") > 0)
        assertTrue(compareVersions("1.1", "1.0.9") > 0)
        assertEquals(0, compareVersions("1.0", "1.0.0"))
        assertTrue(compareVersions("1.0.0-beta", "1.0.0") < 0)
    }

    @Test
    fun appStateIsDecidedByVersionAndRunOnlyTellsBuildsApart() {
        val asset = ReleaseAsset("toolbox-v0.8.32-release.apk", "u", 0, null)
        fun app(code: Int?, run: Long?, name: String = "0.8.32") = AppPackage(name, code, null, run, null, "", asset)
        val installed = InstalledBuild("0.8.32", 65, 200)
        assertEquals(AppUpdateState.NEWER, UpdateFeedParser.appState(app(66, 100, "0.9.0"), installed))
        assertEquals(AppUpdateState.DOWNGRADE, UpdateFeedParser.appState(app(64, 300, "0.8.31"), installed))
        // A later CI run of the same version is another build, not an update: the shown version would not change.
        assertEquals(AppUpdateState.OTHER_BUILD, UpdateFeedParser.appState(app(65, 300), installed))
        assertEquals(AppUpdateState.OTHER_BUILD, UpdateFeedParser.appState(app(65, 100), installed))
        assertEquals(AppUpdateState.CURRENT, UpdateFeedParser.appState(app(65, 200), installed))
        assertEquals(AppUpdateState.NEWER, UpdateFeedParser.appState(app(null, null, "0.8.33"), installed))
        assertEquals(AppUpdateState.DOWNGRADE, UpdateFeedParser.appState(app(null, null, "0.8.31"), installed))
        assertEquals(AppUpdateState.CURRENT, UpdateFeedParser.appState(app(null, null), InstalledBuild("0.8.32", 65, 0)))
    }

    @Test
    fun swappedTestAssetMustNotBeAnOlderVersionOrRun() {
        val asset = ReleaseAsset("toolbox-v0.9.0-dev.apk", "u", 0, null)
        fun app(code: Int?, run: Long?, name: String) = AppPackage(name, code, null, run, null, "", asset)
        val original = app(66, 200, "0.9.0")
        assertTrue(UpdateFeedParser.isNotOlder(app(67, 150, "0.9.1"), original))
        assertTrue(UpdateFeedParser.isNotOlder(app(66, 200, "0.9.0"), original))
        assertTrue(UpdateFeedParser.isNotOlder(app(66, 300, "0.9.0"), original))
        assertFalse(UpdateFeedParser.isNotOlder(app(66, 100, "0.9.0"), original))
        assertFalse(UpdateFeedParser.isNotOlder(app(65, 900, "0.8.32"), original))
        assertTrue(UpdateFeedParser.isNotOlder(app(null, null, "0.9.1"), original))
    }
}

package io.toolbox.host.backup

import io.toolbox.core.data.*
import io.toolbox.tool.packagekit.backup.BackupException
import io.toolbox.tool.packagekit.backup.BackupJson
import org.junit.Assert.*
import org.junit.Test

class CatalogLayoutBackupCodecTest {
    private val original = HostSettings(catalogLayout = CatalogLayout(favorites = listOf("a", "b"),
        groups = listOf(CatalogGroup("g", "工作", listOf("a"), false)), sort = CatalogSort.NAME))

    @Test fun newBackupRoundTripsLayoutIncludingOrderAndExpansion() {
        val json = BackupJson.encode(BackupDataCodec.settings(original))
        val decoded = BackupDataCodec.settings(HostSettings(), BackupJson.obj(BackupJson.parse(json.toByteArray())), mutableListOf())
        assertEquals(original, decoded)
    }

    @Test fun oldBackupKeepsLocalLayoutUntilFinalCatalogReconciliation() {
        val archive = BackupDataCodec.settings(HostSettings(theme = ThemeMode.DARK)) - "catalogLayout"
        val decoded = BackupDataCodec.settings(original, archive, mutableListOf())
        assertEquals(original.catalogLayout, decoded.catalogLayout)
        assertEquals(ThemeMode.DARK, decoded.theme)
        assertEquals(listOf("b"), decoded.catalogLayout.reconcile(setOf("b", "new")).favorites)
        assertEquals("g", decoded.catalogLayout.reconcile(setOf("b")).groups.single().id)
    }

    @Test fun malformedOrUnknownPresentLayoutFailsPreviewRatherThanResettingData() {
        listOf(null, mapOf("version" to 99), "invalid").forEach { invalid ->
            val error = runCatching { BackupDataCodec.settings(original, BackupDataCodec.settings(original) + ("catalogLayout" to invalid), mutableListOf()) }.exceptionOrNull()
            assertTrue(error is BackupException)
            assertEquals("CATALOG_LAYOUT", (error as BackupException).code)
        }
    }
}

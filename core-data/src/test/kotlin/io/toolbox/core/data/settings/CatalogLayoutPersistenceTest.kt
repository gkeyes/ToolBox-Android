package io.toolbox.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import io.toolbox.core.data.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class CatalogLayoutPersistenceTest {
    @Test fun editsAreAtomicDurableAndPreserveEmptyGroupsAndMultipleMembership() = runBlocking {
        val directory = Files.createTempDirectory("catalog-layout").toFile()
        val file = File(directory, "settings.preferences_pb")
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        val settings = DataStoreHostSettingsRepository(store)
        val installed = MutableStateFlow(listOf(entry("a"), entry("b")))
        val layout = CatalogLayoutRepository(settings, catalog(installed), DataMutationLock())
        try {
            assertEquals(CatalogLayout(), settings.settings.first().catalogLayout)
            assertTrue(layout.update { value, _ -> value.copy(groups = listOf(CatalogGroup("g1", "同名"), CatalogGroup("g2", "同名"))) } is DataResult.Success)
            coroutineScope {
                launch { layout.update { value, _ -> value.favorite("a", true) } }
                launch { layout.update { value, _ -> value.favorite("b", true) } }
                launch { layout.update { value, _ -> value.groupMembership("g1", "a", true) } }
                launch { layout.update { value, _ -> value.groupMembership("g2", "a", true) } }
            }
            layout.update { value, _ -> value.copy(sort = CatalogSort.NAME, groups = value.groups.map { if (it.id == "g1") it.copy(name = "工作", expanded = false) else it }) }
            assertEquals(setOf("a", "b"), settings.settings.first().catalogLayout.favorites.toSet())
            assertTrue(settings.settings.first().catalogLayout.groups.all { "a" in it.members })
            // Replacement changes the version only; presentation must remain untouched.
            installed.value = listOf(entry("a").copy(versionCode = 2), entry("b"))
            layout.reconcile()
            val persisted = settings.settings.first().catalogLayout
            job.cancelAndJoin()
            val reopenedJob = SupervisorJob()
            val reopened = PreferenceDataStoreFactory.create(scope = CoroutineScope(reopenedJob + Dispatchers.IO), produceFile = { file })
            try {
                val reader = DataStoreHostSettingsRepository(reopened)
                assertEquals(persisted, reader.settings.first().catalogLayout)
                assertFalse(persisted.groups[0].expanded)
                val updated = CatalogLayoutRepository(reader, catalog(installed), DataMutationLock())
                installed.value = listOf(entry("b"))
                updated.reconcile()
                val removed = reader.settings.first().catalogLayout
                assertEquals(listOf("b"), removed.favorites)
                assertEquals(listOf("g1", "g2"), removed.groups.map { it.id })
                assertTrue(removed.groups.all { it.members.isEmpty() })
            } finally { reopenedJob.cancelAndJoin() }
        } finally { job.cancelAndJoin(); directory.deleteRecursively() }
    }

    @Test fun restoreLockPreventsTransientEmptyCatalogFromPruningLayout() = runBlocking {
        val lock = DataMutationLock()
        val installed = MutableStateFlow(listOf(entry("a")))
        val saved = MutableStateFlow(HostSettings(catalogLayout = CatalogLayout(favorites = listOf("a"))))
        val settings = object : HostSettingsRepository {
            override val settings = saved.asStateFlow()
            override suspend fun update(transform: (HostSettings) -> HostSettings): DataResult<Unit> {
                saved.value = transform(saved.value); return DataResult.Success(Unit)
            }
        }
        val layout = CatalogLayoutRepository(settings, catalog(installed), lock)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val restore = launch(Dispatchers.Default) { lock.run { installed.value = emptyList(); entered.complete(Unit); release.await(); installed.value = listOf(entry("a")) } }
        entered.await()
        val queued = async(Dispatchers.Default) { layout.reconcile() }
        yield()
        assertFalse(queued.isCompleted)
        assertEquals(listOf("a"), saved.value.catalogLayout.favorites)
        release.complete(Unit); restore.join(); queued.await()
        assertEquals(listOf("a"), saved.value.catalogLayout.favorites)
    }

    @Test fun damagedOrUnknownLayoutIsAnErrorAndCannotBeOverwrittenByAppearanceUpdate() = runBlocking {
        val invalid = """{"version":2,"favorites":[],"groups":[],"sort":"INSTALLED"}"""
        val prefs = emptyPreferences().toMutablePreferences().apply { this[HostSettingsKeys.catalogLayout] = invalid }
        var writes = 0
        val store = object : DataStore<Preferences> {
            override val data = flowOf<Preferences>(prefs)
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val next = transform(prefs); writes++; return next
            }
        }
        val repository = DataStoreHostSettingsRepository(store)
        assertTrue(runCatching { repository.settings.first() }.isFailure)
        assertTrue(repository.update { it.copy(theme = ThemeMode.DARK) } is DataResult.Failure)
        assertEquals(0, writes)
        assertEquals(invalid, prefs[HostSettingsKeys.catalogLayout])
    }

    @Test fun ioWriteFailureRetainsLastLayoutAndReportsFailure() = runBlocking {
        val original = CatalogLayout(favorites = listOf("a"))
        val prefs = emptyPreferences().toMutablePreferences().apply { this[HostSettingsKeys.catalogLayout] = CatalogLayoutCodec.encode(original) }
        val store = object : DataStore<Preferences> {
            override val data = flowOf<Preferences>(prefs)
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences { throw IOException("write failed") }
        }
        val repository = DataStoreHostSettingsRepository(store)
        assertTrue(repository.update { it.copy(catalogLayout = CatalogLayout()) } is DataResult.Failure)
        assertEquals(original, repository.settings.first().catalogLayout)
    }

    private fun entry(id: String) = CatalogEntry(id, id, SecurityProfile.STRICT, 1, null, null, null, 1, "1.0.0", 1)
    private fun catalog(entries: StateFlow<List<CatalogEntry>>) = object : CatalogRepository {
        override fun observeCatalogProjection() = entries
        override fun observeTools(): Flow<List<InstalledTool>> = error("unused")
        override fun observeTool(toolId: String): Flow<InstalledTool?> = error("unused")
    }
}

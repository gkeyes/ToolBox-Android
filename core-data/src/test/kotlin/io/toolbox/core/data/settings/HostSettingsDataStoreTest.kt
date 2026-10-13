package io.toolbox.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import io.toolbox.core.data.CatalogLayout
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.HostSettings
import io.toolbox.core.data.ThemeMode
import io.toolbox.core.data.ThemeStyle
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostSettingsDataStoreTest {
    private val directory: File = Files.createTempDirectory("host-settings").toFile()
    private val file = File(directory, "settings.preferences_pb")

    @After
    fun deleteFiles() {
        directory.deleteRecursively()
    }

    @Test
    fun freshInstallUsesTheGivenDefaultAppearanceAndPersistsIt() = runBlocking {
        withStore(ThemeStyle.LIQUID_GLASS) { repository, preferences ->
            assertEquals(HostSettings(), repository.settings.first())
            assertEquals(ThemeStyle.LIQUID_GLASS.name, preferences()[HostSettingsKeys.themeStyle])
            assertEquals(false, preferences()[HostSettingsKeys.reduceTransparency])
        }
    }

    @Test
    fun upgradeFromBeforeAppearanceSettingsKeepsExistingValuesAndAddsTheUpgradeDefault() = runBlocking {
        raw { store -> store.edit { it[HostSettingsKeys.theme] = ThemeMode.DARK.name; it[HostSettingsKeys.backgroundEnabled] = false } }
        withStore(ThemeStyle.MIUIX) { repository, _ ->
            val settings = repository.settings.first()
            assertEquals(ThemeMode.DARK, settings.theme)
            assertEquals(false, settings.backgroundEnabled)
            assertEquals(ThemeStyle.MIUIX, settings.themeStyle)
            assertEquals(false, settings.reduceTransparency)
        }
    }

    @Test
    fun migrationNeverOverwritesAnAppearanceTheUserAlreadyChose() = runBlocking {
        raw { store ->
            store.edit {
                it[HostSettingsKeys.themeStyle] = ThemeStyle.LIQUID_GLASS.name
                it[HostSettingsKeys.reduceTransparency] = true
            }
        }
        withStore(ThemeStyle.MIUIX) { repository, _ ->
            val settings = repository.settings.first()
            assertEquals(ThemeStyle.LIQUID_GLASS, settings.themeStyle)
            assertEquals(true, settings.reduceTransparency)
        }
    }

    @Test
    fun unknownEnumNamesFromANewerBuildFallBackToDefaults() = runBlocking {
        raw { store ->
            store.edit {
                it[HostSettingsKeys.theme] = "SOLARIZED"
                it[HostSettingsKeys.themeStyle] = "NEON"
            }
        }
        withStore(ThemeStyle.MIUIX) { repository, _ ->
            val settings = repository.settings.first()
            assertEquals(ThemeMode.SYSTEM, settings.theme)
            assertEquals(ThemeStyle.LIQUID_GLASS, settings.themeStyle)
        }
    }

    @Test
    fun updateWritesEveryFieldAndSurvivesReopening() = runBlocking {
        val expected = HostSettings(
            theme = ThemeMode.MONET_DARK,
            backgroundEnabled = false,
            themeStyle = ThemeStyle.MIUIX,
            reduceTransparency = true,
            catalogLayout = CatalogLayout(favorites = listOf("a", "b")),
        )
        withStore(ThemeStyle.LIQUID_GLASS) { repository, _ ->
            assertTrue(repository.update { expected } is DataResult.Success)
            assertEquals(expected, repository.settings.first())
        }
        withStore(ThemeStyle.LIQUID_GLASS) { repository, _ ->
            assertEquals(expected, repository.settings.first())
        }
    }

    @Test
    fun updateTransformsTheCurrentValueSoSuccessiveEditsCompose() = runBlocking {
        withStore(ThemeStyle.LIQUID_GLASS) { repository, _ ->
            repository.update { it.copy(theme = ThemeMode.LIGHT) }
            repository.update { it.copy(reduceTransparency = true) }
            val settings = repository.settings.first()
            assertEquals(ThemeMode.LIGHT, settings.theme)
            assertEquals(true, settings.reduceTransparency)
        }
    }

    /** Opens the production store (with its migration); only one store may own the file at a time. */
    private suspend fun withStore(
        defaultThemeStyle: ThemeStyle,
        block: suspend (DataStoreHostSettingsRepository, suspend () -> Preferences) -> Unit,
    ) {
        val job = SupervisorJob()
        val store = createHostSettingsDataStore(file, CoroutineScope(job + Dispatchers.IO), defaultThemeStyle)
        try {
            block(DataStoreHostSettingsRepository(store), { store.data.first() })
        } finally {
            job.cancelAndJoin()
        }
    }

    /** Writes the file the way an older build would have, without the appearance migration. */
    private suspend fun raw(block: suspend (DataStore<Preferences>) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        try {
            block(store)
        } finally {
            job.cancelAndJoin()
        }
    }
}

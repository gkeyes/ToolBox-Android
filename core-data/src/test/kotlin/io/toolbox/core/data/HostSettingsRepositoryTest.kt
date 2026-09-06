package io.toolbox.core.data

import io.toolbox.core.data.memory.InMemoryHostSettingsRepository
import io.toolbox.core.data.settings.DataStoreHostSettingsRepository
import io.toolbox.core.data.settings.createHostSettingsDataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HostSettingsRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun appearanceAndBackgroundSettingsRoundTrip() = runBlocking {
        val desired = HostSettings(
            theme = ThemeMode.DARK,
            backgroundEnabled = false,
            themeStyle = ThemeStyle.MIUIX,
            reduceTransparency = true,
        )
        val memory = InMemoryHostSettingsRepository()
        assertEquals(DataResult.Success(Unit), memory.update { desired })
        assertEquals(desired, memory.settings.first())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = createHostSettingsDataStore(
            File(temporaryFolder.root, "host-settings.preferences_pb"),
            scope,
        )
        val persistent = DataStoreHostSettingsRepository(dataStore)
        assertEquals(DataResult.Success(Unit), persistent.update { desired })
        assertEquals(desired, persistent.settings.first())
        val keys = dataStore.data.first().asMap().keys.map { it.name }.toSet()
        assertEquals(setOf("theme", "background_enabled", "theme_style", "reduce_transparency"), keys)
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test
    fun newInstallDefaultsToLiquidGlass() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = createHostSettingsDataStore(
            File(temporaryFolder.root, "fresh-host-settings.preferences_pb"),
            scope,
            defaultThemeStyle = ThemeStyle.LIQUID_GLASS,
        )
        val settings = DataStoreHostSettingsRepository(dataStore).settings.first()
        assertEquals(HostSettings(), settings)
        assertEquals(
            setOf("theme_style", "reduce_transparency"),
            dataStore.data.first().asMap().keys.map { it.name }.toSet(),
        )
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test
    fun legacyInstallGetsMiuixWithoutResettingExistingSettings() = runBlocking {
        val file = File(temporaryFolder.root, "legacy-host-settings.preferences_pb")
        val legacyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val legacyStore = PreferenceDataStoreFactory.create(scope = legacyScope, produceFile = { file })
        legacyStore.edit { preferences ->
            preferences[stringPreferencesKey("theme")] = ThemeMode.MONET_DARK.name
            preferences[booleanPreferencesKey("background_enabled")] = false
        }
        legacyScope.coroutineContext[Job]!!.cancelAndJoin()

        val migratedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val migratedStore = createHostSettingsDataStore(
            file,
            migratedScope,
            defaultThemeStyle = ThemeStyle.MIUIX,
        )
        assertEquals(
            HostSettings(
                theme = ThemeMode.MONET_DARK,
                backgroundEnabled = false,
                themeStyle = ThemeStyle.MIUIX,
                reduceTransparency = false,
            ),
            DataStoreHostSettingsRepository(migratedStore).settings.first(),
        )
        assertEquals(
            setOf("theme", "background_enabled", "theme_style", "reduce_transparency"),
            migratedStore.data.first().asMap().keys.map { it.name }.toSet(),
        )
        migratedScope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test
    fun explicitAppearanceSurvivesMigration() = runBlocking {
        val file = File(temporaryFolder.root, "explicit-host-settings.preferences_pb")
        val initialScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val initialStore = PreferenceDataStoreFactory.create(scope = initialScope, produceFile = { file })
        initialStore.edit { preferences ->
            preferences[stringPreferencesKey("theme_style")] = ThemeStyle.LIQUID_GLASS.name
            preferences[booleanPreferencesKey("reduce_transparency")] = true
        }
        initialScope.coroutineContext[Job]!!.cancelAndJoin()

        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reopened = DataStoreHostSettingsRepository(
            createHostSettingsDataStore(file, reopenedScope, defaultThemeStyle = ThemeStyle.MIUIX),
        )
        assertEquals(ThemeStyle.LIQUID_GLASS, reopened.settings.first().themeStyle)
        assertEquals(true, reopened.settings.first().reduceTransparency)
        reopenedScope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test
    fun corruptSettingsRecoverToFreshDefaults() = runBlocking {

        val corruptFile = File(temporaryFolder.root, "corrupt-host-settings.preferences_pb")
        val corruptBytes = byteArrayOf(0x80.toByte())
        corruptFile.writeBytes(corruptBytes)
        val corruptScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val recovered = DataStoreHostSettingsRepository(createHostSettingsDataStore(corruptFile, corruptScope))
        assertEquals(HostSettings(), recovered.settings.first())
        assertFalse(corruptFile.readBytes().contentEquals(corruptBytes))
        corruptScope.coroutineContext[Job]!!.cancelAndJoin()
    }
}

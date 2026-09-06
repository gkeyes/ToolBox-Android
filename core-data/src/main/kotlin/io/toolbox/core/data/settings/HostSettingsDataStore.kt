package io.toolbox.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataMigration
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.toolbox.core.data.CoreDataInitializationException
import io.toolbox.core.data.ThemeStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.io.IOException

internal fun createHostSettingsDataStore(
    file: File,
    scope: CoroutineScope,
    defaultThemeStyle: ThemeStyle = ThemeStyle.LIQUID_GLASS,
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    migrations = listOf(HostAppearanceMigration(defaultThemeStyle)),
    scope = scope,
    produceFile = { file },
)

private class HostAppearanceMigration(
    private val defaultThemeStyle: ThemeStyle,
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        HostSettingsKeys.themeStyle !in currentData ||
            HostSettingsKeys.reduceTransparency !in currentData

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            if (HostSettingsKeys.themeStyle !in this) {
                this[HostSettingsKeys.themeStyle] = defaultThemeStyle.name
            }
            if (HostSettingsKeys.reduceTransparency !in this) {
                this[HostSettingsKeys.reduceTransparency] = false
            }
        }

    override suspend fun cleanUp() = Unit
}

internal object ProcessLifetimeHostSettingsDataStores {
    private val stores = mutableMapOf<String, DataStore<Preferences>>()

    fun get(
        file: File,
        defaultThemeStyle: ThemeStyle,
    ): DataStore<Preferences> {
        val canonicalFile = try {
            file.canonicalFile
        } catch (error: IOException) {
            throw CoreDataInitializationException(
                CoreDataInitializationException.Reason.SETTINGS_PATH_UNAVAILABLE,
                error,
            )
        }
        return synchronized(stores) {
            stores.getOrPut(canonicalFile.path) {
                createHostSettingsDataStore(
                    file = canonicalFile,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    defaultThemeStyle = defaultThemeStyle,
                )
            }
        }
    }
}

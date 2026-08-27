package io.toolbox.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.toolbox.core.data.CoreDataInitializationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.io.IOException

internal fun createHostSettingsDataStore(
    file: File,
    scope: CoroutineScope,
): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    scope = scope,
    produceFile = { file },
)

internal object ProcessLifetimeHostSettingsDataStores {
    private val stores = mutableMapOf<String, DataStore<Preferences>>()

    fun get(file: File): DataStore<Preferences> {
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
                )
            }
        }
    }
}

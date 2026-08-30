package io.toolbox.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.HostSettings
import io.toolbox.core.data.HostSettingsRepository
import io.toolbox.core.data.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class DataStoreHostSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : HostSettingsRepository {
    override val settings: Flow<HostSettings> = dataStore.data.map(Preferences::toSettings)

    override suspend fun update(transform: (HostSettings) -> HostSettings): DataResult<Unit> = try {
        dataStore.edit { preferences ->
            val next = transform(preferences.toSettings())
            preferences[Keys.theme] = next.theme.name
            preferences[Keys.backgroundEnabled] = next.backgroundEnabled
        }
        DataResult.Success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure("updateHostSettings")
    }

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val backgroundEnabled = booleanPreferencesKey("background_enabled")
    }
}

private fun Preferences.toSettings() = HostSettings(
    theme = get(stringPreferencesKey("theme")).enumOrDefault(ThemeMode.SYSTEM),
    backgroundEnabled = get(booleanPreferencesKey("background_enabled")) ?: true,
)

private inline fun <reified T : Enum<T>> String?.enumOrDefault(default: T): T =
    this?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

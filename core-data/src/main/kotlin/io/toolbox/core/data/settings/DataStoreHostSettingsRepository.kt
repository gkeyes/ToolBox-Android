package io.toolbox.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.HostSettings
import io.toolbox.core.data.HostSettingsRepository
import io.toolbox.core.data.ThemeMode
import io.toolbox.core.data.ThemeStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class DataStoreHostSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : HostSettingsRepository {
    override val settings: Flow<HostSettings> = dataStore.data.map(Preferences::toSettings).flowOn(Dispatchers.IO)

    override suspend fun update(transform: (HostSettings) -> HostSettings): DataResult<Unit> = withContext(Dispatchers.IO) { try {
        dataStore.edit { preferences ->
            val next = transform(preferences.toSettings())
            preferences[HostSettingsKeys.theme] = next.theme.name
            preferences[HostSettingsKeys.backgroundEnabled] = next.backgroundEnabled
            preferences[HostSettingsKeys.themeStyle] = next.themeStyle.name
            preferences[HostSettingsKeys.reduceTransparency] = next.reduceTransparency
            preferences[HostSettingsKeys.catalogLayout] = CatalogLayoutCodec.encode(next.catalogLayout)
        }
        DataResult.Success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure("updateHostSettings")
    } }
}

private fun Preferences.toSettings() = HostSettings(
    theme = get(HostSettingsKeys.theme).enumOrDefault(ThemeMode.SYSTEM),
    backgroundEnabled = get(HostSettingsKeys.backgroundEnabled) ?: true,
    themeStyle = get(HostSettingsKeys.themeStyle).enumOrDefault(ThemeStyle.LIQUID_GLASS),
    reduceTransparency = get(HostSettingsKeys.reduceTransparency) ?: false,
    catalogLayout = get(HostSettingsKeys.catalogLayout)?.let(CatalogLayoutCodec::decode) ?: io.toolbox.core.data.CatalogLayout(),
)

private inline fun <reified T : Enum<T>> String?.enumOrDefault(default: T): T =
    this?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

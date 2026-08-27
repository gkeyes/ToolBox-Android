package io.toolbox.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.GlobalSecurityPolicy
import io.toolbox.core.data.HostPage
import io.toolbox.core.data.HostSettings
import io.toolbox.core.data.HostSettingsRepository
import io.toolbox.core.data.ThemeMode
import io.toolbox.core.data.validationError
import io.toolbox.core.data.withPersistedDefaults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class DataStoreHostSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : HostSettingsRepository {
    override val settings: Flow<HostSettings> = dataStore.data.map { preferences ->
        preferences.toSettings()
    }

    override suspend fun update(transform: (HostSettings) -> HostSettings): DataResult<Unit> = try {
        dataStore.edit { preferences ->
            val next = transform(preferences.toSettings())
            next.validationError()?.let { field -> throw InvalidHostSetting(field) }
            preferences[Keys.theme] = next.theme.name
            preferences[Keys.securityPolicy] = next.securityPolicy.name
            preferences[Keys.auditRetentionDays] = next.auditRetentionDays
            preferences[Keys.developerMode] = next.developerMode
            preferences[Keys.defaultStorageQuotaBytes] = next.defaultStorageQuotaBytes
            preferences[Keys.lastPage] = next.lastPage.name
        }
        DataResult.Success(Unit)
    } catch (invalid: InvalidHostSetting) {
        DataResult.Failure.InvalidInput(invalid.field)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DataResult.Failure.StorageFailure("updateHostSettings")
    }

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val securityPolicy = stringPreferencesKey("security_policy")
        val auditRetentionDays = intPreferencesKey("audit_retention_days")
        val developerMode = booleanPreferencesKey("developer_mode")
        val defaultStorageQuotaBytes = longPreferencesKey("default_storage_quota_bytes")
        val lastPage = stringPreferencesKey("last_page")
    }

    private fun Preferences.toSettings() = HostSettings(
        theme = get(Keys.theme).enumOrDefault(ThemeMode.SYSTEM),
        securityPolicy = get(Keys.securityPolicy).enumOrDefault(GlobalSecurityPolicy.STRICT),
        auditRetentionDays = get(Keys.auditRetentionDays) ?: 30,
        developerMode = get(Keys.developerMode) ?: false,
        defaultStorageQuotaBytes = get(Keys.defaultStorageQuotaBytes) ?: 2L * 1024L * 1024L,
        lastPage = get(Keys.lastPage).enumOrDefault(HostPage.HOME),
    ).withPersistedDefaults()
}

private class InvalidHostSetting(val field: String) : IllegalArgumentException(field)

private inline fun <reified T : Enum<T>> String?.enumOrDefault(default: T): T =
    this?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

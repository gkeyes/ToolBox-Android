package io.toolbox.core.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import io.toolbox.core.data.memory.InMemoryHostSettingsRepository
import io.toolbox.core.data.settings.DataStoreHostSettingsRepository
import io.toolbox.core.data.settings.createHostSettingsDataStore
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

class HostSettingsRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun invalidNumericUpdatesAreRejectedAndCorruptPersistenceDefaults() = runBlocking {
        val defaults = HostSettings()
        val memory = InMemoryHostSettingsRepository()

        assertEquals(
            DataResult.Failure.InvalidInput("auditRetentionDays"),
            memory.update { it.copy(auditRetentionDays = 0) },
        )
        assertEquals(
            DataResult.Failure.InvalidInput("defaultStorageQuotaBytes"),
            memory.update { it.copy(defaultStorageQuotaBytes = HostSettingsLimits.MAX_STORAGE_QUOTA_BYTES + 1) },
        )
        assertEquals(defaults, memory.settings.first())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = createHostSettingsDataStore(
            file = temporaryFolder.newFile("host-settings.preferences_pb"),
            scope = scope,
        )
        dataStore.edit { preferences ->
            preferences[intPreferencesKey("audit_retention_days")] = -10
            preferences[longPreferencesKey("default_storage_quota_bytes")] = Long.MAX_VALUE
        }
        val persistent = DataStoreHostSettingsRepository(dataStore)
        assertEquals(defaults, persistent.settings.first())
        assertEquals(
            DataResult.Failure.InvalidInput("auditRetentionDays"),
            persistent.update { it.copy(auditRetentionDays = HostSettingsLimits.MAX_AUDIT_RETENTION_DAYS + 1) },
        )
        assertEquals(
            DataResult.Failure.InvalidInput("defaultStorageQuotaBytes"),
            persistent.update { it.copy(defaultStorageQuotaBytes = 0) },
        )
        assertEquals(defaults, persistent.settings.first())
        scope.coroutineContext[Job]!!.cancelAndJoin()

        val corruptFile = temporaryFolder.newFile("corrupt-host-settings.preferences_pb")
        val corruptBytes = byteArrayOf(0x80.toByte())
        corruptFile.writeBytes(corruptBytes)
        val corruptScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val recovered = DataStoreHostSettingsRepository(
            createHostSettingsDataStore(corruptFile, corruptScope),
        )
        assertEquals(defaults, recovered.settings.first())
        assertFalse(corruptFile.readBytes().contentEquals(corruptBytes))
        corruptScope.coroutineContext[Job]!!.cancelAndJoin()
    }
}

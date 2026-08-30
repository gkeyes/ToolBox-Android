package io.toolbox.core.data

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
    fun onlyThemeAndBackgroundArePersisted() = runBlocking {
        val desired = HostSettings(ThemeMode.DARK, backgroundEnabled = false)
        val memory = InMemoryHostSettingsRepository()
        assertEquals(DataResult.Success(Unit), memory.update { desired })
        assertEquals(desired, memory.settings.first())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = createHostSettingsDataStore(
            temporaryFolder.newFile("host-settings.preferences_pb"),
            scope,
        )
        val persistent = DataStoreHostSettingsRepository(dataStore)
        assertEquals(DataResult.Success(Unit), persistent.update { desired })
        assertEquals(desired, persistent.settings.first())
        val keys = dataStore.data.first().asMap().keys.map { it.name }.toSet()
        assertEquals(setOf("theme", "background_enabled"), keys)
        scope.coroutineContext[Job]!!.cancelAndJoin()

        val corruptFile = temporaryFolder.newFile("corrupt-host-settings.preferences_pb")
        val corruptBytes = byteArrayOf(0x80.toByte())
        corruptFile.writeBytes(corruptBytes)
        val corruptScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val recovered = DataStoreHostSettingsRepository(createHostSettingsDataStore(corruptFile, corruptScope))
        assertEquals(HostSettings(), recovered.settings.first())
        assertFalse(corruptFile.readBytes().contentEquals(corruptBytes))
        corruptScope.coroutineContext[Job]!!.cancelAndJoin()
    }
}

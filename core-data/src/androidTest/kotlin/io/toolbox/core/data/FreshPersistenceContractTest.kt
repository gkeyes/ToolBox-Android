package io.toolbox.core.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.toolbox.core.data.db.RoomBackgroundTaskRepository
import io.toolbox.core.data.db.RoomCatalogLifecycleRepository
import io.toolbox.core.data.db.RoomCatalogRepository
import io.toolbox.core.data.db.RoomInstallTransactionRepository
import io.toolbox.core.data.db.RoomPermissionGrantRepository
import io.toolbox.core.data.db.RoomToolKvRepository
import io.toolbox.core.data.db.ToolBoxDatabase
import io.toolbox.core.data.settings.DataStoreHostSettingsRepository
import io.toolbox.core.data.settings.createHostSettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FreshPersistenceContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun resetStorage() {
        context.deleteDatabase(DATABASE_NAME)
        context.preferencesDataStoreFile(SETTINGS_NAME).delete()
    }

    @After
    fun cleanStorage() {
        context.deleteDatabase(DATABASE_NAME)
        context.preferencesDataStoreFile(SETTINGS_NAME).delete()
    }

    @Test
    fun freshSchemaAndSettingsReopenWithoutCompatibilityArtifacts() = runTest {
        var database = openDatabase()
        val installs = RoomInstallTransactionRepository(database)
        val lifecycle = RoomCatalogLifecycleRepository(database)
        val grants = RoomPermissionGrantRepository(database)
        val keyValues = RoomToolKvRepository(database)
        val tasks = RoomBackgroundTaskRepository(database)

        assertEquals(
            DataResult.Success(Unit),
            installs.begin(
                InstallTransaction("tx", TOOL_ID, 1, InstallTransactionState.PREPARING, 1, 1),
            ),
        )
        assertEquals(
            DataResult.Success(CommitInstallOutcome.Committed),
            lifecycle.commitInstall(attempt()),
        )
        assertEquals(
            DataResult.Success(Unit),
            grants.put(PermissionGrant(TOOL_ID, "clipboard.write", true, 2)),
        )
        assertEquals(DataResult.Success(Unit), keyValues.put(TOOL_ID, "key", "value", 3))
        assertEquals(DataResult.Success(Unit), tasks.create(task()))
        assertEquals(DataResult.Success(Unit), tasks.markRunning("task", 5, 1))
        val result = TaskRunResult("task", RunOutcome.SUCCEEDED, 6, "result", null, 1)
        assertEquals(
            DataResult.Success(Unit),
            tasks.finishRun("task", result, TaskState.COMPLETED, null),
        )
        database.close()

        database = openDatabase()
        assertEquals(1, RoomCatalogRepository(database).observeTools().first().size)
        assertEquals("value", RoomToolKvRepository(database).observe(TOOL_ID, "key").first()!!.valueJson)
        assertEquals(
            true,
            RoomPermissionGrantRepository(database).observeGrants(TOOL_ID).first()
                .single { it.capability == "clipboard.write" }.granted,
        )
        assertEquals(result, RoomBackgroundTaskRepository(database).observeResult("task").first())

        val expectedTables = setOf(
            "tools",
            "tool_versions",
            "permission_grants",
            "tool_kv",
            "install_transactions",
            "background_tasks",
            "task_results",
            "room_master_table",
            "android_metadata",
        )
        val cursor = database.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
        )
        val actualTables = buildSet {
            cursor.use {
                while (it.moveToNext()) add(it.getString(0))
            }
        }
        assertEquals(expectedTables, actualTables)
        assertFalse(actualTables.any { it.contains("audit") || it.contains("publisher") || it.contains("session") })
        database.close()

        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var settings = DataStoreHostSettingsRepository(
            createHostSettingsDataStore(context.preferencesDataStoreFile(SETTINGS_NAME), scope),
        )
        val desired = HostSettings(ThemeMode.DARK, backgroundEnabled = false)
        assertEquals(DataResult.Success(Unit), settings.update { desired })
        scope.coroutineContext[Job]!!.cancelAndJoin()

        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = createHostSettingsDataStore(context.preferencesDataStoreFile(SETTINGS_NAME), scope)
        settings = DataStoreHostSettingsRepository(store)
        assertEquals(desired, settings.settings.first())
        assertEquals(setOf("theme", "background_enabled"), store.data.first().asMap().keys.map { it.name }.toSet())
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }

    private fun openDatabase() = Room.databaseBuilder(context, ToolBoxDatabase::class.java, DATABASE_NAME).build()

    private fun attempt() = CatalogInstallAttempt(
        transactionId = "tx",
        metadata = ToolMetadata(TOOL_ID, "Persistence tool", SecurityProfile.STRICT, 1),
        version = ToolVersion(
            TOOL_ID,
            1,
            "1.0.0",
            BundleLocator("tools/persistence/current"),
            100,
            "sha256:persistence",
            1,
        ),
        initialGrants = listOf(PermissionGrant(TOOL_ID, "storage", true, 1)),
    )

    private fun task() = BackgroundTask(
        taskId = "task",
        toolId = TOOL_ID,
        versionCode = 1,
        key = "refresh",
        operation = BackgroundOperation.HTTP_GET,
        specJson = "{}",
        periodic = false,
        intervalMinutes = null,
        state = TaskState.QUEUED,
        createdAt = 4,
        updatedAt = 4,
        nextRunAt = null,
        runAttempt = 0,
    )

    private companion object {
        const val DATABASE_NAME = "fresh-persistence.db"
        const val SETTINGS_NAME = "fresh-persistence-settings"
        const val TOOL_ID = "io.toolbox.persistence"
    }
}

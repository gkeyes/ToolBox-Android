package io.toolbox.core.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.core.data.db.RoomCatalogRepository
import io.toolbox.core.data.db.RoomPermissionGrantRepository
import io.toolbox.core.data.db.RoomRuntimeSessionRepository
import io.toolbox.core.data.db.RoomToolKvRepository
import io.toolbox.core.data.db.ToolBoxDatabase
import io.toolbox.core.data.memory.InMemoryCoreData
import io.toolbox.core.data.settings.DataStoreHostSettingsRepository
import io.toolbox.core.data.settings.createHostSettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceContractTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ToolBoxDatabase::class.java,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun cleanBefore() {
        context.deleteDatabase(DATABASE_NAME)
        context.preferencesDataStoreFile(SETTINGS_NAME).delete()
    }

    @After
    fun cleanAfter() {
        context.deleteDatabase(DATABASE_NAME)
        context.preferencesDataStoreFile(SETTINGS_NAME).delete()
    }

    @Test
    fun freshV1CatalogAndSettingsPersistAcrossReopenedProductionAdapters() = runBlocking {
        val schemaDatabase = migrationHelper.createDatabase(DATABASE_NAME, 1)
        val tables = schemaDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%'",
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        schemaDatabase.close()
        assertEquals(
            setOf(
                "tools",
                "tool_versions",
                "permission_grants",
                "tool_kv",
                "publishers",
                "audit_logs",
                "runtime_sessions",
            ),
            tables,
        )

        val firstDatabase = openDatabase()
        val firstCatalog = RoomCatalogRepository(firstDatabase)
        assertTrue(firstCatalog.observeTools().first().isEmpty())
        assertTrue(firstCatalog.registerVersion(tool(), version()) is DataResult.Success)
        assertTrue(firstCatalog.activateVersion(TOOL_ID, VERSION_CODE) is DataResult.Success)

        val firstSettingsScope = settingsScope()
        val firstSettings = settingsRepository(firstSettingsScope)
        assertTrue(
            firstSettings.update {
                it.copy(
                    theme = ThemeMode.DARK,
                    securityPolicy = GlobalSecurityPolicy.BALANCED,
                    auditRetentionDays = 14,
                    developerMode = true,
                    defaultStorageQuotaBytes = 4_194_304,
                    lastPage = HostPage.TOOLS,
                )
            } is DataResult.Success,
        )
        assertEquals(ThemeMode.DARK, firstSettings.settings.first().theme)
        firstDatabase.close()
        firstSettingsScope.coroutineContext[Job]!!.cancelAndJoin()

        val reopenedDatabase = openDatabase()
        val reopenedCatalog = RoomCatalogRepository(reopenedDatabase)
        val persistedTool = reopenedCatalog.observeTool(TOOL_ID).first()
        assertEquals(VERSION_CODE, persistedTool?.activeVersionCode)
        assertEquals(LaunchState.STABLE, reopenedCatalog.observeVersions(TOOL_ID).first().single().launchState)

        val reopenedSettingsScope = settingsScope()
        val reopenedSettings = settingsRepository(reopenedSettingsScope).settings.first()
        assertEquals(
            HostSettings(
                theme = ThemeMode.DARK,
                securityPolicy = GlobalSecurityPolicy.BALANCED,
                auditRetentionDays = 14,
                developerMode = true,
                defaultStorageQuotaBytes = 4_194_304,
                lastPage = HostPage.TOOLS,
            ),
            reopenedSettings,
        )
        reopenedDatabase.close()
        reopenedSettingsScope.coroutineContext[Job]!!.cancelAndJoin()
    }

    @Test
    fun productionAdaptersEnforceRollbackOwnershipQuotaAndRuntimeParity() = runBlocking {
        val database = openDatabase()
        val catalog = RoomCatalogRepository(
            database,
            CatalogCommitHook { error("injected commit failure") },
        )
        assertTrue(catalog.registerVersion(tool(), version()) is DataResult.Success)

        assertEquals(
            DataResult.Failure.StorageFailure("activateVersion"),
            catalog.activateVersion(TOOL_ID, VERSION_CODE),
        )
        assertEquals(null, catalog.observeTool(TOOL_ID).first()?.activeVersionCode)
        assertEquals(LaunchState.PENDING, catalog.observeVersions(TOOL_ID).first().single().launchState)

        val keyValues = RoomToolKvRepository(database)
        val orphanKv = keyValues.put(ORPHAN_TOOL_ID, "key", "x", 1, 5)
        assertEquals(DataResult.Failure.NotFound("tool"), orphanKv)
        val kvResults = List(8) { index ->
            async(Dispatchers.Default) {
                keyValues.put(TOOL_ID, "key-$index", "x", index.toLong(), 5)
            }
        }.awaitAll()
        assertEquals(5, kvResults.count { it is DataResult.Success })
        assertEquals(3, kvResults.count { it is DataResult.Failure.QuotaExceeded })
        assertEquals(5L, keyValues.bytesUsed(TOOL_ID))

        val grants = RoomPermissionGrantRepository(database)
        val orphanGrant = grants.put(grant(ORPHAN_TOOL_ID))
        assertEquals(DataResult.Failure.NotFound("tool"), orphanGrant)

        val sessions = RoomRuntimeSessionRepository(database)
        val orphanStart = sessions.start(session(ORPHAN_TOOL_ID))
        assertEquals(DataResult.Failure.NotFound("tool"), orphanStart)
        assertTrue(sessions.start(session(TOOL_ID)) is DataResult.Success)
        val duplicateStart = sessions.start(session(TOOL_ID))
        assertEquals(DataResult.Failure.InvalidInput("sessionId"), duplicateStart)
        assertTrue(sessions.finish("runtime-session", 200, "closed") is DataResult.Success)
        val finishedAgain = sessions.finish("runtime-session", 201, "closed-again")
        assertEquals(DataResult.Failure.NotFound("runtimeSession"), finishedAgain)
        val missingFinish = sessions.finish("missing-session", 200, "closed")
        assertEquals(DataResult.Failure.NotFound("runtimeSession"), missingFinish)

        val memory = InMemoryCoreData.create()
        assertEquals(orphanKv, memory.keyValues.put(ORPHAN_TOOL_ID, "key", "x", 1, 5))
        assertEquals(orphanGrant, memory.grants.put(grant(ORPHAN_TOOL_ID)))
        assertEquals(orphanStart, memory.sessions.start(session(ORPHAN_TOOL_ID)))
        memory.catalog.registerVersion(tool(), version())
        assertTrue(memory.sessions.start(session(TOOL_ID)) is DataResult.Success)
        assertEquals(duplicateStart, memory.sessions.start(session(TOOL_ID)))
        assertTrue(memory.sessions.finish("runtime-session", 200, "closed") is DataResult.Success)
        assertEquals(finishedAgain, memory.sessions.finish("runtime-session", 201, "closed-again"))
        assertEquals(missingFinish, memory.sessions.finish("missing-session", 200, "closed"))
        database.close()
    }

    private fun openDatabase() = Room.databaseBuilder(
        context,
        ToolBoxDatabase::class.java,
        DATABASE_NAME,
    ).build()

    private fun settingsScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun settingsRepository(scope: CoroutineScope) = DataStoreHostSettingsRepository(
        createHostSettingsDataStore(context.preferencesDataStoreFile(SETTINGS_NAME), scope),
    )

    private fun tool() = ToolMetadata(
        id = TOOL_ID,
        name = "Persistent tool",
        signatureState = SignatureState.VERIFIED_UNKNOWN,
        publisherKeyId = null,
        securityProfile = SecurityProfile.STRICT,
        installedAt = 100,
    )

    private fun version() = ToolVersion(
        toolId = TOOL_ID,
        versionCode = VERSION_CODE,
        version = "1.0.0",
        bundleLocator = BundleLocator("miniapps/persistent/versions/1/bundle"),
        bundleBytes = 256,
        integrityHash = "sha256:persistent",
        installedAt = 100,
        launchState = LaunchState.PENDING,
    )

    private fun grant(toolId: String) = PermissionGrant(
        toolId = toolId,
        permission = "storage",
        state = GrantState.GRANTED,
        scope = GrantScope.PERSISTENT,
        grantedAt = 100,
        expiresAt = null,
        source = GrantSource.INSTALL,
    )

    private fun session(toolId: String) = RuntimeSession(
        sessionId = "runtime-session",
        toolId = toolId,
        origin = "https://runtime.invalid",
        profileName = null,
        nonceHash = "nonce-hash",
        startedAt = 100,
        endedAt = null,
        exitReason = null,
    )

    private companion object {
        const val DATABASE_NAME = "persistence-contract.db"
        const val SETTINGS_NAME = "persistence-contract-settings"
        const val TOOL_ID = "io.toolbox.test.persistence"
        const val ORPHAN_TOOL_ID = "io.toolbox.test.orphan"
        const val VERSION_CODE = 1
    }
}

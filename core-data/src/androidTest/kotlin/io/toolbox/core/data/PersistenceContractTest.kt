package io.toolbox.core.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.core.data.db.RoomCatalogRepository
import io.toolbox.core.data.db.RoomCatalogLifecycleRepository
import io.toolbox.core.data.db.RoomCatalogOrganizationRepository
import io.toolbox.core.data.db.RoomAuditRepository
import io.toolbox.core.data.db.RoomPermissionGrantRepository
import io.toolbox.core.data.db.RoomRuntimeSessionRepository
import io.toolbox.core.data.db.RoomToolKvRepository
import io.toolbox.core.data.db.ToolBoxDatabase
import io.toolbox.core.data.db.ToolEntity
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
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%' AND name NOT LIKE 'sqlite_%'",
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
        val firstLifecycle = RoomCatalogLifecycleRepository(firstDatabase)
        val firstOrganization = RoomCatalogOrganizationRepository(firstDatabase)
        assertTrue(firstCatalog.observeTools().first().isEmpty())
        assertEquals(
            DataResult.Success(CommitInstallOutcome.Committed),
            firstLifecycle.commitInstall(attempt()),
        )
        assertEquals(LaunchState.PENDING, firstCatalog.observeVersions(TOOL_ID).first().single().launchState)
        assertEquals(DataResult.Success(Unit), firstLifecycle.markActiveVersionStable(TOOL_ID, VERSION_CODE))
        assertEquals(DataResult.Success(Unit), firstOrganization.setPinnedOrder(TOOL_ID, 0))
        assertEquals(DataResult.Success(Unit), firstOrganization.setCategory(TOOL_ID, "finance"))
        assertEquals(DataResult.Success(Unit), firstOrganization.recordOpened(TOOL_ID, 1_234L))

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
        val reopenedLifecycle = RoomCatalogLifecycleRepository(reopenedDatabase)
        val persistedTool = reopenedCatalog.observeTool(TOOL_ID).first()
        assertEquals(VERSION_CODE, persistedTool?.activeVersionCode)
        assertEquals(0, persistedTool?.metadata?.pinnedOrder)
        assertEquals("finance", persistedTool?.metadata?.categoryId)
        assertEquals(1_234L, persistedTool?.lastOpenedAt)
        assertEquals(LaunchState.STABLE, reopenedCatalog.observeVersions(TOOL_ID).first().single().launchState)
        assertEquals("persistent-session-1", reopenedCatalog.observeVersions(TOOL_ID).first().single().sourceSessionId)
        assertEquals(
            DataResult.Success(CommittedInstall(TOOL_ID, VERSION_CODE)),
            reopenedLifecycle.findCommittedInstall("persistent-session-1"),
        )
        assertEquals(
            DataResult.Success(null),
            reopenedLifecycle.findCommittedInstall("persistent-session-1-missing"),
        )

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
        )
        val failingLifecycle = RoomCatalogLifecycleRepository(
            database,
            CatalogCommitHook { error("injected commit failure") },
        )
        val emptySnapshot = failingLifecycle.snapshot(TOOL_ID)

        assertEquals(
            DataResult.Failure.StorageFailure("commitInstall"),
            failingLifecycle.commitInstall(attempt()),
        )
        assertEquals(emptySnapshot, failingLifecycle.snapshot(TOOL_ID))

        val lifecycle = RoomCatalogLifecycleRepository(database)
        assertEquals(
            DataResult.Failure.UnsignedPersistentGrant(TOOL_ID, "storage"),
            lifecycle.commitInstall(
                attempt(
                    sourceSessionId = "unsafe-persistent-grant",
                    initialGrantScope = GrantScope.PERSISTENT,
                ),
            ),
        )
        assertEquals(emptySnapshot, lifecycle.snapshot(TOOL_ID))
        assertTrue(lifecycle.commitInstall(attempt()) is DataResult.Success)
        assertEquals(DataResult.Success(Unit), lifecycle.markActiveVersionStable(TOOL_ID, VERSION_CODE))
        val beforeUpdate = (lifecycle.snapshot(TOOL_ID) as DataResult.Success).value
        val update = attempt(
            versionCode = 2,
            name = "Updated tool",
            sourceSessionId = "persistent-session-2",
            categoryId = "package-category-must-not-replace-user-category",
        )
        assertTrue(
            lifecycle.commitInstall(update) is DataResult.Success,
        )
        assertEquals("user-category", catalog.observeTool(TOOL_ID).first()?.metadata?.categoryId)
        assertEquals(DataResult.Success(Unit), lifecycle.markActiveVersionStable(TOOL_ID, 2))
        val stableUpdate = lifecycle.snapshot(TOOL_ID)
        assertEquals(
            DataResult.Failure.LifecycleConflict(TOOL_ID),
            lifecycle.compensateInstall(update, beforeUpdate),
        )
        assertEquals(stableUpdate, lifecycle.snapshot(TOOL_ID))
        assertEquals(DataResult.Success(RollbackOutcome(1)), lifecycle.rollbackToPreviousStable(TOOL_ID))
        assertEquals("Persistent tool", catalog.observeTool(TOOL_ID).first()?.metadata?.name)
        assertEquals("user-category", catalog.observeTool(TOOL_ID).first()?.metadata?.categoryId)

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
        memory.lifecycle.commitInstall(attempt())
        assertTrue(memory.sessions.start(session(TOOL_ID)) is DataResult.Success)
        assertEquals(duplicateStart, memory.sessions.start(session(TOOL_ID)))
        assertTrue(memory.sessions.finish("runtime-session", 200, "closed") is DataResult.Success)
        assertEquals(finishedAgain, memory.sessions.finish("runtime-session", 201, "closed-again"))
        assertEquals(missingFinish, memory.sessions.finish("missing-session", 200, "closed"))

        assertTrue(sessions.start(session(TOOL_ID, "runtime-session-open")) is DataResult.Success)

        val audit = RoomAuditRepository(database)
        assertTrue(audit.append(auditEvent()) is DataResult.Success)
        assertEquals(DataResult.Success(DeleteToolCatalogOutcome.Deleted), lifecycle.deleteToolCatalog(TOOL_ID))
        assertEquals(DataResult.Success(DeleteToolCatalogOutcome.AlreadyAbsent), lifecycle.deleteToolCatalog(TOOL_ID))
        assertTrue(catalog.observeVersions(TOOL_ID).first().isEmpty())
        assertTrue(grants.observeGrants(TOOL_ID).first().isEmpty())
        assertEquals(0L, keyValues.bytesUsed(TOOL_ID))
        assertEquals(DataResult.Failure.NotFound("tool"), keyValues.put(TOOL_ID, "after-delete", "x", 1, 5))
        assertTrue(sessions.observeOpenSessions().first().isEmpty())
        assertEquals(1, audit.observeRecent(10).first().size)

        memory.grants.put(grant(TOOL_ID))
        memory.keyValues.put(TOOL_ID, "owned", "x", 1, 5)
        memory.sessions.start(session(TOOL_ID, "memory-open"))
        memory.audit.append(auditEvent())
        assertEquals(
            DataResult.Success(DeleteToolCatalogOutcome.Deleted),
            memory.lifecycle.deleteToolCatalog(TOOL_ID),
        )
        assertTrue(memory.grants.observeGrants(TOOL_ID).first().isEmpty())
        assertEquals(0L, memory.keyValues.bytesUsed(TOOL_ID))
        assertTrue(memory.sessions.observeOpenSessions().first().isEmpty())
        assertEquals(1, memory.audit.observeRecent(10).first().size)
        database.close()
    }

    @Test
    fun catalogProjectionRetainsToolWhenActiveVersionRowIsMissing() = runBlocking {
        val database = openDatabase()
        val lifecycle = RoomCatalogLifecycleRepository(database)
        val catalog = RoomCatalogRepository(database)

        assertEquals(
            DataResult.Success(CommitInstallOutcome.Committed),
            lifecycle.commitInstall(attempt()),
        )
        database.tools().insert(
            ToolEntity(
                id = ORPHAN_TOOL_ID,
                name = "Missing active version",
                activeVersionCode = 999,
                signatureState = SignatureState.UNSIGNED.name,
                publisherKeyId = null,
                securityProfile = SecurityProfile.STRICT.name,
                installedAt = 99,
                lastOpenedAt = null,
                pinnedOrder = null,
                categoryId = null,
            ),
        )

        val projection = catalog.observeCatalogProjection().first()
        val installed = projection.single { it.toolId == TOOL_ID }
        assertEquals(VERSION_CODE, installed.activeVersionCode)
        assertEquals("1.0.0", installed.activeVersionName)
        assertEquals(256L, installed.bundleBytes)
        assertEquals(LaunchState.PENDING, installed.launchState)

        val missing = projection.single { it.toolId == ORPHAN_TOOL_ID }
        assertEquals("Missing active version", missing.name)
        assertEquals(null, missing.activeVersionCode)
        assertEquals(null, missing.activeVersionName)
        assertEquals(null, missing.bundleBytes)
        assertEquals(null, missing.launchState)
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

    private fun tool(
        name: String = "Persistent tool",
        categoryId: String = "user-category",
    ) = ToolMetadata(
        id = TOOL_ID,
        name = name,
        signatureState = SignatureState.UNSIGNED,
        publisherKeyId = null,
        securityProfile = SecurityProfile.STRICT,
        installedAt = 100,
        categoryId = categoryId,
    )

    private fun version(
        versionCode: Int = VERSION_CODE,
        name: String = "Persistent tool",
        sourceSessionId: String = "persistent-session-1",
    ) = ToolVersion(
        toolId = TOOL_ID,
        versionCode = versionCode,
        version = "$versionCode.0.0",
        bundleLocator = BundleLocator("miniapps/persistent/versions/$versionCode/bundle"),
        bundleBytes = 256,
        integrityHash = "sha256:persistent-$versionCode",
        installedAt = 100,
        launchState = LaunchState.PENDING,
        sourceSessionId = sourceSessionId,
        identity = ToolVersionIdentity(
            name = name,
            signatureState = SignatureState.UNSIGNED,
            publisherKeyId = null,
            securityProfile = SecurityProfile.STRICT,
        ),
    )

    private fun attempt(
        versionCode: Int = VERSION_CODE,
        name: String = "Persistent tool",
        sourceSessionId: String = "persistent-session-1",
        categoryId: String = "user-category",
        initialGrantScope: GrantScope = GrantScope.SESSION,
    ) = CatalogInstallAttempt(
        metadata = tool(name, categoryId),
        version = version(versionCode, name, sourceSessionId),
        initialGrants = listOf(grant(TOOL_ID).copy(scope = initialGrantScope)),
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

    private fun session(toolId: String, sessionId: String = "runtime-session") = RuntimeSession(
        sessionId = sessionId,
        toolId = toolId,
        origin = "https://runtime.invalid",
        profileName = null,
        nonceHash = "nonce-hash",
        startedAt = 100,
        endedAt = null,
        exitReason = null,
    )

    private fun auditEvent() = AuditEvent(
        toolId = TOOL_ID,
        sessionId = null,
        category = "catalog",
        action = "delete",
        result = "requested",
        risk = AuditRisk.MEDIUM,
        targetHost = null,
        timestamp = 300,
        durationMs = null,
        byteCount = null,
    )

    private companion object {
        const val DATABASE_NAME = "persistence-contract.db"
        const val SETTINGS_NAME = "persistence-contract-settings"
        const val TOOL_ID = "io.toolbox.test.persistence"
        const val ORPHAN_TOOL_ID = "io.toolbox.test.orphan"
        const val VERSION_CODE = 1
    }
}

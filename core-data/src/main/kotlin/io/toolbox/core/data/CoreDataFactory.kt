package io.toolbox.core.data

import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import io.toolbox.core.data.db.RoomAuditRepository
import io.toolbox.core.data.db.RoomCatalogRepository
import io.toolbox.core.data.db.RoomCatalogLifecycleRepository
import io.toolbox.core.data.db.RoomCatalogOrganizationRepository
import io.toolbox.core.data.db.RoomPermissionGrantRepository
import io.toolbox.core.data.db.RoomPublisherRepository
import io.toolbox.core.data.db.RoomRuntimeSessionRepository
import io.toolbox.core.data.db.RoomToolKvRepository
import io.toolbox.core.data.db.ToolBoxDatabase
import io.toolbox.core.data.settings.DataStoreHostSettingsRepository
import io.toolbox.core.data.settings.ProcessLifetimeHostSettingsDataStores

class CoreDataStores internal constructor(
    val repositories: CoreDataRepositories,
    private val database: ToolBoxDatabase,
) : AutoCloseable {
    override fun close() {
        database.close()
    }
}

class CoreDataInitializationException(
    val reason: Reason,
    cause: Throwable? = null,
) : IllegalStateException("Core data initialization failed: ${reason.name}", cause) {
    enum class Reason { MAIN_THREAD_INITIALIZATION, SETTINGS_PATH_UNAVAILABLE }
}

object CoreDataFactory {
    fun create(
        context: Context,
        databaseName: String = "toolbox.db",
        settingsName: String = "host-settings",
    ): CoreDataStores {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw CoreDataInitializationException(
                CoreDataInitializationException.Reason.MAIN_THREAD_INITIALIZATION,
            )
        }
        val appContext = context.applicationContext
        val settings = ProcessLifetimeHostSettingsDataStores.get(
            appContext.preferencesDataStoreFile(settingsName),
        )
        val database = Room.databaseBuilder(appContext, ToolBoxDatabase::class.java, databaseName).build()
        return CoreDataStores(
            repositories = CoreDataRepositories(
                catalog = RoomCatalogRepository(database),
                lifecycle = RoomCatalogLifecycleRepository(database),
                organization = RoomCatalogOrganizationRepository(database),
                grants = RoomPermissionGrantRepository(database),
                keyValues = RoomToolKvRepository(database),
                publishers = RoomPublisherRepository(database),
                audit = RoomAuditRepository(database),
                sessions = RoomRuntimeSessionRepository(database),
                settings = DataStoreHostSettingsRepository(settings),
            ),
            database = database,
        )
    }
}

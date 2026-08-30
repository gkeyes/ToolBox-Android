package io.toolbox.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ToolEntity::class,
        ToolVersionEntity::class,
        PermissionGrantEntity::class,
        ToolKvEntity::class,
        InstallTransactionEntity::class,
        BackgroundTaskEntity::class,
        TaskResultEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class ToolBoxDatabase : RoomDatabase() {
    abstract fun tools(): ToolDao
    abstract fun versions(): ToolVersionDao
    abstract fun grants(): PermissionGrantDao
    abstract fun keyValues(): ToolKvDao
    abstract fun installs(): InstallTransactionDao
    abstract fun backgroundTasks(): BackgroundTaskDao
    abstract fun taskResults(): TaskResultDao
}

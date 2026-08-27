package io.toolbox.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ToolEntity::class,
        ToolVersionEntity::class,
        PermissionGrantEntity::class,
        ToolKvEntity::class,
        PublisherEntity::class,
        AuditLogEntity::class,
        RuntimeSessionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class ToolBoxDatabase : RoomDatabase() {
    abstract fun tools(): ToolDao
    abstract fun versions(): ToolVersionDao
    abstract fun grants(): PermissionGrantDao
    abstract fun keyValues(): ToolKvDao
    abstract fun publishers(): PublisherDao
    abstract fun audit(): AuditDao
    abstract fun sessions(): RuntimeSessionDao
}

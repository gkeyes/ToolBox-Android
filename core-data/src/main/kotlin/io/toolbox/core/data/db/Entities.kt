package io.toolbox.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "tools")
internal data class ToolEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val securityProfile: String,
    val installedAt: Long,
    val lastOpenedAt: Long?,
    val pinnedOrder: Int?,
    val categoryId: String?,
)

@Entity(
    tableName = "tool_versions",
    foreignKeys = [
        ForeignKey(
            entity = ToolEntity::class,
            parentColumns = ["id"],
            childColumns = ["toolId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ToolVersionEntity(
    @androidx.room.PrimaryKey val toolId: String,
    val versionCode: Int,
    val version: String,
    val bundleLocator: String,
    val bundleBytes: Long,
    val integrityHash: String,
    val installedAt: Long,
)

internal data class InstalledToolProjection(
    val id: String,
    val name: String,
    val securityProfile: String,
    val installedAt: Long,
    val lastOpenedAt: Long?,
    val pinnedOrder: Int?,
    val categoryId: String?,
    val versionCode: Int,
    val version: String,
    val bundleLocator: String,
    val bundleBytes: Long,
    val integrityHash: String,
    val versionInstalledAt: Long,
)

@Entity(
    tableName = "permission_grants",
    primaryKeys = ["toolId", "capability"],
    foreignKeys = [
        ForeignKey(
            entity = ToolEntity::class,
            parentColumns = ["id"],
            childColumns = ["toolId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("toolId")],
)
internal data class PermissionGrantEntity(
    val toolId: String,
    val capability: String,
    val granted: Boolean,
    val updatedAt: Long,
)

@Entity(
    tableName = "tool_kv",
    primaryKeys = ["toolId", "key"],
    foreignKeys = [
        ForeignKey(
            entity = ToolEntity::class,
            parentColumns = ["id"],
            childColumns = ["toolId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("toolId")],
)
internal data class ToolKvEntity(
    val toolId: String,
    val key: String,
    val valueJson: String,
    val updatedAt: Long,
    val bytes: Int,
)

@Entity(tableName = "install_transactions", indices = [Index("state"), Index("toolId")])
internal data class InstallTransactionEntity(
    @androidx.room.PrimaryKey val id: String,
    val toolId: String,
    val versionCode: Int,
    val state: String,
    val startedAt: Long,
    val updatedAt: Long,
    val failureCode: String?,
)

@Entity(
    tableName = "background_tasks",
    foreignKeys = [
        ForeignKey(
            entity = ToolEntity::class,
            parentColumns = ["id"],
            childColumns = ["toolId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("toolId"), Index(value = ["toolId", "key"]), Index("state")],
)
internal data class BackgroundTaskEntity(
    @androidx.room.PrimaryKey val taskId: String,
    val toolId: String,
    val versionCode: Int,
    val key: String,
    val operation: String,
    val specJson: String,
    val periodic: Boolean,
    val intervalMinutes: Long?,
    val state: String,
    val createdAt: Long,
    val updatedAt: Long,
    val nextRunAt: Long?,
    val runAttempt: Int,
)

@Entity(
    tableName = "task_results",
    foreignKeys = [
        ForeignKey(
            entity = BackgroundTaskEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class TaskResultEntity(
    @androidx.room.PrimaryKey val taskId: String,
    val outcome: String,
    val completedAt: Long,
    val payloadJson: String?,
    val errorCode: String?,
    val attemptCount: Int,
)

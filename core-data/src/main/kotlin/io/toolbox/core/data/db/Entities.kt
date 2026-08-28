package io.toolbox.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "tools")
internal data class ToolEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val activeVersionCode: Int?,
    val signatureState: String,
    val publisherKeyId: String?,
    val securityProfile: String,
    val installedAt: Long,
    val lastOpenedAt: Long?,
    val pinnedOrder: Int?,
    val categoryId: String?,
)

@Entity(
    tableName = "tool_versions",
    primaryKeys = ["toolId", "versionCode"],
    foreignKeys = [
        ForeignKey(
            entity = ToolEntity::class,
            parentColumns = ["id"],
            childColumns = ["toolId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("toolId"), Index(value = ["sourceSessionId"], unique = true)],
)
internal data class ToolVersionEntity(
    val toolId: String,
    val versionCode: Int,
    val version: String,
    val bundleLocator: String,
    val bundleBytes: Long,
    val integrityHash: String,
    val installedAt: Long,
    val launchState: String,
    val sourceSessionId: String,
    val name: String,
    val signatureState: String,
    val publisherKeyId: String?,
    val securityProfile: String,
)

@Entity(
    tableName = "permission_grants",
    primaryKeys = ["toolId", "permission"],
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
    val permission: String,
    val state: String,
    val scope: String,
    val grantedAt: Long,
    val expiresAt: Long?,
    val source: String,
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

@Entity(tableName = "publishers")
internal data class PublisherEntity(
    @androidx.room.PrimaryKey val keyId: String,
    val displayName: String,
    val encodedPublicKey: String,
    val trustState: String,
    val addedAt: Long,
)

@Entity(tableName = "audit_logs", indices = [Index("timestamp"), Index("toolId")])
internal data class AuditLogEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long,
    val toolId: String?,
    val sessionId: String?,
    val category: String,
    val action: String,
    val result: String,
    val risk: String,
    val targetHost: String?,
    val timestamp: Long,
    val durationMs: Long?,
    val byteCount: Long?,
)

@Entity(
    tableName = "runtime_sessions",
    foreignKeys = [
        ForeignKey(
            entity = ToolEntity::class,
            parentColumns = ["id"],
            childColumns = ["toolId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("toolId"), Index("endedAt")],
)
internal data class RuntimeSessionEntity(
    @androidx.room.PrimaryKey val sessionId: String,
    val toolId: String,
    val origin: String,
    val profileName: String?,
    val nonceHash: String,
    val startedAt: Long,
    val endedAt: Long?,
    val exitReason: String?,
)

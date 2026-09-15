package io.toolbox.tool.packagekit

import java.io.InputStream
import java.nio.file.Path

data class PackageLimits(
    val maxPathCharacters: Int = 180,
    val maxManifestBytes: Long = 128L * 1024,
) {
    init {
        require(maxPathCharacters in 1..HARD_MAX_PATH_CHARACTERS)
        require(maxManifestBytes in 1..HARD_MAX_MANIFEST_BYTES)
    }

    companion object {
        const val HARD_MAX_PATH_CHARACTERS = 180
        const val HARD_MAX_MANIFEST_BYTES = 128L * 1024
    }
}

interface PackageInput {
    val displayName: String
    fun openStream(): InputStream
}

sealed interface PackageValidationResult {
    data class Valid(val manifest: ToolManifest, val archive: ArchiveSummary) : PackageValidationResult
    data class Rejected(val rejection: PackageRejection) : PackageValidationResult
}

interface ToolPackageInspector {
    suspend fun validate(input: PackageInput): PackageValidationResult
}

data class ArchiveSummary(
    val compressedBytes: Long,
    val extractedBytes: Long,
    val fileCount: Int,
    val files: List<String>,
)

data class ToolManifest(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val shortName: String?,
    val description: String?,
    val version: String,
    val versionCode: Int,
    val entry: String,
    val icon: String?,
    val apiVersion: String,
    val minHostVersion: String,
    val categories: List<String>,
    val permissions: List<ManifestPermission>,
    val securityProfile: SecurityProfile,
    val network: ManifestNetwork?,
    val ui: ManifestUi,
    val limits: ManifestLimits,
)

data class ManifestPermission(val name: String, val reason: String, val required: Boolean)
enum class SecurityProfile { STRICT, COMPAT }
data class ManifestNetwork(
    val allowDomains: List<String>,
    val allowRedirects: Boolean,
    val maxResponseBytes: Int,
    val timeoutMs: Int,
    val allowUserDomains: Boolean = false,
)
data class ManifestUi(
    val orientation: ManifestOrientation?,
    val allowFullscreen: Boolean,
    val statusBarStyle: ManifestStatusBarStyle,
    val showHostToolbar: Boolean,
)
enum class ManifestOrientation { UNSPECIFIED, PORTRAIT, LANDSCAPE }
enum class ManifestStatusBarStyle { AUTO, LIGHT, DARK }
data class ManifestLimits(val maxBridgePayloadBytes: Int)

data class PackageRejection(val code: PackageRejectionCode, val detail: String)

enum class PackageRejectionCode {
    INSUFFICIENT_SPACE,
    INSUFFICIENT_RESOURCES,
    RESOURCE_CHECK_FAILED,
    SOURCE_READ_FAILED,
    TEMPORARY_IO_FAILED,
    CLEANUP_FAILED,
    MALFORMED_ARCHIVE,
    UNSUPPORTED_ZIP_FEATURE,
    PATH_INVALID,
    PATH_TOO_LONG,
    PATH_COLLISION,
    SYMLINK,
    SPECIAL_FILE,
    NESTED_ARCHIVE,
    NATIVE_OR_DYNAMIC_CODE,
    EXTRACTION_FAILED,
    MANIFEST_MISSING,
    MANIFEST_TOO_LARGE,
    MANIFEST_INVALID,
    ENTRY_MISSING,
    ENTRY_MIME_INVALID,
    INTEGRITY_MALFORMED,
    INTEGRITY_FILE_SET_MISMATCH,
    INTEGRITY_HASH_MISMATCH,
    SIGNATURE_MALFORMED,
    SIGNATURE_KEY_ID_MISMATCH,
    SIGNATURE_INVALID,
}

internal data class PreparedPackage(
    val manifest: ToolManifest,
    val archive: ArchiveSummary,
    val bundleDirectory: Path,
    val fileHashes: Map<String, String>,
    val temporaryDirectory: Path,
)

internal sealed interface PreparationResult {
    data class Prepared(val value: PreparedPackage) : PreparationResult
    data class Rejected(val rejection: PackageRejection) : PreparationResult
}

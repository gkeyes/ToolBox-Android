package io.toolbox.tool.packagekit

import java.io.InputStream
import java.nio.file.Path

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
)

data class ManifestPermission(val name: String, val reason: String, val required: Boolean)
enum class SecurityProfile { STRICT, COMPAT }
data class ManifestNetwork(
    val maxResponseBytes: Long?,
    val timeoutMs: Int,
)
/**
 * The manifest `ui` block the runtime screen applies. `showHostToolbar` is still accepted by the
 * parser so already-installed packages keep verifying, but it is not modelled: the host never draws
 * a toolbar over a running tool, so there is nothing for it to show or hide.
 */
data class ManifestUi(
    val orientation: ManifestOrientation? = null,
    val allowFullscreen: Boolean = false,
    val statusBarStyle: ManifestStatusBarStyle = ManifestStatusBarStyle.AUTO,
)
enum class ManifestOrientation { UNSPECIFIED, PORTRAIT, LANDSCAPE }
enum class ManifestStatusBarStyle { AUTO, LIGHT, DARK }

data class PackageRejection(val code: PackageRejectionCode, val detail: String)

enum class PackageRejectionCode {
    SOURCE_READ_FAILED,
    TEMPORARY_IO_FAILED,
    INSUFFICIENT_SPACE,
    INSUFFICIENT_RESOURCES,
    RESOURCE_CHECK_FAILED,
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
    UNSUPPORTED_HOST_VERSION,
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
    val signingKeyId: String?,
)

internal sealed interface PreparationResult {
    data class Prepared(val value: PreparedPackage) : PreparationResult
    data class Rejected(val rejection: PackageRejection) : PreparationResult
}

/** Preflight uses the exact same inspector as installation, without publishing a bundle. */
object ToolPackageInspectors {
    /**
     * With [hostVersion], a package for a newer host is refused as UNSUPPORTED_HOST_VERSION (as on install)
     * instead of failing strict validation of fields this host does not know.
     */
    fun create(
        temporaryDirectory: java.io.File,
        resourceProbe: PackageResourceProbe = FileSystemPackageResourceProbe,
        hostVersion: String? = null,
    ): ToolPackageInspector = DefaultPackageInspector(
        temporaryDirectory.toPath(), resourceProbe = resourceProbe, hostVersion = hostVersion,
    )
}

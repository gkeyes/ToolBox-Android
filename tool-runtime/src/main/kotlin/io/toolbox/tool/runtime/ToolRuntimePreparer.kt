package io.toolbox.tool.runtime

import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.LaunchState
import io.toolbox.core.data.ToolVersion
import io.toolbox.tool.packagekit.InstalledManifestVerification
import io.toolbox.tool.packagekit.InstalledManifestVerifier
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

data class PreparedToolRuntime(
    val toolId: String,
    val toolName: String,
    val versionCode: Int,
    val launchState: LaunchState,
    val privateFilesRoot: Path,
    val bundleRoot: Path,
    val entry: String,
    val origin: String,
    val profileName: String,
    val securityProfile: io.toolbox.core.data.SecurityProfile,
) {
    val entryUrl: String get() = origin + entry
}

enum class RuntimePreparationCode {
    TOOL_NOT_INSTALLED,
    ACTIVE_VERSION_MISSING,
    LOCATOR_MISMATCH,
    BUNDLE_UNAVAILABLE,
    MANIFEST_INVALID,
    ENTRY_UNAVAILABLE,
}

sealed interface RuntimePreparationResult {
    data class Prepared(val runtime: PreparedToolRuntime) : RuntimePreparationResult
    data class Failed(
        val code: RuntimePreparationCode,
        val message: String,
    ) : RuntimePreparationResult
}

class ToolRuntimePreparer(privateFilesDirectory: File) {
    private val filesRoot = privateFilesDirectory.toPath().toAbsolutePath().normalize()

    fun prepare(toolId: String, tool: InstalledTool?, versions: List<ToolVersion>): RuntimePreparationResult {
        val installed = tool?.takeIf { it.metadata.id == toolId }
            ?: return failed(RuntimePreparationCode.TOOL_NOT_INSTALLED, "该工具已卸载或目录记录不可用。")
        val activeCode = installed.activeVersionCode
            ?: return failed(RuntimePreparationCode.ACTIVE_VERSION_MISSING, "该工具没有可运行的活动版本。")
        val version = versions.singleOrNull { it.toolId == toolId && it.versionCode == activeCode }
            ?: return failed(RuntimePreparationCode.ACTIVE_VERSION_MISSING, "活动版本记录不完整，请重新导入工具。")
        if (
            version.identity.name != installed.metadata.name ||
            version.identity.securityProfile != installed.metadata.securityProfile
        ) {
            return failed(RuntimePreparationCode.MANIFEST_INVALID, "活动版本身份与工具目录不一致。")
        }
        val expectedLocator = RuntimeIdentity.expectedBundleLocator(toolId, activeCode)
        if (version.bundleLocator.value != expectedLocator) {
            return failed(RuntimePreparationCode.LOCATOR_MISMATCH, "活动版本目录不符合 ToolBox 私有目录规则。")
        }
        val bundle = filesRoot.resolve(expectedLocator).normalize()
        if (!bundle.startsWith(filesRoot) || !isDirectoryWithoutLinks(bundle)) {
            return failed(RuntimePreparationCode.BUNDLE_UNAVAILABLE, "工具代码目录不存在或不安全。")
        }
        val manifestFile = bundle.resolve("manifest.json")
        val manifestBytes = readBoundedRegularFile(manifestFile, 128L * 1024)
            ?: return failed(RuntimePreparationCode.MANIFEST_INVALID, "已安装 manifest.json 不可读取。")
        val verified = InstalledManifestVerifier.verify(
            manifestBytes = manifestBytes,
            expectedToolId = toolId,
            expectedVersionCode = activeCode,
            expectedSecurityProfile = installed.metadata.securityProfile,
        )
        val manifest = when (verified) {
            is InstalledManifestVerification.Verified -> verified.manifest
            is InstalledManifestVerification.Rejected -> return failed(
                RuntimePreparationCode.MANIFEST_INVALID,
                "已安装 manifest.json 校验失败：${verified.detail}",
            )
        }
        if (manifest.name != version.identity.name) {
            return failed(RuntimePreparationCode.MANIFEST_INVALID, "已安装 manifest.json 名称与活动版本不一致。")
        }
        val entryFile = resolveSafeRegularFile(bundle, manifest.entry)
            ?: return failed(RuntimePreparationCode.ENTRY_UNAVAILABLE, "工具入口文件不存在或不安全。")
        if (entryFile.parent == null || !entryFile.startsWith(bundle)) {
            return failed(RuntimePreparationCode.ENTRY_UNAVAILABLE, "工具入口超出当前版本目录。")
        }
        return RuntimePreparationResult.Prepared(
            PreparedToolRuntime(
                toolId = toolId,
                toolName = manifest.name,
                versionCode = activeCode,
                launchState = version.launchState,
                privateFilesRoot = filesRoot,
                bundleRoot = bundle,
                entry = manifest.entry,
                origin = RuntimeIdentity.origin(toolId),
                profileName = RuntimeIdentity.profileName(toolId),
                securityProfile = installed.metadata.securityProfile,
            ),
        )
    }

    private fun isDirectoryWithoutLinks(directory: Path): Boolean {
        if (!directory.startsWith(filesRoot)) return false
        var cursor = filesRoot
        val relative = filesRoot.relativize(directory)
        return try {
            relative.forEach { segment ->
                cursor = cursor.resolve(segment)
                val attributes = Files.readAttributes(
                    cursor,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                if (!attributes.isDirectory || attributes.isSymbolicLink) return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun readBoundedRegularFile(path: Path, maxBytes: Long): ByteArray? {
        val attributes = runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return null
        if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() !in 1..maxBytes) return null
        return runCatching {
            Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                input.readNBytes((maxBytes + 1).toInt()).takeIf { it.size.toLong() <= maxBytes }
            }
        }.getOrNull()
    }

    private fun resolveSafeRegularFile(bundle: Path, relativePath: String): Path? {
        if (relativePath.isBlank() || relativePath.startsWith('/') || '\\' in relativePath) return null
        val segments = relativePath.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
        var cursor = bundle
        return try {
            segments.forEachIndexed { index, segment ->
                cursor = cursor.resolve(segment).normalize()
                if (!cursor.startsWith(bundle)) return null
                val attributes = Files.readAttributes(
                    cursor,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                if (attributes.isSymbolicLink) return null
                if (index == segments.lastIndex && !attributes.isRegularFile) return null
                if (index < segments.lastIndex && !attributes.isDirectory) return null
            }
            cursor
        } catch (_: Exception) {
            null
        }
    }

    private fun failed(code: RuntimePreparationCode, message: String) =
        RuntimePreparationResult.Failed(code, message)
}

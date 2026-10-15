package io.toolbox.host.backup

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.DataMutationLock
import io.toolbox.host.importflow.isUnsafeFormatCodePoint
import io.toolbox.tool.packagekit.PackageResourceProbe
import io.toolbox.tool.packagekit.PackageValidationResult
import io.toolbox.tool.packagekit.ToolPackageInspectors
import io.toolbox.tool.packagekit.backup.BackupException
import io.toolbox.tool.packagekit.lifecycle.InstalledPackageExport
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Save dialog for one exported tool; octet-stream keeps the suggested .tbx name on common providers. */
internal class CreateToolPackageDocument : ActivityResultContracts.CreateDocument("application/octet-stream") {
    override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

internal sealed interface ToolExportResult {
    data class Exported(val toolName: String) : ToolExportResult
    data class Failed(val code: String, val message: String) : ToolExportResult
}

internal fun interface ToolExportOperations {
    /** Writes the installed tool as a .tbx to [location]; the tool's stored data is not included. */
    suspend fun exportTool(toolId: String, location: String): ToolExportResult
}

/**
 * Suggested file name for the save dialog: the tool name and version. Path, reserved, control and
 * format characters become "_", as the importer rejects a selected name containing them.
 */
internal fun exportFileName(toolName: String, versionName: String): String {
    val raw = "$toolName-$versionName".trim()
    val cleaned = buildString {
        var offset = 0
        while (offset < raw.length) {
            val codePoint = Character.codePointAt(raw, offset)
            if ((codePoint < 0x80 && codePoint.toChar() in "\\/:*?\"<>|") || Character.isISOControl(codePoint) || isUnsafeFormatCodePoint(codePoint)) append('_')
            else appendCodePoint(codePoint)
            offset += Character.charCount(codePoint)
        }
    }
    return cleaned.ifEmpty { "tool" } + ".tbx"
}

/** Same repack and checks as a backup's package.tbx, for one tool. */
internal class HostToolPackageExporter(
    private val files: File,
    private val catalog: CatalogRepository,
    private val packageLock: DataMutationLock,
    private val documents: BackupDocumentIO,
    private val temporary: File,
    private val resourceProbe: PackageResourceProbe,
) : ToolExportOperations {
    override suspend fun exportTool(toolId: String, location: String): ToolExportResult = withContext(Dispatchers.IO) {
        val session = File(temporary, "export-${UUID.randomUUID()}")
        try {
            check(session.mkdirs())
            val pack = File(session, "package.tbx")
            val name = packageLock.run {
                val tool = catalog.observeTool(toolId).first() ?: return@run null
                InstalledPackageExport.write(files, tool.currentVersion, pack)
                tool.metadata.name
            } ?: return@withContext ToolExportResult.Failed("NOT_INSTALLED", "该工具已不存在。")
            val inspector = ToolPackageInspectors.create(File(session, "inspection"), resourceProbe)
            val checked = inspector.validate(FilePackageInput(pack))
            if (checked is PackageValidationResult.Rejected) {
                return@withContext ToolExportResult.Failed("PACKAGE_${checked.rejection.code.name}", "工具文件校验未通过，未导出。")
            }
            documents.save(pack, location, { _, _ -> }, {})
            ToolExportResult.Exported(name)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val code = (failure as? BackupException)?.code ?: "EXPORT_FAILED"
            ToolExportResult.Failed(code, if (code.startsWith("DESTINATION")) "无法写入所选位置，请换个位置重试。" else "导出未完成，请重试。")
        } finally {
            session.deleteRecursively()
        }
    }
}

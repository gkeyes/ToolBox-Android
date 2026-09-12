package io.toolbox.host.backup

import io.toolbox.core.data.*
import io.toolbox.tool.packagekit.backup.*
import io.toolbox.tool.packagekit.backup.BackupJson as J
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal data class BackupExport(val file: File, val warnings: List<String>)
internal data class RestoreToolPlan(val id: String, val name: String, val incoming: String, val current: String?, val skipped: String? = null)
internal data class RestorePreview(
    val prepared: PreparedBackup, val tools: List<RestoreToolPlan>,
    val currentVersions: Map<String, ToolVersion>, val runtimeIds: Set<String>, val activeTaskIds: Set<String>,
    val warnings: List<String>,
) {
    val conflicts get() = tools.count { it.skipped == null && it.current != null }
    val recoverable get() = tools.filter { it.skipped == null }
}
internal interface BackupOperations {
    val committed: Boolean
    suspend fun export(progress: (String, Int) -> Unit): BackupExport
    suspend fun inspect(input: InputStream, progress: (String, Int) -> Unit): RestorePreview
    suspend fun refresh(preview: RestorePreview): RestorePreview
    suspend fun restore(preview: RestorePreview, progress: (String, Int) -> Unit): List<String>
}

internal object BackupDataCodec {
    const val SECURE = "toolbox.runtime.v1.secure.values"
    const val MAX_JSON = 32L * 1024 * 1024
    fun secureKey(key: String) = key == SECURE || key.startsWith("$SECURE.chunk.")
    // Archive host control records but never turn imported data into executable runtime requests.
    fun hostControl(key: String) = key.startsWith("__toolbox.") || key.startsWith("toolbox.host.")
    fun keyFile(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) } + ".json"
    fun settings(value: HostSettings): Map<String, Any?> = mapOf(
        "dataVersion" to 1, "theme" to value.theme.name, "themeStyle" to value.themeStyle.name,
        "backgroundEnabled" to value.backgroundEnabled, "reduceTransparency" to value.reduceTransparency,
    )
    fun settings(old: HostSettings, data: Map<String, Any?>, warnings: MutableList<String>): HostSettings {
        if (J.int(data["dataVersion"]) != 1) {
            warnings += "宿主设置格式不兼容，保留当前设置；请升级 ToolBox 后再次恢复。"
            return old
        }
        BackupArchive.unknown(data, setOf("dataVersion", "theme", "themeStyle", "backgroundEnabled", "reduceTransparency"), warnings)
        fun <T : Enum<T>> enumValue(name: String, values: Array<T>, fallback: T): T {
            val raw = data[name] ?: return fallback
            val selected = values.firstOrNull { it.name == J.string(raw) }
            if (selected == null) warnings += "外观字段 $name 暂不支持，已保留本机值。"
            return selected ?: fallback
        }
        return old.copy(
            theme = enumValue("theme", ThemeMode.entries.toTypedArray(), old.theme),
            themeStyle = enumValue("themeStyle", ThemeStyle.entries.toTypedArray(), old.themeStyle),
            backgroundEnabled = data["backgroundEnabled"]?.let(J::bool) ?: old.backgroundEnabled,
            reduceTransparency = data["reduceTransparency"]?.let(J::bool) ?: old.reduceTransparency,
        )
    }
    fun write(file: File, value: Any?) { file.parentFile.mkdirs(); file.writeText(J.encode(value)) }
    fun requireSuccess(result: DataResult<*>) { if (result !is DataResult.Success) throw BackupException("DATA_WRITE") }
}

internal fun backupMessage(failure: Throwable): String = when ((failure as? BackupException)?.code) {
    "CHECKSUM", "INDEX", "CORRUPT", "PATH", "MISSING", "TOOLS" -> "归档损坏、校验不符或含非法条目。请重新导出完整备份，或选择另一份文件。"
    "LIMIT" -> "备份超出安全处理上限。请选择较小的完整备份，并检查可用存储空间。"
    "VERSION", "FORMAT", "DATA_VERSION" -> "备份格式与当前版本不兼容。请升级 ToolBox，或使用兼容版本重新导出。"
    "PACKAGE" -> "工具包未通过现有安装校验，恢复已撤销。请检查原工具包后重新导出。"
    "PREVIEW_CHANGED" -> "本机工具或任务已变化，请重新核对下方覆盖清单。"
    "ROLLBACK" -> "回滚尚未完成，旧数据快照仍保留，已暂停工具与写入。请释放空间并重启 ToolBox；不要卸载或清除应用数据。"
    "DESTINATION_CLEANUP" -> "导出未完成，文件提供器也未能删除不完整文件。请在文件管理器中删除刚创建的文件，再选择本地存储重试。"
    "DESTINATION" -> "目标文件写入或复核失败。请检查空间与访问权限，重新选择本地位置。"
    else -> "操作未完成。请检查可用空间和文件权限后重试；恢复期间的改动已尝试回滚，已停止的工具需手动重新打开。"
}

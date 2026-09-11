package io.toolbox.host.backup

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.work.WorkManager
import io.toolbox.core.data.*
import io.toolbox.core.data.backup.BackupDatabase
import io.toolbox.host.*
import io.toolbox.host.runtime.AndroidKeyStoreCipher
import io.toolbox.host.runtime.RuntimeSecureEnvelopeStorage
import io.toolbox.host.runtime.withRuntimeStorageQuiescent
import io.toolbox.tool.packagekit.*
import io.toolbox.tool.packagekit.backup.*
import io.toolbox.tool.packagekit.backup.BackupJson as J
import io.toolbox.tool.packagekit.lifecycle.SupportedToolCapabilities
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Portable adapter over existing stores and package operations; all work is off the UI thread. */
internal class HostBackupService(
    private val context: Context,
    private val repositories: CoreDataRepositories,
    private val database: BackupDatabase,
    private val packages: HostPackageOperations,
    private val packageLock: DataMutationLock,
    private val background: HostBackgroundOperations,
    private val runtimeIds: suspend () -> Set<String>,
    private val files: File = context.filesDir,
    private val temporary: File = File(context.cacheDir, "backup-operations"),
    private val journalRoot: File = File(files, "backup-restore"),
    private val cancelScheduledWork: suspend () -> Unit = {
        WorkManager.getInstance(context).cancelAllWorkByTag("toolbox-background").result.get(30, TimeUnit.SECONDS)
        Unit
    },
    private val checkpointPoint: (String) -> Unit = {},
) : BackupOperations {
    private val operation = Mutex()
    private val archive = BackupArchive()
    @Volatile override var committed = false
        private set

    override suspend fun export(progress: (String, Int) -> Unit): BackupExport = withContext(Dispatchers.IO) {
        operation.withLock {
            committed = false
            val session = File(temporary, "operation-${UUID.randomUUID()}")
            check(session.mkdirs())
            try {
                val content = File(session, "content").apply { mkdirs() }
                val warnings = mutableListOf<String>()
                val contents = packageLock.run {
                    repositories.mutations.run {
                        database.transaction {
                            val tools = repositories.catalog.observeTools().first()
                            BackupDataCodec.write(File(content, "host/settings.json"), BackupDataCodec.settings(repositories.settings.settings.first()))
                            tools.forEachIndexed { index, tool ->
                                exportTool(content, tool, warnings)
                                progress("收集工具与数据", (index + 1) * 45 / maxOf(1, tools.size))
                            }
                            BackupContents(BuildConfig.VERSION_NAME, System.currentTimeMillis(), tools.map {
                                BackupTool(it.metadata.id, it.metadata.name, it.currentVersion.version, it.currentVersion.versionCode)
                            }, warnings.distinct())
                        }
                    }
                }
                val zip = File(session, "ToolBox-backup.zip")
                archive.write(content, zip, contents) { progress("压缩并复核备份", 45 + it * 55 / 100) }
                check(content.deleteRecursively())
                BackupExport(zip, warnings.distinct())
            } catch (failure: Throwable) { session.deleteRecursively(); throw failure }
        }
    }

    private suspend fun exportTool(root: File, tool: InstalledTool, warnings: MutableList<String>) {
        val id = tool.metadata.id
        val target = File(root, "tools/$id").apply { mkdirs() }
        val bundle = File(files, tool.currentVersion.bundleLocator.value)
        check(bundle.canonicalFile.toPath().startsWith(File(files, "miniapps/$id/versions").canonicalFile.toPath()))
        val pack = File(target, "package.tbx")
        ZipOutputStream(FileOutputStream(pack)).use { zip ->
            BackupArchive.regularFiles(bundle).forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(bundle).invariantSeparatorsPath))
                file.inputStream().use { BackupArchive.copy(it, zip, PackageLimits.HARD_MAX_ENTRY_BYTES) }
                zip.closeEntry()
            }
        }
        val inspector = ToolPackageInspectors.create(File(root.parentFile, "inspection"))
        if (inspector.validate(FilePackageInput(pack)) !is PackageValidationResult.Valid) throw BackupException("PACKAGE")
        BackupDataCodec.write(File(target, "metadata.json"), mapOf(
            "dataVersion" to 1, "id" to id, "versionCode" to tool.currentVersion.versionCode,
            "installedAt" to tool.metadata.installedAt, "versionInstalledAt" to tool.currentVersion.installedAt,
            "lastOpenedAt" to tool.lastOpenedAt, "pinnedOrder" to tool.metadata.pinnedOrder, "categoryId" to tool.metadata.categoryId,
            "grants" to repositories.grants.observeGrants(id).first().map { mapOf("capability" to it.capability, "granted" to it.granted, "updatedAt" to it.updatedAt) },
        ))
        var secureBytes = 0L
        repositories.keyValues.keys(id).chunked(16).forEach { window ->
            repositories.keyValues.readSnapshot(id) { snapshot ->
                snapshot.getMany(window.toSet()).values.forEach { row ->
                    BackupDataCodec.write(File(target, "data/kv/${BackupDataCodec.keyFile(row.key)}"), mapOf(
                        "dataVersion" to 1, "key" to row.key, "valueJson" to row.valueJson, "updatedAt" to row.updatedAt,
                    ))
                    if (BackupDataCodec.secureKey(row.key)) secureBytes += row.bytes
                }
            }
        }
        if (secureBytes > 0) {
            if (secureBytes > BackupDataCodec.MAX_JSON) warnings += "${tool.metadata.name}：安全数据较大，仅归档原加密记录，跨设备可能需要重新登录。"
            else try {
                val encrypted = RuntimeSecureEnvelopeStorage(id, repositories.keyValues).read()
                if (encrypted != null) {
                    val plaintext = AndroidKeyStoreCipher(id).decrypt(encrypted)
                    J.obj(J.parse(plaintext.toByteArray()))
                    File(target, "data/secure.json").writeText(plaintext)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { warnings += "${tool.metadata.name}：硬件密钥不可用，已归档加密记录，恢复后可能需要重新登录。" }
        }
        repositories.backgroundTasks.observeTasks(id).first().forEach { task ->
            val result = repositories.backgroundTasks.observeResult(task.taskId).first()
            BackupDataCodec.write(File(target, "data/tasks/${BackupDataCodec.keyFile(task.taskId)}"), mapOf(
                "dataVersion" to 1, "taskId" to task.taskId, "versionCode" to task.versionCode, "key" to task.key, "operation" to task.operation.name,
                "specJson" to task.specJson, "periodic" to task.periodic, "intervalMinutes" to task.intervalMinutes,
                "state" to task.state.name, "createdAt" to task.createdAt, "updatedAt" to task.updatedAt, "runAttempt" to task.runAttempt,
                "result" to result?.let { mapOf("outcome" to it.outcome.name, "completedAt" to it.completedAt, "payloadJson" to it.payloadJson, "errorCode" to it.errorCode, "attemptCount" to it.attemptCount) },
            ))
        }
    }

    override suspend fun inspect(input: InputStream, progress: (String, Int) -> Unit): RestorePreview = withContext(Dispatchers.IO) {
        operation.withLock {
            committed = false
            temporary.mkdirs()
            val prepared = archive.read(input, temporary) { progress("检查归档和文件校验值", it * 70 / 100) }
            try { preview(prepared, progress) } catch (failure: Throwable) { prepared.close(); throw failure }
        }
    }
    override suspend fun refresh(preview: RestorePreview): RestorePreview = withContext(Dispatchers.IO) {
        operation.withLock { preview(preview.prepared) { _, _ -> } }
    }
    private suspend fun preview(prepared: PreparedBackup, progress: (String, Int) -> Unit): RestorePreview {
        val warnings = prepared.contents.warnings.toMutableList()
        val current = repositories.catalog.observeTools().first()
        BackupDataCodec.settings(repositories.settings.settings.first(), J.read(File(prepared.directory, "host/settings.json")), warnings)
        val inspector = ToolPackageInspectors.create(File(prepared.directory.parentFile, "inspection"))
        val taskIds = mutableSetOf<String>()
        val plans = prepared.contents.tools.mapIndexed { index, tool ->
            val folder = File(prepared.directory, "tools/${tool.id}")
            val result = inspector.validate(FilePackageInput(File(folder, "package.tbx"))) as? PackageValidationResult.Valid ?: throw BackupException("PACKAGE")
            if (result.manifest.id != tool.id || result.manifest.versionCode != tool.versionCode || result.manifest.version != tool.version) throw BackupException("INDEX")
            val metadata = J.read(File(folder, "metadata.json"))
            if (metadata["id"] != tool.id || J.int(metadata["versionCode"]) != tool.versionCode) throw BackupException("INDEX")
            BackupArchive.unknown(metadata, METADATA_KEYS, warnings)
            var skipped = when {
                tool.dataVersion != 1 || J.int(metadata["dataVersion"]) != 1 -> "数据格式较新，请升级 ToolBox 后恢复"
                !HostVersionPolicy.supports(BuildConfig.VERSION_NAME, result.manifest.minHostVersion) -> "需要 ToolBox ${result.manifest.minHostVersion} 或更高版本"
                result.manifest.permissions.any { it.required && it.name !in SupportedToolCapabilities.All } -> "当前宿主缺少该工具要求的能力"
                else -> null
            }
            if (skipped == null) validatePresentation(metadata)
            val keys = mutableSetOf<String>()
            var controls = 0
            dataFiles(folder, "kv").forEach { file ->
                val row = J.read(file, BackupDataCodec.MAX_JSON)
                if (J.int(row["dataVersion"]) != 1) { skipped = "部分数据格式不兼容，跳过整个工具，避免丢失无法识别的数据" }
                else {
                    val key = J.string(row["key"])
                    if (key.isBlank() || !keys.add(key) || file.name != BackupDataCodec.keyFile(key) || J.long(row["updatedAt"]) < 0) throw BackupException("INDEX")
                    J.string(row["valueJson"])
                    if (BackupDataCodec.hostControl(key)) controls++
                    BackupArchive.unknown(row, setOf("dataVersion", "key", "valueJson", "updatedAt"), warnings)
                }
            }
            File(folder, "data/secure.json").takeIf(File::exists)?.let { J.read(it, BackupDataCodec.MAX_JSON) }
            dataFiles(folder, "tasks").forEach { file ->
                parseTask(file, tool.id, warnings)?.let { (task, _) ->
                    if (!taskIds.add(task.taskId)) throw BackupException("INDEX")
                    val old = (repositories.backgroundTasks.getTask(task.taskId) as? DataResult.Success)?.value
                    if (old != null && old.toolId != tool.id) throw BackupException("INDEX")
                }
            }
            if (controls > 0) warnings += "${tool.name}：$controls 条运行描述符仅归档，不自动启动会话、闹钟或任务。"
            val known = setOf("package.tbx", "metadata.json", "data/secure.json") +
                dataFiles(folder, "kv").map { it.relativeTo(folder).invariantSeparatorsPath } + dataFiles(folder, "tasks").map { it.relativeTo(folder).invariantSeparatorsPath }
            if (BackupArchive.regularFiles(folder).any { it.relativeTo(folder).invariantSeparatorsPath !in known }) warnings += "${tool.name}：未知扩展文件暂不应用，原备份完整保留。"
            progress("按现有流程校验工具包", 70 + (index + 1) * 30 / maxOf(1, prepared.contents.tools.size))
            RestoreToolPlan(tool.id, tool.name, tool.version, current.firstOrNull { it.metadata.id == tool.id }?.currentVersion?.version, skipped)
        }
        return RestorePreview(prepared, plans, current.associate { it.metadata.id to it.currentVersion }, runtimeIds(), activeTasks(current), warnings.distinct())
    }

    override suspend fun restore(preview: RestorePreview, progress: (String, Int) -> Unit): List<String> = withContext(Dispatchers.IO) {
        operation.withLock {
            committed = false
            packageLock.run {
                val current = repositories.catalog.observeTools().first()
                if (current.associate { it.metadata.id to it.currentVersion } != preview.currentVersions || runtimeIds() != preview.runtimeIds || activeTasks(current) != preview.activeTaskIds) throw BackupException("PREVIEW_CHANGED")
                val warnings = preview.warnings.toMutableList()
                val journal = journal(files, journalRoot, repositories, database, preview.recoverable.map { it.id }, checkpointPoint)
                var safeToResume = true
                var paused = false
                try {
                    progress("停止已确认的运行任务", 2)
                    paused = true
                    withTimeout(30_000) { BackupRuntimeGate.pauseAndDrain() }
                    current.forEach { background.releaseRuntime(it.metadata.id) }
                    background.cancelAll(current.map { it.metadata.id })
                    cancelScheduledWork()
                    current.forEach { withRuntimeStorageQuiescent(it.metadata.id) { } }
                    repositories.mutations.run {
                        try {
                            progress("创建本机回滚快照", 8)
                            journal.begin()
                            database.transaction {
                                preview.recoverable.forEachIndexed { index, plan ->
                                    currentCoroutineContext().ensureActive()
                                    progress("恢复工具 ${index + 1}/${preview.recoverable.size}", 15 + index * 70 / maxOf(1, preview.recoverable.size))
                                    restoreTool(File(preview.prepared.directory, "tools/${plan.id}"), plan, warnings)
                                }
                                val settings = J.read(File(preview.prepared.directory, "host/settings.json"))
                                BackupDataCodec.requireSuccess(repositories.settings.update { BackupDataCodec.settings(it, settings, warnings) })
                            }
                            currentCoroutineContext().ensureActive()
                            withContext(NonCancellable) {
                                journal.commit()
                                committed = true
                                progress("恢复已提交", 100)
                            }
                        } catch (failure: Throwable) {
                            withContext(NonCancellable) {
                                if (journal.committed) committed = true
                                else try { progress("正在回滚原数据", 95); journal.rollback() }
                                catch (_: Exception) {
                                    safeToResume = false
                                    repositories.mutations.blockUntilRestart()
                                    packageLock.blockUntilRestart()
                                    throw BackupException("ROLLBACK")
                                }
                            }
                            if (!committed) throw failure
                        }
                    }
                    warnings += "权限按当前 manifest 和宿主授权重新检查；备份不会开启 Android 系统权限或新增高风险授权。"
                    warnings += "任务历史已恢复为完成或停止状态。请重新打开工具，检查登录状态并手动启动需要的会话、闹钟和任务。"
                    warnings.distinct()
                } finally { if (paused && safeToResume) BackupRuntimeGate.resume() }
            }
        }
    }

    private suspend fun restoreTool(folder: File, plan: RestoreToolPlan, warnings: MutableList<String>) {
        val imported = packages.importPackage(FilePackageInput(File(folder, "package.tbx")))
        val installed = if (imported is HostImportResult.ConfirmationRequired) {
            try { packages.confirmImport(imported.confirmation.id) }
            finally { withContext(NonCancellable) { packages.cancelImport(imported.confirmation.id) } }
        } else imported
        if (installed !is HostImportResult.Installed || installed.toolId != plan.id) throw BackupException("PACKAGE")
        BackupDataCodec.requireSuccess(repositories.keyValues.replace(plan.id, repositories.keyValues.keys(plan.id).filterNot(BackupDataCodec::secureKey).toSet(), emptyMap(), 0))
        var opaqueSecure = false
        dataFiles(folder, "kv").forEach { file ->
            val row = J.read(file, BackupDataCodec.MAX_JSON)
            val key = J.string(row["key"])
            when {
                BackupDataCodec.secureKey(key) -> opaqueSecure = true
                BackupDataCodec.hostControl(key) -> Unit
                else -> BackupDataCodec.requireSuccess(repositories.keyValues.put(plan.id, key, J.string(row["valueJson"]), J.long(row["updatedAt"])))
            }
        }
        val secure = File(folder, "data/secure.json")
        if (secure.exists()) {
            val manifest = packages.installedManifest(plan.id) as? HostInstalledManifestResult.Found
            val granted = repositories.grants.observeGrants(plan.id).first().any { it.capability == "storage.secure" && it.granted }
            if (manifest?.manifest?.permissions?.any { it.capability == "storage.secure" } == true && granted) {
                val plaintext = secure.readText()
                J.obj(J.parse(plaintext.toByteArray()))
                BackupDataCodec.requireSuccess(RuntimeSecureEnvelopeStorage(plan.id, repositories.keyValues).write(AndroidKeyStoreCipher(plan.id).encrypt(plaintext), System.currentTimeMillis()))
            } else warnings += "${plan.name}：当前未授权安全存储，本机安全数据未覆盖；开启权限后可再次恢复。"
        } else if (opaqueSecure) warnings += "${plan.name}：加密记录仍在原备份中，但缺少可迁移密钥，本机安全数据未覆盖；可能需要重新登录。"
        else BackupDataCodec.requireSuccess(RuntimeSecureEnvelopeStorage(plan.id, repositories.keyValues).clear())
        val metadata = J.read(File(folder, "metadata.json"))
        database.restorePresentation(plan.id, J.long(metadata["installedAt"]), J.long(metadata["versionInstalledAt"]), metadata["lastOpenedAt"]?.let(J::long), metadata["pinnedOrder"]?.let(J::int), metadata["categoryId"]?.let(J::string))
        BackupDataCodec.requireSuccess(repositories.backgroundTasks.deleteForTool(plan.id))
        dataFiles(folder, "tasks").forEach { parseTask(it, plan.id, warnings)?.let { (task, result) -> database.restoreTaskHistory(task, result) } }
    }

    private fun parseTask(file: File, toolId: String, warnings: MutableList<String>): Pair<BackgroundTask, TaskRunResult?>? {
        val row = J.read(file)
        if (J.int(row["dataVersion"]) != 1) { warnings += "部分任务历史格式较新，已跳过；原备份保留这些记录。"; return null }
        BackupArchive.unknown(row, TASK_KEYS, warnings)
        val operation = BackgroundOperation.entries.firstOrNull { it.name == row["operation"] }
        val state = TaskState.entries.firstOrNull { it.name == row["state"] }
        if (operation == null || state == null) { warnings += "未知任务类型已跳过，未执行。"; return null }
        val id = J.string(row["taskId"])
        if (file.name != BackupDataCodec.keyFile(id)) throw BackupException("INDEX")
        val task = BackgroundTask(id, toolId, J.int(row["versionCode"]), J.string(row["key"]), operation, J.string(row["specJson"]), J.bool(row["periodic"]), row["intervalMinutes"]?.let(J::long), state, J.long(row["createdAt"]), J.long(row["updatedAt"]), null, J.int(row["runAttempt"]))
        require(task.taskId.matches(Regex("^[A-Za-z0-9._:-]{1,128}$")) && task.versionCode > 0)
        require(task.key.isNotBlank() && task.key.length <= CoreDataLimits.MAX_TASK_KEY_LENGTH && task.createdAt >= 0 && task.updatedAt >= task.createdAt && task.runAttempt >= 0)
        require(task.specJson.toByteArray().size <= CoreDataLimits.MAX_TASK_SPEC_BYTES && (!task.periodic || (task.intervalMinutes ?: 0) >= 15))
        val result = row["result"]?.let { value ->
            val data = J.obj(value)
            BackupArchive.unknown(data, setOf("outcome", "completedAt", "payloadJson", "errorCode", "attemptCount"), warnings)
            val outcome = RunOutcome.entries.firstOrNull { it.name == data["outcome"] }
            if (outcome == null) { warnings += "未知任务结果已跳过。"; null }
            else TaskRunResult(id, outcome, J.long(data["completedAt"]), data["payloadJson"]?.let(J::string), data["errorCode"]?.let(J::string), J.int(data["attemptCount"]))
        }
        require(result == null || (result.completedAt >= 0 && result.attemptCount >= 0 && (result.payloadJson?.toByteArray()?.size ?: 0) <= CoreDataLimits.MAX_TASK_RESULT_BYTES))
        return task to result
    }
    private fun validatePresentation(data: Map<String, Any?>) {
        require(J.long(data["installedAt"]) >= 0 && J.long(data["versionInstalledAt"]) >= 0)
        require(data["lastOpenedAt"] == null || J.long(data["lastOpenedAt"]) >= 0)
        require(data["pinnedOrder"] == null || J.int(data["pinnedOrder"]) >= 0)
        require(data["categoryId"] == null || J.string(data["categoryId"]).length <= 200)
    }
    private suspend fun activeTasks(tools: List<InstalledTool>): Set<String> = tools.flatMap { repositories.backgroundTasks.observeTasks(it.metadata.id).first() }
        .filter { it.state == TaskState.QUEUED || it.state == TaskState.RUNNING }.map { it.taskId }.toSet()
    private fun dataFiles(folder: File, kind: String): List<File> = File(folder, "data/$kind").takeIf(File::isDirectory)?.listFiles()?.sortedBy { it.name }.orEmpty().also { list -> check(list.all { it.isFile && it.extension == "json" }) }

    companion object {
        suspend fun recover(context: Context, stores: CoreDataStores) = withContext(Dispatchers.IO) {
            File(context.cacheDir, "backup-operations").deleteRecursively()
            try {
                stores.repositories.mutations.run { journal(context.filesDir, File(context.filesDir, "backup-restore"), stores.repositories, stores.backup, emptyList()).recover() }
            } catch (_: Exception) { throw BackupException("ROLLBACK") }
        }
        private fun journal(files: File, root: File, repositories: CoreDataRepositories, database: BackupDatabase, ids: List<String>, point: (String) -> Unit = {}): RestoreJournal = RestoreJournal(
            root, File(files, "miniapps"), object : RestoreCheckpoint {
                override suspend fun save(directory: File) {
                    database.checkpoint(File(directory, "database.json"))
                    synced(File(directory, "settings.json"), J.encode(BackupDataCodec.settings(repositories.settings.settings.first())))
                    synced(File(directory, "key-cleanup.json"), J.encode(mapOf("ids" to ids.filterNot { AndroidKeyStoreCipher(it).hasKey() })))
                }
                override suspend fun restore(directory: File) {
                    database.restoreCheckpoint(File(directory, "database.json"))
                    val settings = J.read(File(directory, "settings.json"))
                    BackupDataCodec.requireSuccess(repositories.settings.update { BackupDataCodec.settings(it, settings, mutableListOf()) })
                    J.array(J.read(File(directory, "key-cleanup.json"))["ids"]).forEach { check(AndroidKeyStoreCipher.deleteForTool(J.string(it))) }
                }
            },
            syncDirectory = { directory ->
                val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_DIRECTORY, 0)
                try { Os.fsync(fd) } finally { Os.close(fd) }
            }, point = point,
        )
        private fun synced(file: File, text: String) { FileOutputStream(file).use { it.write(text.toByteArray()); it.fd.sync() } }
        private val METADATA_KEYS = setOf("dataVersion", "id", "versionCode", "installedAt", "versionInstalledAt", "lastOpenedAt", "pinnedOrder", "categoryId", "grants")
        private val TASK_KEYS = setOf("dataVersion", "taskId", "versionCode", "key", "operation", "specJson", "periodic", "intervalMinutes", "state", "createdAt", "updatedAt", "runAttempt", "result")
    }
}

internal class FilePackageInput(private val file: File) : PackageInput {
    override val displayName = "backup-package.tbx"
    override fun openStream(): InputStream = file.inputStream()
}

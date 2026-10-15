package io.toolbox.host.backup

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.work.WorkManager
import io.toolbox.core.data.*
import io.toolbox.core.data.backup.BackupDatabase
import io.toolbox.host.*
import io.toolbox.host.runtime.AndroidKeyStoreCipher
import io.toolbox.host.runtime.RuntimeSecureStore
import io.toolbox.host.runtime.RuntimeValueJson
import io.toolbox.host.runtime.withRuntimeStorageQuiescent
import io.toolbox.tool.packagekit.*
import io.toolbox.tool.packagekit.backup.*
import io.toolbox.tool.packagekit.backup.BackupJson as J
import io.toolbox.tool.packagekit.lifecycle.InstalledPackageIdentity
import io.toolbox.tool.packagekit.lifecycle.SupportedToolCapabilities
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
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
        WorkManager.getInstance(context).cancelAllWorkByTag("toolbox-background").result.get()
        Unit
    },
    private val checkpointPoint: (String) -> Unit = {},
    private val resourceProbe: PackageResourceProbe = AndroidPackageResourceProbe(context),
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
                    // Only the row reads share the Room transaction, so the backup is one consistent
                    // snapshot across all tools. Rows are paged into per-tool spill files there, so
                    // memory stays bounded however much a tool stores. Bundle repacking, package
                    // validation, Keystore decryption and the backup's own file writes run after it
                    // commits; the package lock still keeps bundles stable. The spill lives in the
                    // session directory, which is deleted on failure or cancellation.
                    val spill = File(session, "spill").apply { mkdirs() }
                    val snapshot = repositories.mutations.run { database.transaction { readExportSnapshot(spill) } }
                    BackupDataCodec.write(File(content, "host/settings.json"), BackupDataCodec.settings(snapshot.settings))
                    snapshot.tools.forEachIndexed { index, tool ->
                        try { exportTool(content, tool, warnings) }
                        finally { tool.keyValues.delete(); tool.tasks.delete() }
                        progress("收集工具与数据", (index + 1) * 45 / maxOf(1, snapshot.tools.size))
                    }
                    spill.deleteRecursively()
                    BackupContents(BuildConfig.VERSION_NAME, System.currentTimeMillis(), snapshot.tools.map { (tool) ->
                        BackupTool(tool.metadata.id, tool.metadata.name, tool.currentVersion.version, tool.currentVersion.versionCode)
                    }, warnings.distinct())
                }
                val zip = File(session, "ToolBox-backup.zip")
                archive.write(content, zip, contents) { progress("压缩并复核备份", 45 + it * 55 / 100) }
                check(content.deleteRecursively())
                BackupExport(zip, warnings.distinct())
            } catch (failure: Throwable) { session.deleteRecursively(); throw failure }
        }
    }

    /** Row reads and plain spill serialization only; runs inside the export transaction. */
    private suspend fun readExportSnapshot(spill: File): ExportSnapshot {
        val tools = repositories.catalog.observeTools().first()
        val settings = repositories.settings.settings.first()
        return ExportSnapshot(settings, tools.mapIndexed { index, tool ->
            val id = tool.metadata.id
            val keyValues = File(spill, "$index.kv")
            ExportSpillWriter(keyValues).use { writer ->
                repositories.keyValues.keys(id).chunked(16).forEach { window ->
                    repositories.keyValues.readSnapshot(id) { snapshot ->
                        val found = snapshot.getMany(window.toSet())
                        window.forEach { key -> found[key]?.let(writer::kv) }
                    }
                }
                writer.finish()
            }
            val tasks = File(spill, "$index.tasks")
            ExportSpillWriter(tasks).use { writer ->
                repositories.backgroundTasks.observeTasks(id).first().forEach { task ->
                    writer.task(task, repositories.backgroundTasks.observeResult(task.taskId).first())
                }
                writer.finish()
            }
            ExportToolSnapshot(tool, repositories.grants.observeGrants(id).first(), keyValues, tasks)
        })
    }

    /** Files, ZIP and Keystore work only; reads nothing but [snapshot]'s spill files and the locked bundle. */
    private suspend fun exportTool(root: File, snapshot: ExportToolSnapshot, warnings: MutableList<String>) {
        val tool = snapshot.tool
        val id = tool.metadata.id
        val target = File(root, "tools/$id").apply { mkdirs() }
        val bundle = File(files, tool.currentVersion.bundleLocator.value)
        check(bundle.canonicalFile.toPath().startsWith(File(files, "miniapps/$id/versions").canonicalFile.toPath()))
        val pack = File(target, "package.tbx")
        val destination = object : java.io.FilterOutputStream(FileOutputStream(pack)) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                ResourceCapacity.requireStorageBytes(target, length.toLong())
                out.write(bytes, offset, length)
            }
            override fun write(value: Int) {
                ResourceCapacity.requireStorageBytes(target, 1)
                out.write(value)
            }
        }
        ZipOutputStream(destination).use { zip ->
            BackupArchive.regularFiles(bundle).forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(bundle).invariantSeparatorsPath))
                file.inputStream().use { BackupArchive.copy(it, zip) }
                zip.closeEntry()
            }
        }
        val inspector = ToolPackageInspectors.create(File(root.parentFile, "inspection"), resourceProbe)
        validatePackage(inspector, pack)
        BackupDataCodec.write(File(target, "metadata.json"), mapOf(
            "dataVersion" to 1, "id" to id, "versionCode" to tool.currentVersion.versionCode,
            "installedAt" to tool.metadata.installedAt, "versionInstalledAt" to tool.currentVersion.installedAt,
            "lastOpenedAt" to tool.lastOpenedAt, "pinnedOrder" to tool.metadata.pinnedOrder, "categoryId" to tool.metadata.categoryId,
            "grants" to snapshot.grants.map { mapOf("capability" to it.capability, "granted" to it.granted, "updatedAt" to it.updatedAt) },
        ))
        // Secure rows are remembered by their offset in the key-value spill, so decryption can
        // load one encrypted row at a time instead of keeping all ciphertext next to the plaintext.
        val secureOffsets = LinkedHashMap<String, Long>()
        var secureBytes = 0L
        ExportSpillReader(snapshot.keyValues).use { reader ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val offset = reader.position
                val row = reader.nextKv() ?: break
                BackupDataCodec.write(File(target, "data/kv/${BackupDataCodec.keyFile(row.key)}"), mapOf(
                    "dataVersion" to 1, "key" to row.key, "valueJson" to row.valueJson, "updatedAt" to row.updatedAt,
                ))
                if (BackupDataCodec.secureKey(row.key)) {
                    secureBytes += row.bytes
                    secureOffsets[row.key] = offset
                }
            }
        }
        if (secureBytes > 0) {
            if (secureBytes > io.toolbox.core.data.ResourceCapacity.availableHeapBytes() / 2) warnings += "${tool.metadata.name}：当前可用内存不足以解密，仅归档原加密记录，跨设备可能需要重新登录。"
            else try {
                val document = ExportSpillRows(snapshot.keyValues).use { rows ->
                    val repository = SnapshotToolKvRepository(id, secureOffsets.keys.toList(), secureBytes) { key ->
                        secureOffsets[key]?.let(rows::kvAt)
                    }
                    RuntimeSecureStore(id, repository, AndroidKeyStoreCipher(id)).readAll()
                }
                if (document != null) {
                    val plaintext = RuntimeValueJson.encodeObject(document)
                    J.obj(J.parse(plaintext.toByteArray()))
                    File(target, "data/secure.json").writeText(plaintext)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { warnings += "${tool.metadata.name}：硬件密钥不可用，已归档加密记录，恢复后可能需要重新登录。" }
        }
        ExportSpillReader(snapshot.tasks).use { reader -> while (true) {
            currentCoroutineContext().ensureActive()
            val (task, result) = reader.nextTask() ?: break
            BackupDataCodec.write(File(target, "data/tasks/${BackupDataCodec.keyFile(task.taskId)}"), mapOf(
                "dataVersion" to 1, "taskId" to task.taskId, "versionCode" to task.versionCode, "key" to task.key, "operation" to task.operation.name,
                "specJson" to task.specJson, "periodic" to task.periodic, "intervalMinutes" to task.intervalMinutes,
                "state" to task.state.name, "createdAt" to task.createdAt, "updatedAt" to task.updatedAt, "runAttempt" to task.runAttempt,
                "result" to result?.let { mapOf("outcome" to it.outcome.name, "completedAt" to it.completedAt, "payloadJson" to it.payloadJson, "errorCode" to it.errorCode, "attemptCount" to it.attemptCount) },
            ))
        } }
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
        val inspector = ToolPackageInspectors.create(File(prepared.directory.parentFile, "inspection"), resourceProbe)
        val taskIds = mutableSetOf<String>()
        val plans = prepared.contents.tools.mapIndexed { index, tool ->
            val folder = File(prepared.directory, "tools/${tool.id}")
            val result = validatePackage(inspector, File(folder, "package.tbx"))
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
            val installed = current.firstOrNull { it.metadata.id == tool.id }?.currentVersion
            var keepInstalled = false
            if (skipped == null && installed != null) {
                // Hashing a large installed bundle must stop when the user cancels the restore preview.
                val installedKey = try {
                    runInterruptible(Dispatchers.IO) { InstalledPackageIdentity.signingKey(files, installed, resourceProbe) } to false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null to true
                }
                when (val identity = restoreIdentity(installed, installedKey.first, installedKey.second, result.signingKeyId, result.manifest.versionCode, result.integrityHash)) {
                    RestoreIdentity.Replace -> Unit
                    RestoreIdentity.KeepInstalled -> {
                        keepInstalled = true
                        warnings += "${tool.name}：包身份无法验证，保留本机版本 ${installed.version}，仅恢复备份中的数据。"
                    }
                    is RestoreIdentity.Skip -> skipped = identity.reason
                }
            }
            if (skipped == null) validatePresentation(metadata)
            val keys = mutableSetOf<String>()
            var controls = 0
            dataFiles(folder, "kv").forEach { file ->
                val row = J.read(file)
                if (J.int(row["dataVersion"]) != 1) { skipped = "部分数据格式不兼容，跳过整个工具，避免丢失无法识别的数据" }
                else {
                    val key = J.string(row["key"])
                    if (key.isBlank() || !keys.add(key) || file.name != BackupDataCodec.keyFile(key) || J.long(row["updatedAt"]) < 0) throw BackupException("INDEX")
                    J.string(row["valueJson"])
                    if (BackupDataCodec.hostControl(key)) controls++
                    BackupArchive.unknown(row, setOf("dataVersion", "key", "valueJson", "updatedAt"), warnings)
                }
            }
            File(folder, "data/secure.json").takeIf(File::exists)?.let { J.read(it) }
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
            // A kept install shows its own version as the result, not a downgrade to the backup's.
            RestoreToolPlan(tool.id, tool.name, if (keepInstalled) checkNotNull(installed).version else tool.version, installed?.version, skipped, keepInstalled)
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
                    BackupRuntimeGate.pauseAndDrain()
                    current.forEach { background.releaseRuntime(it.metadata.id) }
                    background.cancelAll(current.map { it.metadata.id })
                    // Notifications from the pre-restore versions would otherwise outlive the restore.
                    current.forEach { background.clearNotifications(it.metadata.id) }
                    cancelScheduledWork()
                    current.forEach { withRuntimeStorageQuiescent(it.metadata.id) { } }
                    repositories.mutations.run {
                        try {
                            progress("创建本机回滚快照", 8)
                            journal.begin()
                            // Package installs (unpacking, signature checks, file copies) and Keystore
                            // encryption run outside the Room transaction; the journal's checkpoint
                            // rolls back installed files, rows, settings and new keys on any failure.
                            val restores = preview.recoverable.mapIndexed { index, plan ->
                                currentCoroutineContext().ensureActive()
                                progress("恢复工具 ${index + 1}/${preview.recoverable.size}", 15 + index * 60 / maxOf(1, preview.recoverable.size))
                                val folder = File(preview.prepared.directory, "tools/${plan.id}")
                                // The preview approved this exact installed version (checked above), so a
                                // kept install is still the code whose identity could not be replaced.
                                if (!plan.keepInstalled) installPackage(folder, plan)
                                PreparedToolRestore(
                                    plan, folder, prepareSecure(folder, plan, warnings),
                                    dataFiles(folder, "tasks").mapNotNull { parseTask(it, plan.id, warnings) },
                                    keptVersion = if (plan.keepInstalled) preview.currentVersions.getValue(plan.id) else null,
                                )
                            }
                            progress("写入工具数据", 80)
                            database.transaction {
                                restores.forEach { restore ->
                                    currentCoroutineContext().ensureActive()
                                    restoreRows(restore)
                                }
                            }
                            val settings = J.read(File(preview.prepared.directory, "host/settings.json"))
                            BackupDataCodec.requireSuccess(repositories.settings.update { BackupDataCodec.settings(it, settings, warnings) })
                            // Room has committed its final installed set. Never prune layout from a
                            // transient projection emitted while individual tools are being restored.
                            BackupDataCodec.requireSuccess(CatalogLayoutRepository(repositories).reconcile())
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

    private suspend fun installPackage(folder: File, plan: RestoreToolPlan) {
        val imported = packages.importPackage(FilePackageInput(File(folder, "package.tbx")))
        val installed = if (imported is HostImportResult.ConfirmationRequired) {
            try { packages.confirmImport(imported.confirmation.id) }
            finally { withContext(NonCancellable) { packages.cancelImport(imported.confirmation.id) } }
        } else imported
        if (installed is HostImportResult.Failed) throw BackupException("PACKAGE_${installed.code}")
        if (installed !is HostImportResult.Installed || installed.toolId != plan.id) throw BackupException("PACKAGE")
    }

    /** Decides and encrypts secure data after the package (and its grants) is installed. */
    private suspend fun prepareSecure(folder: File, plan: RestoreToolPlan, warnings: MutableList<String>): SecureRestore {
        val secure = File(folder, "data/secure.json")
        if (secure.exists()) {
            val manifest = packages.installedManifest(plan.id) as? HostInstalledManifestResult.Found
            val granted = repositories.grants.observeGrants(plan.id).first().any { it.capability == "storage.secure" && it.granted }
            if (manifest?.manifest?.permissions?.any { it.capability == "storage.secure" } == true && granted) {
                val plaintext = secure.readText()
                J.obj(J.parse(plaintext.toByteArray()))
                val document = RuntimeValueJson.decodeObject(plaintext) ?: throw BackupException("INDEX")
                return SecureRestore.Replace(secureStore(plan.id).encryptRows(document))
            }
            warnings += "${plan.name}：当前未授权安全存储，本机安全数据未覆盖；开启权限后可再次恢复。"
            return SecureRestore.Keep
        }
        val opaqueSecure = dataFiles(folder, "kv").any { BackupDataCodec.secureKey(J.string(J.read(it)["key"])) }
        if (opaqueSecure) {
            warnings += "${plan.name}：加密记录仍在原备份中，但缺少可迁移密钥，本机安全数据未覆盖；可能需要重新登录。"
            return SecureRestore.Keep
        }
        return SecureRestore.Clear
    }

    /** Row writes only; runs inside the restore transaction. */
    private suspend fun restoreRows(restore: PreparedToolRestore) {
        val (plan, folder, secure, tasks, keptVersion) = restore
        BackupDataCodec.requireSuccess(repositories.keyValues.replace(plan.id, repositories.keyValues.keys(plan.id).filterNot(BackupDataCodec::secureKey).toSet(), emptyMap(), 0))
        dataFiles(folder, "kv").forEach { file ->
            val row = J.read(file)
            val key = J.string(row["key"])
            if (!BackupDataCodec.secureKey(key) && !BackupDataCodec.hostControl(key)) {
                BackupDataCodec.requireSuccess(repositories.keyValues.put(plan.id, key, J.string(row["valueJson"]), J.long(row["updatedAt"])))
            }
        }
        when (secure) {
            SecureRestore.Keep -> Unit
            SecureRestore.Clear -> BackupDataCodec.requireSuccess(secureStore(plan.id).clearRows())
            is SecureRestore.Replace -> BackupDataCodec.requireSuccess(secureStore(plan.id).replaceAll(secure.rows, System.currentTimeMillis()))
        }
        val metadata = J.read(File(folder, "metadata.json"))
        database.restorePresentation(plan.id, J.long(metadata["installedAt"]), keptVersion?.installedAt ?: J.long(metadata["versionInstalledAt"]), metadata["lastOpenedAt"]?.let(J::long), metadata["pinnedOrder"]?.let(J::int), metadata["categoryId"]?.let(J::string))
        BackupDataCodec.requireSuccess(repositories.backgroundTasks.deleteForTool(plan.id))
        tasks.forEach { (task, result) -> database.restoreTaskHistory(task, result) }
    }

    private fun secureStore(toolId: String) = RuntimeSecureStore(toolId, repositories.keyValues, AndroidKeyStoreCipher(toolId))

    private data class PreparedToolRestore(
        val plan: RestoreToolPlan,
        val folder: File,
        val secure: SecureRestore,
        val tasks: List<Pair<BackgroundTask, TaskRunResult?>>,
        /** The installed version that stays when only data is restored. */
        val keptVersion: ToolVersion? = null,
    )

    private sealed interface SecureRestore {
        data object Keep : SecureRestore
        data object Clear : SecureRestore
        class Replace(val rows: Map<String, String>) : SecureRestore
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
        require(task.taskId.matches(Regex("^[A-Za-z0-9._:-]+$")) && task.versionCode > 0)
        require(task.key.isNotBlank() && task.createdAt >= 0 && task.updatedAt >= task.createdAt && task.runAttempt >= 0)
        require((!task.periodic || (task.intervalMinutes ?: 0) >= 15))
        val result = row["result"]?.let { value ->
            val data = J.obj(value)
            BackupArchive.unknown(data, setOf("outcome", "completedAt", "payloadJson", "errorCode", "attemptCount"), warnings)
            val outcome = RunOutcome.entries.firstOrNull { it.name == data["outcome"] }
            if (outcome == null) { warnings += "未知任务结果已跳过。"; null }
            else TaskRunResult(id, outcome, J.long(data["completedAt"]), data["payloadJson"]?.let(J::string), data["errorCode"]?.let(J::string), J.int(data["attemptCount"]))
        }
        require(result == null || (result.completedAt >= 0 && result.attemptCount >= 0))
        return task to result
    }
    private suspend fun validatePackage(inspector: ToolPackageInspector, file: File): PackageValidationResult.Valid =
        when (val result = inspector.validate(FilePackageInput(file))) {
            is PackageValidationResult.Valid -> result
            is PackageValidationResult.Rejected -> throw BackupException("PACKAGE_${result.rejection.code.name}")
        }

    private fun validatePresentation(data: Map<String, Any?>) {
        require(J.long(data["installedAt"]) >= 0 && J.long(data["versionInstalledAt"]) >= 0)
        require(data["lastOpenedAt"] == null || J.long(data["lastOpenedAt"]) >= 0)
        require(data["pinnedOrder"] == null || J.int(data["pinnedOrder"]) >= 0)
        data["categoryId"]?.let(J::string)
    }
    private suspend fun activeTasks(tools: List<InstalledTool>): Set<String> = tools.flatMap { repositories.backgroundTasks.observeActiveTasks(it.metadata.id).first() }
        .map { it.taskId }.toSet()
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
                val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
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

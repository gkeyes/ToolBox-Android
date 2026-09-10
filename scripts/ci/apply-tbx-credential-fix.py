from pathlib import Path
r=Path.cwd()
def edit(path, old, new):
 p=r/path; s=p.read_text(); assert old in s, (path,old[:100]); p.write_text(s.replace(old,new,1))
p='tool-package/src/main/kotlin/io/toolbox/tool/packagekit/IntegrityVerifier.kt'
edit(p, '    ) {\n        val integrityBytes', '    ): String? {\n        val integrityBytes')
edit(p, '            return\n        }\n        val expectedHashes', '            return null\n        }\n        val expectedHashes')
edit(p, '        if (signatureBytes != null) verifySignature(signatureBytes, integrityBytes)', '        return signatureBytes?.let { verifySignature(it, integrityBytes) }')
edit(p, '    private fun verifySignature(bytes: ByteArray, integrityBytes: ByteArray) {', '    private fun verifySignature(bytes: ByteArray, integrityBytes: ByteArray): String {')
edit(p, '            reject(PackageRejectionCode.SIGNATURE_INVALID, "Ed25519 signature is invalid")\n        }', '            reject(PackageRejectionCode.SIGNATURE_INVALID, "Ed25519 signature is invalid")\n        }\n        return expectedKeyId')
p='tool-package/src/main/kotlin/io/toolbox/tool/packagekit/DefaultPackageInspector.kt'
edit(p, '            IntegrityVerifier.verify(extracted.metadata, extracted.hashes, limits)', '            val signingKeyId = IntegrityVerifier.verify(extracted.metadata, extracted.hashes, limits)')
edit(p, '                    temporaryDirectory = temporaryDirectory,', '                    temporaryDirectory = temporaryDirectory,\n                    signingKeyId = signingKeyId,')
p='tool-package/src/main/kotlin/io/toolbox/tool/packagekit/PackageInspection.kt'
edit(p, '    val temporaryDirectory: Path,\n)', '    val temporaryDirectory: Path,\n    val signingKeyId: String?,\n)')
p='tool-package/src/main/kotlin/io/toolbox/tool/packagekit/lifecycle/PackageLifecycle.kt'
edit(p, 'interface ToolStateCleanup {\n', '''interface ToolStateCleanup {
    /** Holds the host's runtime/storage barrier until publication, commit or rollback has finished. */
    suspend fun <T> withVersionReplacement(
        toolId: String,
        previousVersionCode: Int,
        nextVersionCode: Int,
        action: suspend () -> T,
    ): T {
        beforeVersionReplacement(toolId, previousVersionCode, nextVersionCode)
        return action()
    }

''')
edit(p, '    suspend fun afterVersionReplacement(toolId: String, previousVersionCode: Int, nextVersionCode: Int)', '''    /** Retryable metadata/cache invalidation only. Never erase data or stop a later runtime here. */
    suspend fun afterVersionReplacement(toolId: String, previousVersionCode: Int, nextVersionCode: Int)''')
edit(p, 'enum class PackageVersionConfirmationKind { SAME_VERSION, DOWNGRADE }', 'enum class PackageVersionConfirmationKind { SAME_VERSION, DOWNGRADE, UPDATE }')
edit(p, '    CONFIRMATION_EXPIRED,', '    CONFIRMATION_EXPIRED,\n    SIGNING_IDENTITY_CHANGED,')
p='tool-package/src/main/kotlin/io/toolbox/tool/packagekit/lifecycle/DefaultToolPackageManager.kt'
s=(r/p).read_text()
s=s.replace('import io.toolbox.core.data.InstallTransaction\n', 'import io.toolbox.core.data.InstallTransaction\nimport io.toolbox.core.data.InstalledTool\n')
s=s.replace('import java.nio.ByteBuffer\n','').replace('import java.nio.charset.StandardCharsets\n','').replace('import java.security.MessageDigest\n','')
s=s.replace('    filesRoot: Path,', '    private val filesRoot: Path,',1).replace('    limits: PackageLimits,', '    private val limits: PackageLimits,',1)
old='''        val previous = catalog.observeTool(manifest.id).first()
        if (
            previous != null &&
            manifest.versionCode <= previous.currentVersion.versionCode &&
            previous.currentVersion != confirmedCurrentVersion
        ) {'''
new='''        val previous = catalog.observeTool(manifest.id).first()
        // A confirmation authorizes this private prepared package against this exact old install,
        // including its integrity hash and installedAt, not just a reusable version number.
        if (confirmedCurrentVersion != null && previous?.currentVersion != confirmedCurrentVersion) {
            discardPrepared(prepared)?.let { return PackageInstallResult.Failed(it) }
            return failed(PackageOperationFailureCode.CONFIRMATION_EXPIRED, "The installed package changed; select the update again")
        }
        val previousSigningKey = if (previous == null) null else try {
            runInterruptible { installedSigningKey(filesRoot, previous.currentVersion, limits) }
        } catch (cancelled: CancellationException) {
            discardPrepared(prepared)
            throw cancelled
        } catch (_: Exception) {
            discardPrepared(prepared)?.let { return PackageInstallResult.Failed(it) }
            return failed(PackageOperationFailureCode.STORAGE_FAILURE, "The installed package identity could not be verified; existing data was preserved")
        }
        if (previousSigningKey != null && previousSigningKey != prepared.signingKeyId) {
            discardPrepared(prepared)?.let { return PackageInstallResult.Failed(it) }
            return failed(PackageOperationFailureCode.SIGNING_IDENTITY_CHANGED, "The update is not signed by the installed package key")
        }
        if (
            previous != null &&
            (manifest.versionCode <= previous.currentVersion.versionCode || previousSigningKey == null) &&
            previous.currentVersion != confirmedCurrentVersion
        ) {'''
assert old in s;s=s.replace(old,new,1)
old='''                    kind = if (manifest.versionCode == previous.currentVersion.versionCode) {
                        PackageVersionConfirmationKind.SAME_VERSION
                    } else {
                        PackageVersionConfirmationKind.DOWNGRADE
                    },'''
new='''                    kind = when {
                        manifest.versionCode == previous.currentVersion.versionCode -> PackageVersionConfirmationKind.SAME_VERSION
                        manifest.versionCode < previous.currentVersion.versionCode -> PackageVersionConfirmationKind.DOWNGRADE
                        else -> PackageVersionConfirmationKind.UPDATE
                    },'''
assert old in s;s=s.replace(old,new,1)
start=s.index('        val replacingSameVersion = ')
end=s.index('        if (previous != null) {\n            try {\n                runInterruptible {\n                    storage.recordReplacementCleanup(',start)
s=s[:start]+'''        return try {
            if (previous == null) {
                publishAndCommit(prepared, transaction, null, cleanup)
            } else {
                cleanup.withVersionReplacement(manifest.id, previous.currentVersion.versionCode, manifest.versionCode) {
                    publishAndCommit(prepared, transaction, previous, cleanup)
                }
            }
        } catch (cancelled: CancellationException) {
            failAndClean(transaction, null, "CANCELLED")
            throw cancelled
        } catch (_: Exception) {
            failAndClean(transaction, null, "RUNTIME_RELEASE_FAILED")
            failed(PackageOperationFailureCode.CLEANUP_FAILURE, "Running tool could not be stopped for update")
        }
    }

    private suspend fun publishAndCommit(
        prepared: PreparedPackage,
        transaction: InstallTransaction,
        previous: InstalledTool?,
        cleanup: ToolStateCleanup,
    ): PackageInstallResult {
        val manifest = prepared.manifest
        val transactionId = transaction.id
'''+s[end:]
start=s.index('        if (previous != null && !replacingSameVersion) {')
end=s.index('        when (val marking = ',start)
s=s[:start]+s[end:]
s=s.replace('integrityHash = aggregateHash(prepared.fileHashes),','integrityHash = aggregatePackageHash(prepared.fileHashes),')
start=s.index('    private fun aggregateHash(')
end=s.index('    private fun dataFailure(',start)
s=s[:start]+s[end:]
(r/p).write_text(s)
p='tool-runtime/src/main/kotlin/io/toolbox/tool/runtime/RuntimeProfileManager.kt'
edit(p, 'interface RuntimeDataCleaner {\n', '''interface RuntimeDataCleaner {
    /** Exclusive stopped-runtime mutation; unlike clearThenRun, preserves browsing data and mode. */
    suspend fun <T> withPreservedData(toolId: String, action: suspend () -> T): T

''')
edit(p, '    override suspend fun <T> clearThenRun(\n', '''    override suspend fun <T> withPreservedData(toolId: String, action: suspend () -> T): T {
        val acquired = withContext(Dispatchers.Main.immediate) { RuntimeWebViewLifecycle.beginCleanup(toolId) }
        check(acquired) { "Tool runtime is still in use" }
        try {
            return action()
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { RuntimeWebViewLifecycle.finishCleanup(toolId) }
        }
    }

    override suspend fun <T> clearThenRun(
''')
p='app/src/main/kotlin/io/toolbox/host/HostDependencies.kt'
edit(p, '    val runtimeDataCleaner: RuntimeDataCleaner = object : RuntimeDataCleaner {\n', '''    val runtimeDataCleaner: RuntimeDataCleaner = object : RuntimeDataCleaner {
        override suspend fun <T> withPreservedData(toolId: String, action: suspend () -> T): T =
            deferredRuntimeProfileManager.value.withPreservedData(toolId, action)

''')
p='app/src/main/kotlin/io/toolbox/host/ProductionHostOperations.kt'
edit(p, 'import io.toolbox.host.runtime.clearRuntimeSecureStorage\n', 'import io.toolbox.host.runtime.clearRuntimeSecureStorage\nimport io.toolbox.host.runtime.withRuntimeStorageQuiescent\n')
edit(p, '    private val cleanup = object : ToolStateCleanup {\n', '''    private val cleanup = object : ToolStateCleanup {
        override suspend fun <T> withVersionReplacement(
            toolId: String,
            previousVersionCode: Int,
            nextVersionCode: Int,
            action: suspend () -> T,
        ): T {
            beforeVersionReplacement(toolId, previousVersionCode, nextVersionCode)
            return runtimeDataCleaner.withPreservedData(toolId) {
                withRuntimeStorageQuiescent(toolId, action)
            }
        }

''')
edit(p, '            clearRuntimeState(toolId, removeShortcut = false)', '''            // Old replacement markers also arrive here during recovery. This must remain
            // non-destructive and must not stop a new runtime started since the commit.
            invalidateToolIcon(toolId)''')
edit(p, '                    PackageVersionConfirmationKind.DOWNGRADE -> HostImportConfirmationKind.DOWNGRADE', '                    PackageVersionConfirmationKind.DOWNGRADE -> HostImportConfirmationKind.DOWNGRADE\n                    PackageVersionConfirmationKind.UPDATE -> HostImportConfirmationKind.UPDATE')
edit(p, '        "CONFIRMATION_EXPIRED" -> "安装确认已失效，请重新选择工具包。"', '        "CONFIRMATION_EXPIRED" -> "安装确认已失效，请重新选择工具包。"\n        "SIGNING_IDENTITY_CHANGED" -> "更新包的签名身份与原工具不同，已保留原工具和数据。请使用原作者签名的更新包。"')
p='app/src/main/kotlin/io/toolbox/host/HostOperations.kt'
edit(p, 'internal enum class HostImportConfirmationKind { SAME_VERSION, DOWNGRADE }', 'internal enum class HostImportConfirmationKind { SAME_VERSION, DOWNGRADE, UPDATE }')
p='app/src/main/kotlin/io/toolbox/host/ui/HostCatalogScreens.kt'
edit(p, '            HostImportConfirmationKind.DOWNGRADE -> "安装较低版本？"', '            HostImportConfirmationKind.DOWNGRADE -> "安装较低版本？"\n            HostImportConfirmationKind.UPDATE -> "更新并保留工具数据？"')
old='''            if (it.kind == HostImportConfirmationKind.SAME_VERSION) {
                "${it.toolName} 当前为 $installed，待安装为 $incoming，两者 versionCode 相同。继续会覆盖现有工具文件，并停止其运行和后台任务；普通存储与仍有效的权限选择会保留。"
            } else {
                "${it.toolName} 当前为 $installed，待安装为 $incoming。较低版本可能无法读取新版数据；继续会停止其运行和后台任务，普通存储与仍有效的权限选择会保留。"
            }'''
new='''            val replacement = when (it.kind) {
                HostImportConfirmationKind.SAME_VERSION -> "两者 versionCode 相同，将覆盖现有工具文件。"
                HostImportConfirmationKind.DOWNGRADE -> "较低版本可能无法读取新版数据。"
                HostImportConfirmationKind.UPDATE -> "无法通过原工具的签名确认此次更新的身份。"
            }
            "${it.toolName} 当前为 $installed，待安装为 $incoming。$replacement 继续会停止旧运行和后台任务，并允许这份更新使用已保存的登录信息、设置与仍有效的权限。原来关闭的权限不会开启。仅在信任此包来源时继续。"'''
edit(p,old,new)
p='app/src/main/kotlin/io/toolbox/host/runtime/HostRuntimeBridgeProvider.kt'
edit(p, '                canAccess = { grantState.isGranted(runtime.toolId, ToolBoxCapabilityId.STORAGE_SECURE) },', '''                canAccess = {
                    grantState.currentVersionCode(runtime.toolId) == runtime.versionCode &&
                        grantState.isGranted(runtime.toolId, ToolBoxCapabilityId.STORAGE_SECURE)
                },''')
edit(p, '/** Revocation denies queued callers and drains the already admitted ordinary operation. */', '''/** No runtime is admitted while the host holds these locks for a version replacement. */
internal suspend fun <T> withRuntimeStorageQuiescent(toolId: String, action: suspend () -> T): T =
    ToolRuntimeStorageLocks.mutexFor(toolId, ToolStorageNamespace.Standard).withLock {
        ToolRuntimeStorageLocks.mutexFor(toolId, ToolStorageNamespace.Secure).withLock { action() }
    }

/** Revocation denies queued callers and drains the already admitted ordinary operation. */''')
edit(p, 'cipher.init(Cipher.ENCRYPT_MODE, key())', 'cipher.init(Cipher.ENCRYPT_MODE, key(createIfMissing = true))')
edit(p, 'cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))', 'cipher.init(Cipher.DECRYPT_MODE, key(createIfMissing = false), GCMParameterSpec(GCM_TAG_BITS, iv))')
edit(p, '    private fun key(): SecretKey {', '    private fun key(createIfMissing: Boolean): SecretKey {')
edit(p, '        (store.getKey(alias, null) as? SecretKey)?.let { return it }\n        val generator', '''        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!createIfMissing) throw RuntimeHandlerException(
            RuntimeRpcErrorCode.INTERNAL_ERROR,
            "Secure storage key is missing; existing ciphertext was not changed",
        )
        val generator''')
p=r/'app/src/main/kotlin/io/toolbox/host/runtime/RuntimeSessionManager.kt'
s=p.read_text().replace('import kotlinx.coroutines.CancellationException\n','import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.cancelAndJoin\nimport kotlinx.coroutines.currentCoroutineContext\n',1)
s=s.replace('private val openingTools = mutableSetOf<String>()','private val openingTools = mutableMapOf<String, Job>()',1)
s=s.replace('    suspend fun releaseTool(toolId: String) = withContext(Dispatchers.Main.immediate) {\n        stopTool(toolId)', '''    suspend fun releaseTool(toolId: String) = withContext(Dispatchers.Main.immediate) {
        // An IO preparation may not own a WebView yet. Drain it before a same-version
        // replacement can publish new files or an old opener can acquire the new profile.
        openingTools[toolId]?.cancelAndJoin()
        stopTool(toolId)''',1)
s=s.replace('''        if (hosts[toolId] != null || !openingTools.add(toolId)) {
            restoreReason?.let { reason -> hosts[toolId]?.emitRestore(reason) }
            return
        }
        stateFlow(toolId).value = RuntimeUiState.Loading''','''        if (hosts[toolId] != null || toolId in openingTools) {
            restoreReason?.let { reason -> hosts[toolId]?.emitRestore(reason) }
            return
        }
        val openingJob = checkNotNull(currentCoroutineContext()[Job])
        openingTools[toolId] = openingJob
        stateFlow(toolId).value = RuntimeUiState.Loading''',1)
s=s.replace('            openingTools -= toolId\n','            if (openingTools[toolId] === openingJob) openingTools.remove(toolId)\n',1)
p.write_text(s)
p=r/'tool-package/src/main/kotlin/io/toolbox/tool/packagekit/lifecycle/DefaultToolPackageManager.kt'
s=p.read_text()
start=s.index('        val manifest = prepared.manifest',s.index('    private suspend fun publishAndCommit('))
end=s.index('\n    private suspend fun completeCommittedInstall(',start)
body=s[start:end]
assert body.endswith('    }\n')
body=body[:-6]
wrapped='        try {\n'+''.join('    '+line+'\n' for line in body.rstrip('\n').split('\n'))+'''        } catch (cancelled: CancellationException) {
            // Rollback is still inside the runtime/storage barrier, including Room cancellation.
            failAndClean(transaction, null, "CANCELLED")
            throw cancelled
        } catch (_: Exception) {
            failAndClean(transaction, null, "COMMIT_FAILED")
            return failed(PackageOperationFailureCode.DATA_FAILURE, "Package commit failed; existing data was preserved")
        }
    }
'''
s=s[:start]+wrapped+s[end:]
needle='''    ) = withContext(NonCancellable + ioDispatcher) {
        runCatching { transactions.fail(transaction.id, now(), failureCode) }'''
assert needle in s
s=s.replace(needle,'''    ) = withContext(NonCancellable + ioDispatcher) {
        // Cancellation may be delivered after Room committed. Never roll back a committed
        // install (or an uncertain DB result); its owner/cleanup markers drive recovery.
        when (val committed = lifecycle.findCommittedInstall(transaction.id)) {
            is DataResult.Failure -> return@withContext
            is DataResult.Success -> if (committed.value != null) return@withContext
        }
        runCatching { transactions.fail(transaction.id, now(), failureCode) }''',1)
p.write_text(s)
p=r/'tool-package/src/test/kotlin/io/toolbox/tool/packagekit/lifecycle/DirectPackageLifecycleTest.kt'
s=p.read_text().replace('class DirectPackageLifecycleTest {','''class DirectPackageLifecycleTest {
    // Continuous signed versions share one ephemeral fixture identity; never a production key.
    private val signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
''',1)
s=s.replace('        signed: Boolean = false,','        signed: Boolean = true,\n        signingPair: java.security.KeyPair = signer,',1)
s=s.replace('signature(integrity, corruptSignature)', 'signature(integrity, corruptSignature, signingPair)',1)
s=s.replace('    private fun signature(integrity: ByteArray, corrupt: Boolean): String {\n        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()', '    private fun signature(integrity: ByteArray, corrupt: Boolean, pair: java.security.KeyPair): String {',1)
insert='''    @Test
    fun unsignedUpdateRequiresCandidateConfirmationWithoutChangingExistingChoices() = runBlocking {
        val root = Files.createTempDirectory("tool-package-unsigned-update")
        try {
            val data = InMemoryCoreData.create()
            val manager = ToolPackageManagers.create(root.toFile(), data.catalog, data.lifecycle, data.installs)
            assertTrue(manager.importAndInstall(ByteInput("v1.tbx", packageBytes(signed = false))) is PackageInstallResult.Installed)
            data.keyValues.put(TOOL_ID, "retained", "value", 1)
            data.grants.put(PermissionGrant(TOOL_ID, "network", true, 2))
            data.grants.put(PermissionGrant(TOOL_ID, "storage", false, 3))
            val choices = data.grants.observeGrants(TOOL_ID).first()
            val pending = manager.importAndInstall(ByteInput("v2.tbx", packageBytes(versionCode = 2, signed = false)))
                as PackageInstallResult.ConfirmationRequired
            assertEquals(PackageVersionConfirmationKind.UPDATE, pending.confirmation.kind)
            assertEquals(1, data.catalog.observeTool(TOOL_ID).first()!!.currentVersion.versionCode)
            assertFalse(Files.exists(root.resolve("miniapps/$TOOL_ID/versions/2")))
            assertEquals(choices, data.grants.observeGrants(TOOL_ID).first())
            assertEquals(PackageInstallResult.Installed(TOOL_ID, 2, true), manager.confirmInstall(pending.confirmation.id))
            assertEquals(choices, data.grants.observeGrants(TOOL_ID).first())
            assertEquals("value", data.keyValues.observe(TOOL_ID, "retained").first()!!.valueJson)
            assertTrue(manager.confirmInstall(pending.confirmation.id) is PackageInstallResult.Failed)
        } finally { deleteTree(root) }
    }

    @Test
    fun signingChangesAndSignedToUnsignedDoNotReplaceExistingInstallation() = runBlocking {
        val root = Files.createTempDirectory("tool-package-signing-change")
        try {
            val data = InMemoryCoreData.create()
            val manager = ToolPackageManagers.create(root.toFile(), data.catalog, data.lifecycle, data.installs)
            assertTrue(manager.importAndInstall(ByteInput("v1.tbx", packageBytes())) is PackageInstallResult.Installed)
            val old = data.catalog.observeTool(TOOL_ID).first()
            data.keyValues.put(TOOL_ID, "retained", "value", 1)
            for (bytes in listOf(
                packageBytes(versionCode = 2, signed = false),
                packageBytes(versionCode = 2, signingPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()),
            )) {
                val result = manager.importAndInstall(ByteInput("changed.tbx", bytes)) as PackageInstallResult.Failed
                assertEquals(PackageOperationFailureCode.SIGNING_IDENTITY_CHANGED, result.failure.code)
                assertEquals(old, data.catalog.observeTool(TOOL_ID).first())
                assertEquals("value", data.keyValues.observe(TOOL_ID, "retained").first()!!.valueJson)
                assertNoTransientFiles(root)
            }
            assertEquals(PackageInstallResult.Installed(TOOL_ID, 2, true), manager.importAndInstall(ByteInput("same-signer.tbx", packageBytes(versionCode = 2))))
        } finally { deleteTree(root) }
    }

    @Test
    fun confirmationRejectsChangedOldInstallAndTamperedPreparedCandidate() = runBlocking {
        val root = Files.createTempDirectory("tool-package-bound-confirmation")
        try {
            val data = InMemoryCoreData.create()
            val manager = ToolPackageManagers.create(root.toFile(), data.catalog, data.lifecycle, data.installs)
            manager.importAndInstall(ByteInput("v1.tbx", packageBytes(signed = false)))
            val pending = manager.importAndInstall(ByteInput("v2.tbx", packageBytes(versionCode = 2, signed = false)))
                as PackageInstallResult.ConfirmationRequired
            // Even same versionCode with different installedAt/hash is a different old install.
            val old = data.catalog.observeTool(TOOL_ID).first()!!
            val tx = "external-update"
            data.installs.begin(InstallTransaction(tx, TOOL_ID, 1, InstallTransactionState.PREPARING, 9, 9))
            data.lifecycle.commitInstall(CatalogInstallAttempt(tx, old.metadata,
                old.currentVersion.copy(installedAt = 9), data.grants.observeGrants(TOOL_ID).first()))
            val stale = manager.confirmInstall(pending.confirmation.id) as PackageInstallResult.Failed
            assertEquals(PackageOperationFailureCode.CONFIRMATION_EXPIRED, stale.failure.code)
            val next = manager.importAndInstall(ByteInput("v2.tbx", packageBytes(versionCode = 2, signed = false)))
                as PackageInstallResult.ConfirmationRequired
            Files.walk(root.resolve("miniapps/.imports")).use { paths ->
                val html = paths.filter { it.fileName.toString() == "index.html" }.findFirst().orElseThrow()
                Files.writeString(html, "<!doctype html><html><body>altered</body></html>")
            }
            assertTrue(manager.confirmInstall(next.confirmation.id) is PackageInstallResult.Failed)
            assertEquals(1, data.catalog.observeTool(TOOL_ID).first()!!.currentVersion.versionCode)
            assertNoTransientFiles(root)
        } finally { deleteTree(root) }
    }

'''
assert '    private fun packageBytes(' in s
s=s.replace('    private fun packageBytes(',insert+'    private fun packageBytes(',1)
p.write_text(s)
p=r/'app/build.gradle.kts';s=p.read_text();assert 'versionCode = 29' in s;s=s.replace('versionCode = 29','versionCode = 30').replace('versionName = "0.7.5"','versionName = "0.7.6"');p.write_text(s)
p=r/'.github/workflows/android.yml';s=p.read_text().replace('0.7.5','0.7.6').replace("versionCode='29'", "versionCode='30'").replace('VERSION_CODE=29','VERSION_CODE=30');p.write_text(s)
p=r/'sdk/help/manual.md';s=p.read_text();old='普通存储在工具更新后保留；安全存储在关闭其授权、工具更新或删除时会清理，更新后需要重新输入 Token。临时文件令牌和 sessionId 不能作为可跨版本复用的数据保存。';new='ToolBox 0.7.6 起，同身份更新保留普通存储、安全存储密文及原密钥和可安全继承的 WebView 数据。未签名工具更新须在宿主确认后才能使用旧登录信息和仍有效的授权；原来关闭的权限不会开启。已签名工具的签名主体变化或变为未签名时拒绝替换。主动关闭安全存储授权或删除工具仍会清理密钥和密文；此前已删除的数据不能恢复。请先更新宿主，再更新工具包。临时文件令牌、sessionId 和后台运行实例不能跨版本复用。';assert old in s;s=s.replace(old,new);p.write_text(s)
p=r/'docs/ToolBox_Android_技术方案.md';s=p.read_text();s+='''
## TBX 更新数据保留（0.7.6）

版本替换在宿主释放旧运行时（包括正在创建的实例）后，持有独占 profile 与普通／安全存储屏障，完成文件发布、Room 提交或提交前回滚。更新收尾仅失效图标缓存；卸载和用户主动关闭安全存储继续执行删除。现存三行 replacement-cleanup 标记重放同一非破坏性收尾，不停止提交后新建的运行时。

从目录记录 hash 锁定的旧 bundle 验证原 Ed25519 主体，不建立发布者信任库。签名一致可按版本策略更新；旧包未签名时复用导入确认，将批准绑定到私有候选包及完整旧 ToolVersion。已签名包更换主体或变为未签名均拒绝原位替换。确认不会通过关闭再开启权限实现；用户关闭的权限保持关闭，新增能力默认关闭。

存储逻辑 key、命名空间、origin、profile 名和 KeyStore alias 不变，不修改 Room schema。解密仅取已有密钥；缺密钥或密文损坏报错且不创建替代密钥或覆盖记录。旧版本已删除的原密钥／密文不能通过此修复恢复。

回归入口：`TbxUpgradePersistenceTest`、`TbxUpgradeRuntimeTest`、`TbxUpgradeProcessTest`、`DirectPackageLifecycleTest`。前者保留了在 48bedb53 宿主实现上运行失败的原密文保留断言；后续同一断言须通过。过程重启仅由 GitHub 隔离模拟器分两次 instrumentation 验证，中间不重装或清数据。实际运行结果以 CI 产物为准，编译不等于执行，不包含截图或真机验收。
''';p.write_text(s)

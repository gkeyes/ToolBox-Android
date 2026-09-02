package io.toolbox.tool.packagekit.lifecycle

import io.toolbox.core.data.CatalogInstallAttempt
import io.toolbox.core.data.CatalogLifecycleRepository
import io.toolbox.core.data.CommitInstallOutcome
import io.toolbox.core.data.CommittedInstall
import io.toolbox.core.data.DataResult
import io.toolbox.core.data.DeleteToolCatalogOutcome
import io.toolbox.core.data.ToolKvValue
import io.toolbox.core.data.memory.InMemoryCoreData
import io.toolbox.tool.packagekit.PackageInput
import io.toolbox.tool.packagekit.PackageRejectionCode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectPackageLifecycleTest {
    @Test
    fun validUtf8EntrySplitAtSniffBoundaryIsAccepted() = runBlocking {
        val root = Files.createTempDirectory("tool-package-utf8-boundary")
        try {
            val opening = "<!doctype html><html><body>"
            val boundaryHtml = (
                opening + "a".repeat(4095 - opening.toByteArray().size) + "中</body></html>"
            ).toByteArray()
            assertEquals(0xe4.toByte(), boundaryHtml[4095])
            val repositories = InMemoryCoreData.create()
            val manager = ToolPackageManagers.create(
                privateFilesDirectory = root.toFile(),
                catalog = repositories.catalog,
                lifecycle = repositories.lifecycle,
                transactions = repositories.installs,
            )

            val result = manager.importAndInstall(
                ByteInput("utf8-boundary.tbx", packageBytes(entryHtml = boundaryHtml)),
            )

            assertEquals(PackageInstallResult.Installed(TOOL_ID, 1, false), result)
            assertNoTransientFiles(root)
            assertNoPendingCleanup(root)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun standalonePackageUnderTestPassesProductionImportLifecycle() = runBlocking {
        val packagePath = System.getenv("TOOLBOX_PACKAGE_UNDER_TEST")?.let(Path::of)
            ?: return@runBlocking
        val expectedVersionCode = requireNotNull(System.getenv("TOOLBOX_PACKAGE_EXPECTED_VERSION_CODE")) {
            "TOOLBOX_PACKAGE_EXPECTED_VERSION_CODE is required for the standalone package gate."
        }.toInt()
        val root = Files.createTempDirectory("tool-package-standalone")
        try {
            val repositories = InMemoryCoreData.create()
            val manager = ToolPackageManagers.create(
                privateFilesDirectory = root.toFile(),
                catalog = repositories.catalog,
                lifecycle = repositories.lifecycle,
                transactions = repositories.installs,
                hostVersion = "0.3.3",
            )

            val result = manager.importAndInstall(FileInput(packagePath))

            assertEquals(
                PackageInstallResult.Installed("io.toolbox.githubactionswatcher", expectedVersionCode, false),
                result,
            )
            assertNoTransientFiles(root)
            assertNoPendingCleanup(root)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun importUpdateVersionGateAndUninstallAreOneStepAndAtomic() = runBlocking {
        val root = Files.createTempDirectory("tool-package-lifecycle")
        try {
            val repositories = InMemoryCoreData.create()
            val manager = ToolPackageManagers.create(
                privateFilesDirectory = root.toFile(),
                catalog = repositories.catalog,
                lifecycle = repositories.lifecycle,
                transactions = repositories.installs,
            )
            val first = manager.importAndInstall(ByteInput("v1.tbx", packageBytes(versionCode = 1)))
            assertEquals(PackageInstallResult.Installed(TOOL_ID, 1, false), first)
            assertTrue(repositories.keyValues.put(TOOL_ID, "draft", "1", 1) is DataResult.Success)

            val released = mutableListOf<String>()
            val replaced = mutableListOf<Pair<Int, Int>>()
            val cleanup = object : ToolStateCleanup {
                override suspend fun beforeVersionReplacement(
                    toolId: String,
                    previousVersionCode: Int,
                    nextVersionCode: Int,
                ) {
                    assertEquals(1, repositories.catalog.observeTool(TOOL_ID).first()?.currentVersion?.versionCode)
                    assertTrue(Files.isDirectory(root.resolve("miniapps/$TOOL_ID/versions/1")))
                    released += "update:$previousVersionCode:$nextVersionCode"
                }

                override suspend fun afterVersionReplacement(toolId: String, previousVersionCode: Int, nextVersionCode: Int) {
                    assertEquals(TOOL_ID, toolId)
                    assertEquals(2, repositories.catalog.observeTool(TOOL_ID).first()?.currentVersion?.versionCode)
                    replaced += previousVersionCode to nextVersionCode
                }

                override suspend fun beforeUninstall(toolId: String) {
                    assertEquals(2, repositories.catalog.observeTool(toolId).first()?.currentVersion?.versionCode)
                    assertTrue(Files.isDirectory(root.resolve("miniapps/$TOOL_ID/versions/2")))
                    released += "uninstall"
                }

                override suspend fun afterUninstall(toolId: String) {
                    assertNull(repositories.catalog.observeTool(toolId).first())
                }
            }
            val second = manager.importAndInstall(ByteInput("v2.tbx", packageBytes(versionCode = 2, signed = true)), cleanup)
            assertEquals(PackageInstallResult.Installed(TOOL_ID, 2, true), second)
            assertEquals(listOf("update:1:2"), released)
            assertEquals(listOf(1 to 2), replaced)
            assertEquals(ToolKvValue("draft", "1", 1), repositories.keyValues.observe(TOOL_ID, "draft").first())

            val duplicate = manager.importAndInstall(ByteInput("same.tbx", packageBytes(versionCode = 2)))
            assertTrue(
                duplicate is PackageInstallResult.Failed &&
                    duplicate.failure.code == PackageOperationFailureCode.VERSION_NOT_NEWER,
            )
            assertEquals(2, repositories.catalog.observeTool(TOOL_ID).first()?.currentVersion?.versionCode)

            assertEquals(PackageUninstallResult.Uninstalled(TOOL_ID), manager.uninstall(TOOL_ID, cleanup))
            assertEquals(listOf("update:1:2", "uninstall"), released)
            assertNull(repositories.catalog.observeTool(TOOL_ID).first())
            assertNull(repositories.keyValues.observe(TOOL_ID, "draft").first())
            assertFalse(Files.exists(root.resolve("miniapps/$TOOL_ID")))
            assertNoTransientFiles(root)
            assertNoPendingCleanup(root)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun rejectedSecurityMatrixLeavesNoCatalogOrFiles() = runBlocking {
        val cases = listOf(
            "zip-slip" to zip(mapOf("../escape.html" to HTML)),
            "nested-archive" to packageBytes(extra = mapOf("payload.bin" to byteArrayOf(0x50, 0x4b, 0x03, 0x04))),
            "bad-integrity" to packageBytes(corruptIntegrity = true),
            "bad-signature" to packageBytes(signed = true, corruptSignature = true),
            "bad-utf8-short" to packageBytes(entryHtml = HTML + byteArrayOf(0xe4.toByte())),
            "bad-utf8-exact-boundary" to packageBytes(
                entryHtml = HTML + "a".repeat(4095 - HTML.size).toByteArray() + byteArrayOf(0xe4.toByte()),
            ),
        )
        cases.forEach { (name, bytes) ->
            val root = Files.createTempDirectory("tool-package-rejected")
            try {
                val repositories = InMemoryCoreData.create()
                val manager = ToolPackageManagers.create(
                    privateFilesDirectory = root.toFile(),
                    catalog = repositories.catalog,
                    lifecycle = repositories.lifecycle,
                    transactions = repositories.installs,
                )
                val result = manager.importAndInstall(ByteInput("$name.tbx", bytes))
                assertTrue("$name must be rejected", result is PackageInstallResult.Rejected)
                if (name == "bad-signature") {
                    assertEquals(
                        PackageRejectionCode.SIGNATURE_INVALID,
                        (result as PackageInstallResult.Rejected).rejection.code,
                    )
                }
                if (name.startsWith("bad-utf8-")) {
                    assertEquals(
                        PackageRejectionCode.ENTRY_MIME_INVALID,
                        (result as PackageInstallResult.Rejected).rejection.code,
                    )
                }
                assertTrue(repositories.catalog.observeTools().first().isEmpty())
                assertNoTransientFiles(root)
                assertNoPendingCleanup(root)
            } finally {
                deleteTree(root)
            }
        }
    }

    @Test
    fun failedCatalogCommitLeavesPreviousVersionAndStateUntouched() = runBlocking {
        val root = Files.createTempDirectory("tool-package-commit-failure")
        try {
            val repositories = InMemoryCoreData.create()
            val initialManager = ToolPackageManagers.create(
                privateFilesDirectory = root.toFile(),
                catalog = repositories.catalog,
                lifecycle = repositories.lifecycle,
                transactions = repositories.installs,
            )
            assertEquals(
                PackageInstallResult.Installed(TOOL_ID, 1, false),
                initialManager.importAndInstall(ByteInput("v1.tbx", packageBytes(versionCode = 1))),
            )
            assertTrue(repositories.keyValues.put(TOOL_ID, "draft", "1", 1) is DataResult.Success)

            var replacementCleanupCalled = false
            val cleanup = object : ToolStateCleanup {
                override suspend fun afterVersionReplacement(toolId: String, previousVersionCode: Int, nextVersionCode: Int) {
                    replacementCleanupCalled = true
                }

                override suspend fun afterUninstall(toolId: String) = Unit
            }
            val failingManager = ToolPackageManagers.create(
                privateFilesDirectory = root.toFile(),
                catalog = repositories.catalog,
                lifecycle = FailingCommitLifecycle(repositories.lifecycle),
                transactions = repositories.installs,
            )

            val result = failingManager.importAndInstall(ByteInput("v2.tbx", packageBytes(versionCode = 2)), cleanup)

            assertTrue(
                result is PackageInstallResult.Failed &&
                    result.failure.code == PackageOperationFailureCode.DATA_FAILURE,
            )
            assertFalse(replacementCleanupCalled)
            assertEquals(1, repositories.catalog.observeTool(TOOL_ID).first()?.currentVersion?.versionCode)
            assertEquals(ToolKvValue("draft", "1", 1), repositories.keyValues.observe(TOOL_ID, "draft").first())
            assertTrue(Files.isDirectory(root.resolve("miniapps/$TOOL_ID/versions/1")))
            assertFalse(Files.exists(root.resolve("miniapps/$TOOL_ID/versions/2")))
            assertNoTransientFiles(root)
            assertNoPendingCleanup(root)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun committedUpdateRetriesExternalCleanupWithoutReportingInstallationFailure() = runBlocking {
        val root = Files.createTempDirectory("tool-package-cleanup-retry")
        try {
            val repositories = InMemoryCoreData.create()
            val manager = ToolPackageManagers.create(
                privateFilesDirectory = root.toFile(),
                catalog = repositories.catalog,
                lifecycle = repositories.lifecycle,
                transactions = repositories.installs,
            )
            assertEquals(
                PackageInstallResult.Installed(TOOL_ID, 1, false),
                manager.importAndInstall(ByteInput("v1.tbx", packageBytes(versionCode = 1))),
            )
            var attempts = 0
            val cleanup = object : ToolStateCleanup {
                override suspend fun afterVersionReplacement(toolId: String, previousVersionCode: Int, nextVersionCode: Int) {
                    attempts += 1
                    if (attempts == 1) error("temporary cleanup failure")
                }

                override suspend fun afterUninstall(toolId: String) = Unit
            }

            val update = manager.importAndInstall(ByteInput("v2.tbx", packageBytes(versionCode = 2)), cleanup)

            assertEquals(PackageInstallResult.Installed(TOOL_ID, 2, true), update)
            assertEquals(2, repositories.catalog.observeTool(TOOL_ID).first()?.currentVersion?.versionCode)
            assertEquals(1, attempts)
            assertTrue(Files.list(root.resolve("miniapps/.lifecycle/replacement-cleanup")).use { it.findAny().isPresent })

            assertEquals(PackageRecoveryResult.Recovered, manager.recoverPendingMutations(cleanup))
            assertEquals(2, attempts)
            assertNoPendingCleanup(root)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun failedCatalogDeletionLeavesFilesAndExternalStateUntouched() = runBlocking {
        val root = Files.createTempDirectory("tool-package-delete-failure")
        try {
            val repositories = InMemoryCoreData.create()
            val initialManager = ToolPackageManagers.create(
                privateFilesDirectory = root.toFile(),
                catalog = repositories.catalog,
                lifecycle = repositories.lifecycle,
                transactions = repositories.installs,
            )
            assertEquals(
                PackageInstallResult.Installed(TOOL_ID, 1, false),
                initialManager.importAndInstall(ByteInput("v1.tbx", packageBytes(versionCode = 1))),
            )
            var uninstallCleanupCalled = false
            val cleanup = object : ToolStateCleanup {
                override suspend fun afterVersionReplacement(toolId: String, previousVersionCode: Int, nextVersionCode: Int) = Unit

                override suspend fun afterUninstall(toolId: String) {
                    uninstallCleanupCalled = true
                }
            }
            val failingManager = ToolPackageManagers.create(
                privateFilesDirectory = root.toFile(),
                catalog = repositories.catalog,
                lifecycle = FailingDeleteLifecycle(repositories.lifecycle),
                transactions = repositories.installs,
            )

            val result = failingManager.uninstall(TOOL_ID, cleanup)

            assertTrue(
                result is PackageUninstallResult.Failed &&
                    result.failure.code == PackageOperationFailureCode.DATA_FAILURE,
            )
            assertFalse(uninstallCleanupCalled)
            assertEquals(1, repositories.catalog.observeTool(TOOL_ID).first()?.currentVersion?.versionCode)
            assertTrue(Files.isDirectory(root.resolve("miniapps/$TOOL_ID/versions/1")))
            assertNoPendingCleanup(root)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun packageRequiringNewerHostIsRejectedWithoutResidue() = runBlocking {
        val root = Files.createTempDirectory("tool-package-host-version")
        try {
            val repositories = InMemoryCoreData.create()
            val manager = ToolPackageManagers.create(
                privateFilesDirectory = root.toFile(),
                catalog = repositories.catalog,
                lifecycle = repositories.lifecycle,
                transactions = repositories.installs,
                hostVersion = "0.3.0",
            )

            val result = manager.importAndInstall(
                ByteInput("future.tbx", packageBytes(minHostVersion = "0.4.0")),
            )

            assertTrue(
                result is PackageInstallResult.Failed &&
                    result.failure.code == PackageOperationFailureCode.UNSUPPORTED_HOST_VERSION,
            )
            assertTrue(repositories.catalog.observeTools().first().isEmpty())
            assertNoTransientFiles(root)
            assertNoPendingCleanup(root)
        } finally {
            deleteTree(root)
        }
    }

    private fun packageBytes(
        versionCode: Int = 1,
        minHostVersion: String = "0.2.0",
        entryHtml: ByteArray = HTML,
        signed: Boolean = false,
        corruptIntegrity: Boolean = false,
        corruptSignature: Boolean = false,
        extra: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val content = linkedMapOf(
            "manifest.json" to manifest(versionCode, minHostVersion).toByteArray(),
            "index.html" to entryHtml,
        ).apply { putAll(extra) }
        val integrity = integrity(content, corruptIntegrity).toByteArray()
        val entries = linkedMapOf<String, ByteArray>().apply {
            putAll(content)
            put("integrity.json", integrity)
            if (signed) put("signature.json", signature(integrity, corruptSignature).toByteArray())
        }
        return zip(entries)
    }

    private fun manifest(versionCode: Int, minHostVersion: String) = """
        {"schemaVersion":1,"id":"$TOOL_ID","name":"Fixture","version":"1.0.$versionCode","versionCode":$versionCode,"entry":"index.html","apiVersion":"1.0","minHostVersion":"$minHostVersion","permissions":[{"name":"storage","reason":"Save values"},{"name":"network","reason":"Fetch data"}],"network":{"allowDomains":["api.github.com"]},"securityProfile":"strict"}
    """.trimIndent()

    private fun integrity(content: Map<String, ByteArray>, corrupt: Boolean): String {
        val files = content.entries.joinToString(",") { (path, bytes) ->
            val hash = if (corrupt && path == "index.html") "0".repeat(64) else sha256(bytes)
            "\"$path\":\"$hash\""
        }
        return "{\"schemaVersion\":1,\"algorithm\":\"SHA-256\",\"files\":{$files}}"
    }

    private fun signature(integrity: ByteArray, corrupt: Boolean): String {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val value = Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(integrity)
            sign()
        }.also { if (corrupt) it[0] = (it[0].toInt() xor 1).toByte() }
        val keyId = "sha256:${sha256(pair.public.encoded)}"
        return "{\"schemaVersion\":1,\"algorithm\":\"Ed25519\",\"keyId\":\"$keyId\",\"publicKey\":\"${Base64.getEncoder().encodeToString(pair.public.encoded)}\",\"signedFile\":\"integrity.json\",\"signature\":\"${Base64.getEncoder().encodeToString(value)}\"}"
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { archive ->
            entries.forEach { (name, bytes) ->
                archive.putNextEntry(ZipEntry(name))
                archive.write(bytes)
                archive.closeEntry()
            }
        }
        output.toByteArray()
    }

    private fun assertNoTransientFiles(root: Path) {
        val imports = root.resolve("miniapps/.imports")
        val staging = root.resolve("miniapps/.staging")
        assertTrue(!Files.exists(imports) || Files.list(imports).use { it.findAny().isEmpty })
        assertTrue(!Files.exists(staging) || Files.list(staging).use { it.findAny().isEmpty })
    }

    private fun assertNoPendingCleanup(root: Path) {
        val replacement = root.resolve("miniapps/.lifecycle/replacement-cleanup")
        val uninstall = root.resolve("miniapps/.lifecycle/uninstall-cleanup")
        assertTrue(!Files.exists(replacement) || Files.list(replacement).use { it.findAny().isEmpty })
        assertTrue(!Files.exists(uninstall) || Files.list(uninstall).use { it.findAny().isEmpty })
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class ByteInput(override val displayName: String, val bytes: ByteArray) : PackageInput {
        override fun openStream() = ByteArrayInputStream(bytes)
    }

    private data class FileInput(val path: Path) : PackageInput {
        override val displayName: String = path.fileName.toString()
        override fun openStream() = Files.newInputStream(path)
    }

    private class FailingCommitLifecycle(
        private val delegate: CatalogLifecycleRepository,
    ) : CatalogLifecycleRepository {
        override suspend fun findCommittedInstall(transactionId: String): DataResult<CommittedInstall?> =
            delegate.findCommittedInstall(transactionId)

        override suspend fun commitInstall(attempt: CatalogInstallAttempt): DataResult<CommitInstallOutcome> =
            DataResult.Failure.StorageFailure("forced")

        override suspend fun deleteToolCatalog(toolId: String): DataResult<DeleteToolCatalogOutcome> =
            delegate.deleteToolCatalog(toolId)
    }

    private class FailingDeleteLifecycle(
        private val delegate: CatalogLifecycleRepository,
    ) : CatalogLifecycleRepository {
        override suspend fun findCommittedInstall(transactionId: String): DataResult<CommittedInstall?> =
            delegate.findCommittedInstall(transactionId)

        override suspend fun commitInstall(attempt: CatalogInstallAttempt): DataResult<CommitInstallOutcome> =
            delegate.commitInstall(attempt)

        override suspend fun deleteToolCatalog(toolId: String): DataResult<DeleteToolCatalogOutcome> =
            DataResult.Failure.StorageFailure("forced")
    }

    private companion object {
        const val TOOL_ID = "io.toolbox.fixture"
        val HTML = "<!doctype html><html><body>ok</body></html>".toByteArray()
    }
}

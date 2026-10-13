package io.toolbox.core.data.db

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM guard for the Room schema history: every exported schema version must be reachable from
 * the oldest one through ToolBoxMigrations.ALL. ToolBoxMigrationTest (androidTest) then checks
 * each migration's result against these files on a device.
 */
class ToolBoxSchemaHistoryTest {
    private val schemaDir: File = listOf("schemas", "core-data/schemas")
        .map { File(it, "io.toolbox.core.data.db.ToolBoxDatabase") }
        .firstOrNull(File::isDirectory)
        ?: error("Exported Room schemas not found from ${File("").absolutePath}")

    private val versions: List<Int> = schemaDir.listFiles().orEmpty()
        .filter { it.name.endsWith(".json") }
        .map { it.name.removeSuffix(".json").toInt() }
        .sorted()

    @Test
    fun exportedSchemasAreContiguousAndNameTheirOwnVersion() {
        assertTrue("no exported schema", versions.isNotEmpty())
        assertEquals((versions.first()..versions.last()).toList(), versions)
        for (version in versions) {
            val database = Json.parseToJsonElement(File(schemaDir, "$version.json").readText())
                .jsonObject.getValue("database").jsonObject
            assertEquals("$version.json", version, database.getValue("version").jsonPrimitive.int)
        }
    }

    @Test
    fun migrationsChainFromOldestToNewestExportedSchema() {
        val migrations = ToolBoxMigrations.ALL
        assertEquals(
            "each start version needs exactly one migration",
            migrations.size,
            migrations.map { it.startVersion }.toSet().size,
        )
        var current = versions.first()
        val byStart = migrations.associateBy { it.startVersion }
        while (current != versions.last()) {
            val next = byStart[current]
                ?: throw AssertionError("No migration from schema $current; add it to ToolBoxMigrations.ALL")
            assertTrue("migration ${next.startVersion}->${next.endVersion} must move forward", next.endVersion > current)
            assertTrue("schema ${next.endVersion}.json missing", next.endVersion in versions)
            current = next.endVersion
        }
        assertTrue(
            "migration beyond the newest exported schema",
            migrations.all { it.endVersion <= versions.last() },
        )
    }
}

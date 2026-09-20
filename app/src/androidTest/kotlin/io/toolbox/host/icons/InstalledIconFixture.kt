package io.toolbox.host.icons

import androidx.test.platform.app.InstrumentationRegistry
import io.toolbox.core.data.BundleLocator
import io.toolbox.core.data.CatalogEntry
import io.toolbox.core.data.CatalogRepository
import io.toolbox.core.data.InstalledTool
import io.toolbox.core.data.SecurityProfile
import io.toolbox.core.data.ToolMetadata
import io.toolbox.core.data.ToolVersion
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertTrue

internal class InstalledIconFixture : AutoCloseable {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = Files.createTempDirectory(context.cacheDir.toPath(), "icon-behavior-")
    val id = "io.toolbox.iconfixture"
    private val locator = BundleLocator("miniapps/$id/versions/1/bundle")
    private val bundle = Files.createDirectories(root.resolve(locator.value))
    val initialTool = InstalledTool(
        ToolMetadata(id, "Icon fixture", SecurityProfile.STRICT, 1),
        ToolVersion(id, 1, "1.0.0", locator, 1, "fixture", 1),
        null,
    )
    val current = MutableStateFlow<InstalledTool?>(initialTool)
    val catalogReads = AtomicInteger()
    val catalog = object : CatalogRepository {
        override fun observeCatalogProjection() = flowOf(emptyList<CatalogEntry>())
        override fun observeTools() = current.map { listOfNotNull(it) }
        override fun observeTool(toolId: String) = current.map {
            catalogReads.incrementAndGet()
            it?.takeIf { tool -> tool.metadata.id == toolId }
        }
    }

    init {
        Files.writeString(bundle.resolve("manifest.json"), """{"schemaVersion":1,"id":"$id","name":"Icon fixture","version":"1.0.0","versionCode":1,"entry":"index.html","icon":"icon.svg","apiVersion":"1.0","minHostVersion":"0.3.0","permissions":[],"securityProfile":"strict"}""")
        writeIcon("red")
    }

    fun writeIcon(color: String) {
        Files.writeString(bundle.resolve("icon.svg"), """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect width="100" height="100" fill="$color"/></svg>""")
    }

    override fun close() {
        assertTrue(root.toFile().deleteRecursively())
    }
}

package io.toolbox.host.importflow

import io.toolbox.host.HostExampleInstallResult
import org.junit.Assert.assertEquals
import org.junit.Test

class BundledExamplesMessageTest {
    @Test
    fun aFirstInstallKeepsTheShortMessage() {
        assertEquals("已安装 4 个范例", bundledExamplesMessage(HostExampleInstallResult.Installed(4)))
    }

    @Test
    fun updatedAndCurrentExamplesAreReportedSeparately() {
        assertEquals(
            "范例已就绪：新安装 1 个，更新 2 个，1 个已是最新",
            bundledExamplesMessage(HostExampleInstallResult.Installed(4, updated = 2, alreadyCurrent = 1)),
        )
        assertEquals(
            "范例已就绪：4 个已是最新",
            bundledExamplesMessage(HostExampleInstallResult.Installed(4, alreadyCurrent = 4)),
        )
    }
}

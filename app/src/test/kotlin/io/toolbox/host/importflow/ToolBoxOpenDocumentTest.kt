package io.toolbox.host.importflow

import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolBoxOpenDocumentTest {
    @Test
    fun selectedSourceUsesSafeEphemeralNameAndCanOnlyBeOpenedOnce() {
        val opens = AtomicInteger()
        assertSame(SelectedPackageSource.Cancelled, selectedPackageSource(null, null))
        for ((name, expectedMessage) in listOf(
            null to "无法确认所选文件是 .tbx 工具包",
            "notes.txt" to "请选择 .tbx 工具包",
            "report\u202Etxt.tbx" to "所选文件名包含不安全字符",
            "report\uDB40\uDC01.tbx" to "所选文件名包含不安全字符",
        )) {
            assertEquals(
                SelectedPackageSource.Rejected(expectedMessage),
                selectedPackageSource(name) {
                    opens.incrementAndGet()
                    ByteArrayInputStream(byteArrayOf())
                },
            )
        }
        assertEquals(0, opens.get())

        val selected = selectedPackageSource(" ../unsafe\\name\u0000.tbx ") {
            opens.incrementAndGet()
            ByteArrayInputStream(byteArrayOf(1, 2, 3))
        } as SelectedPackageSource.Ready
        val input = selected.input

        assertEquals("unsafe_name_.tbx", input.displayName)
        assertArrayEquals(byteArrayOf(1, 2, 3), input.openStream().readBytes())
        assertThrows(IllegalStateException::class.java) { input.openStream() }
        assertEquals(1, opens.get())
        assertArrayEquals(
            arrayOf(
                ToolBoxOpenDocument.TOOLBOX_MIME_TYPE,
                ToolBoxOpenDocument.ZIP_MIME_TYPE,
                ToolBoxOpenDocument.OCTET_STREAM_MIME_TYPE,
            ),
            ToolBoxOpenDocument.mimeTypes(),
        )
    }
}

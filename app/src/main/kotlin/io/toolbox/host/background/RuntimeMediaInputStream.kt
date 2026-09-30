package io.toolbox.host.background

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** WebView supplies the read buffers. No complete media body or bridge Blob is retained here. */
internal class RuntimeMediaInputStream(
    private val input: InputStream,
    private val control: ToolNetworkStreamControl,
    private val limit: Long?,
    private val onClose: () -> Unit,
) : InputStream() {
    private val closed = AtomicBoolean()
    private var received = 0L
    private var eof = false

    override fun read(): Int {
        val byte = ByteArray(1)
        return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        if (eof) return -1
        if (closed.get()) throw IOException("Media body closed")
        try {
            control.requireActive()
            val count = input.read(buffer, offset, length)
            control.requireActive()
            if (count < 0) { eof = true; close() }
            else {
                received = Math.addExact(received, count.toLong())
                if ((limit != null && received > limit) || received > MAX_SAFE_NETWORK_BYTES) throw ToolNetworkFailure("RESULT_TOO_LARGE")
            }
            return count
        } catch (error: Exception) { close(); throw error }
    }

    override fun available(): Int {
        if (closed.get()) return 0
        control.requireActive()
        return input.available()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }
}

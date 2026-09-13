package io.toolbox.core.data

import java.io.File

/** Current process/storage capacity. Call immediately before allocation or a streamed write. */
object ResourceCapacity {
    fun availableHeapBytes(): Long = Runtime.getRuntime().let {
        (it.maxMemory() - (it.totalMemory() - it.freeMemory())).coerceAtLeast(0)
    }

    fun requireHeapBytes(bytes: Long) {
        require(bytes >= 0 && bytes <= availableHeapBytes()) { "INSUFFICIENT_MEMORY" }
    }

    fun requireStorageBytes(directory: File, bytes: Long) {
        require(bytes >= 0 && bytes <= directory.usableSpace) { "INSUFFICIENT_STORAGE" }
    }
}

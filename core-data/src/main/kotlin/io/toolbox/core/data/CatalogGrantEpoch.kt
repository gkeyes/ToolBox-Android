package io.toolbox.core.data

import java.util.concurrent.atomic.AtomicLong

/**
 * Advances after every write that can change a tool's installed version or permission grants
 * (install, update, uninstall, grant changes, backup restore and rollback), including writes
 * that failed or rolled back. A cache of those facts is valid only while the epoch it was
 * loaded at is still current, so readers never need Room's asynchronous invalidation.
 */
object CatalogGrantEpoch {
    private val value = AtomicLong()

    fun current(): Long = value.get()

    fun advance() {
        value.incrementAndGet()
    }
}

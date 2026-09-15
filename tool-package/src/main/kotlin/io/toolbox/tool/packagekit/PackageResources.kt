package io.toolbox.tool.packagekit

import android.app.ActivityManager
import android.content.Context
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

data class PackageResourceSnapshot(val availableBytes: Long, val lowMemory: Boolean)

fun interface PackageResourceProbe {
    fun snapshot(directory: Path): PackageResourceSnapshot
}

object FileSystemPackageResourceProbe : PackageResourceProbe {
    override fun snapshot(directory: Path) = PackageResourceSnapshot(
        availableBytes = Files.getFileStore(directory).usableSpace,
        lowMemory = false,
    )
}

class AndroidPackageResourceProbe(context: Context) : PackageResourceProbe {
    private val activityManager = requireNotNull(context.applicationContext.getSystemService(ActivityManager::class.java))

    override fun snapshot(directory: Path): PackageResourceSnapshot {
        val memory = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memory)
        return PackageResourceSnapshot(Files.getFileStore(directory).usableSpace, memory.lowMemory)
    }
}

internal class PackageResourceGuard(private val directory: Path, private val probe: PackageResourceProbe) {
    fun check(requiredBytes: Long = 0) {
        checkPackageInterrupted()
        require(requiredBytes >= 0)
        val snapshot = try {
            probe.snapshot(directory)
        } catch (error: Exception) {
            checkPackageInterrupted()
            reject(PackageRejectionCode.RESOURCE_CHECK_FAILED, "Unable to check available installation resources")
        }
        if (snapshot.availableBytes < 0) reject(PackageRejectionCode.RESOURCE_CHECK_FAILED, "Available storage could not be determined")
        if (snapshot.lowMemory) reject(PackageRejectionCode.INSUFFICIENT_RESOURCES, "The system reports low memory; retry after closing other apps")
        if (requiredBytes > snapshot.availableBytes) {
            reject(PackageRejectionCode.INSUFFICIENT_SPACE, "Not enough available storage for the remaining installation data")
        }
    }

    fun ioRejection(error: IOException): PackageRejection? {
        checkPackageInterrupted()
        // Creation, flush and close can fail after another process consumes the checked space.
        return if (generateSequence<Throwable>(error) { it.cause }.any {
                it.message?.contains("ENOSPC", ignoreCase = true) == true ||
                    it.message?.contains("No space left", ignoreCase = true) == true
            }) {
            PackageRejection(PackageRejectionCode.INSUFFICIENT_SPACE, "Storage filled while installing; free space and retry")
        } else null
    }

    fun ioFailed(error: IOException): Nothing {
        ioRejection(error)?.let { throw InspectionRejected(it) }
        throw error
    }

}

internal fun checkPackageInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Package operation cancelled")
}

internal fun packageByteTotal(total: Long, count: Long): Long = try {
    Math.addExact(total, count)
} catch (_: ArithmeticException) {
    reject(PackageRejectionCode.MALFORMED_ARCHIVE, "Package byte count overflow")
}

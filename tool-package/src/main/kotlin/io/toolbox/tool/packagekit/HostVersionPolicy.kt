package io.toolbox.tool.packagekit

object HostVersionPolicy {
    fun supports(hostVersion: String, minimumHostVersion: String): Boolean {
        val host = numericCore(hostVersion) ?: return false
        val minimum = numericCore(minimumHostVersion) ?: return false
        return host >= minimum
    }

    private fun numericCore(value: String): VersionCore? {
        val parts = value.substringBefore('-').substringBefore('+').split('.')
        if (parts.size != 3) return null
        val values = parts.map { it.toIntOrNull() ?: return null }
        return VersionCore(values[0], values[1], values[2])
    }

    private data class VersionCore(val major: Int, val minor: Int, val patch: Int) : Comparable<VersionCore> {
        override fun compareTo(other: VersionCore): Int =
            compareValuesBy(this, other, VersionCore::major, VersionCore::minor, VersionCore::patch)
    }
}

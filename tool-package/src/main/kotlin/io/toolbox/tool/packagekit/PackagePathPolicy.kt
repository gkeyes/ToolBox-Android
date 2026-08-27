package io.toolbox.tool.packagekit

import java.text.Normalizer
import java.util.Locale

internal data class SafePackagePath(
    val normalized: String,
    val collisionKey: String,
    val directory: Boolean,
)

internal object PackagePathPolicy {
    private val drivePath = Regex("^[A-Za-z]:")
    private val encodedSeparatorOrDot = Regex("%(?:2e|2f|5c)", RegexOption.IGNORE_CASE)

    fun validate(raw: String, limits: PackageLimits): SafePackagePath {
        if (raw.isEmpty() || raw.indexOf('\u0000') >= 0 || raw.contains('\\')) {
            reject(PackageRejectionCode.PATH_INVALID, "Empty, NUL and backslash paths are forbidden")
        }
        if (raw.startsWith('/') || drivePath.containsMatchIn(raw) || encodedSeparatorOrDot.containsMatchIn(raw)) {
            reject(PackageRejectionCode.PATH_INVALID, "Absolute or encoded traversal path is forbidden: $raw")
        }
        val directory = raw.endsWith('/')
        val withoutTrailingSlash = if (directory) raw.dropLast(1) else raw
        if (withoutTrailingSlash.isEmpty()) reject(PackageRejectionCode.PATH_INVALID, "Root directory entry is forbidden")
        val segments = withoutTrailingSlash.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            reject(PackageRejectionCode.PATH_INVALID, "Empty, dot or parent path segment is forbidden: $raw")
        }
        val normalized = Normalizer.normalize(withoutTrailingSlash, Normalizer.Form.NFC)
        if (normalized.length > limits.maxPathCharacters) {
            reject(PackageRejectionCode.PATH_TOO_LONG, "Path exceeds ${limits.maxPathCharacters} characters: $raw")
        }
        return SafePackagePath(
            normalized = normalized,
            collisionKey = normalized.lowercase(Locale.ROOT),
            directory = directory,
        )
    }

    fun forbiddenCode(path: String): PackageRejectionCode? {
        val lower = path.lowercase(Locale.ROOT)
        val extension = lower.substringAfterLast('.', missingDelimiterValue = "")
        return when (extension) {
            "zip", "tbx", "jar", "apk", "aar", "7z", "rar", "tar", "gz", "bz2", "xz" ->
                PackageRejectionCode.NESTED_ARCHIVE
            "dex", "so", "class", "wasm", "odex", "vdex", "oat" ->
                PackageRejectionCode.NATIVE_OR_DYNAMIC_CODE
            else -> null
        }
    }
}

internal class InspectionRejected(val rejection: PackageRejection) : Exception(rejection.detail)

internal fun reject(code: PackageRejectionCode, detail: String): Nothing =
    throw InspectionRejected(PackageRejection(code, detail))

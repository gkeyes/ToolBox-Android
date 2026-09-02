package io.toolbox.tool.packagekit

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal object BundleEntryValidator {
    fun validate(manifest: ToolManifest, bundleDirectory: Path, hashes: Map<String, String>) {
        if (manifest.entry !in hashes) {
            reject(PackageRejectionCode.ENTRY_MISSING, "Manifest entry does not exist: ${manifest.entry}")
        }
        manifest.icon?.let { icon ->
            if (icon !in hashes) reject(PackageRejectionCode.ENTRY_MISSING, "Manifest icon does not exist: $icon")
        }
        val prefix = Files.newInputStream(
            bundleDirectory.resolve(manifest.entry),
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS,
        ).use { it.readNBytes(4096) }
        val text = try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val characters = CharBuffer.allocate(prefix.size)
            val result = decoder.decode(ByteBuffer.wrap(prefix), characters, false)
            if (result.isError) result.throwException()
            characters.flip().toString()
        } catch (_: Exception) {
            reject(PackageRejectionCode.ENTRY_MIME_INVALID, "Manifest entry is not UTF-8 HTML")
        }
        val lower = text.trimStart().lowercase()
        if ('\u0000' in text || (!lower.startsWith("<!doctype html") && !lower.startsWith("<html"))) {
            reject(PackageRejectionCode.ENTRY_MIME_INVALID, "Manifest entry does not have an HTML document signature")
        }
    }
}

package io.toolbox.tool.packagekit

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal object BundleEntryValidator {
    fun validate(manifest: ToolManifest, bundleDirectory: Path, hashes: Map<String, String>) {
        if (manifest.entry !in hashes || !Files.isRegularFile(bundleDirectory.resolve(manifest.entry), NOFOLLOW_LINKS)) {
            reject(PackageRejectionCode.ENTRY_MISSING, "Manifest entry does not exist: ${manifest.entry}")
        }
        manifest.icon?.let { icon ->
            if (icon !in hashes) reject(PackageRejectionCode.ENTRY_MISSING, "Manifest icon does not exist: $icon")
        }
        // The manifest validates the HTML path; extraction already rejects known native/archive
        // signatures for every file. HTML allows BOMs, comments, arbitrary leading whitespace and
        // omitted document tags. A prefix sniff is not an HTML parser and must not reject them.
    }
}

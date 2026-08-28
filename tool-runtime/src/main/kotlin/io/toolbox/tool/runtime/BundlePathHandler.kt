package io.toolbox.tool.runtime

import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import io.toolbox.core.data.SecurityProfile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

internal class BundlePathHandler(
    private val privateFilesRoot: Path,
    private val bundleRoot: Path,
    private val securityProfile: SecurityProfile,
) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse {
        val resource = resolveRegularFile(path) ?: return RuntimePolicy.blockedResponse(404, "Not Found")
        val mime = safeMimeType(resource.fileName.toString())
            ?: return RuntimePolicy.blockedResponse(415, "Unsupported Media Type")
        return try {
            WebResourceResponse(
                mime.type,
                mime.encoding,
                200,
                "OK",
                RuntimePolicy.responseHeaders(securityProfile),
                Files.newInputStream(resource, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            )
        } catch (_: Exception) {
            RuntimePolicy.blockedResponse(404, "Not Found")
        }
    }

    private fun resolveRegularFile(path: String): Path? {
        if (!validateDirectoryChain()) return null
        if (path.isBlank() || path.startsWith('/') || '\\' in path || '%' in path) return null
        val segments = path.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
        var cursor = bundleRoot
        return try {
            segments.forEachIndexed { index, segment ->
                cursor = cursor.resolve(segment).normalize()
                if (!cursor.startsWith(bundleRoot)) return null
                val attributes = Files.readAttributes(
                    cursor,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                if (attributes.isSymbolicLink) return null
                if (index < segments.lastIndex && !attributes.isDirectory) return null
                if (index == segments.lastIndex && (!attributes.isRegularFile || attributes.size() > MAX_RESOURCE_BYTES)) {
                    return null
                }
            }
            cursor
        } catch (_: Exception) {
            null
        }
    }

    private fun validateDirectoryChain(): Boolean {
        if (!bundleRoot.startsWith(privateFilesRoot)) return false
        var cursor = privateFilesRoot
        return try {
            val rootAttributes = Files.readAttributes(
                cursor,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (!rootAttributes.isDirectory || rootAttributes.isSymbolicLink) return false
            privateFilesRoot.relativize(bundleRoot).forEach { segment ->
                cursor = cursor.resolve(segment)
                val attributes = Files.readAttributes(
                    cursor,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                if (!attributes.isDirectory || attributes.isSymbolicLink) return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun safeMimeType(fileName: String): Mime? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> Mime("text/html", "UTF-8")
        "css" -> Mime("text/css", "UTF-8")
        "js", "mjs" -> Mime("text/javascript", "UTF-8")
        "json" -> Mime("application/json", "UTF-8")
        "txt" -> Mime("text/plain", "UTF-8")
        "png" -> Mime("image/png", null)
        "jpg", "jpeg" -> Mime("image/jpeg", null)
        "gif" -> Mime("image/gif", null)
        "webp" -> Mime("image/webp", null)
        "svg" -> Mime("image/svg+xml", "UTF-8")
        "woff" -> Mime("font/woff", null)
        "woff2" -> Mime("font/woff2", null)
        "ttf" -> Mime("font/ttf", null)
        "otf" -> Mime("font/otf", null)
        "mp3" -> Mime("audio/mpeg", null)
        "ogg" -> Mime("audio/ogg", null)
        "wav" -> Mime("audio/wav", null)
        "mp4" -> Mime("video/mp4", null)
        "webm" -> Mime("video/webm", null)
        else -> null
    }

    private data class Mime(val type: String, val encoding: String?)

    private companion object {
        const val MAX_RESOURCE_BYTES = 20L * 1024 * 1024
    }
}

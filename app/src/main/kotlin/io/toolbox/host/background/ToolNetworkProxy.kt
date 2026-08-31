package io.toolbox.host.background

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.Locale
import java.util.Base64
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull

internal fun interface ToolNetworkTransport {
    suspend fun execute(request: Request, timeoutMillis: Long): Response
}

class ToolNetworkProxy private constructor(
    private val dns: Dns,
    private val maxRedirects: Int,
    private val transport: ToolNetworkTransport?,
) {
    constructor(
        dns: Dns = Dns.SYSTEM,
        maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
    ) : this(dns, maxRedirects, null)

    internal constructor(
        transport: ToolNetworkTransport,
        dns: Dns = Dns.SYSTEM,
        maxRedirects: Int = DEFAULT_MAX_REDIRECTS,
    ) : this(dns, maxRedirects, transport)

    private val client by lazy(LazyThreadSafetyMode.NONE) {
        OkHttpClient.Builder()
            .cache(null)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .dns(ValidatingDns(dns))
            .build()
    }

    suspend fun httpGet(
        url: String,
        allowedHosts: Set<String>,
        allowRedirects: Boolean = false,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        maxResponseBytes: Int = MAX_RESULT_BYTES,
    ): NetworkExecution = request(
        url = url,
        method = NetworkRequestMethod.GET,
        allowedHosts = allowedHosts,
        allowRedirects = allowRedirects,
        timeoutMillis = timeoutMillis,
        maxResponseBytes = maxResponseBytes,
        acceptHttpErrors = false,
    )

    suspend fun request(
        url: String,
        method: NetworkRequestMethod,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        bodyIsJson: Boolean = false,
        allowedHosts: Set<String>,
        allowRedirects: Boolean = true,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        maxResponseBytes: Int = DEFAULT_RESPONSE_BYTES,
        acceptHttpErrors: Boolean = true,
    ): NetworkExecution {
        if (timeoutMillis !in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS) {
            return NetworkExecution.TerminalFailure("INVALID_TIMEOUT")
        }
        if (maxResponseBytes !in 1..MAX_PROXY_RESPONSE_BYTES) {
            return NetworkExecution.TerminalFailure("INVALID_RESPONSE_LIMIT")
        }
        if (allowedHosts.isEmpty() || allowedHosts.any { normalizeHost(it) == null }) {
            return NetworkExecution.TerminalFailure("NETWORK_HOST_NOT_ALLOWED")
        }
        val normalizedAllowlist = allowedHosts.mapTo(linkedSetOf()) { requireNotNull(normalizeHost(it)) }
        val requestClient = if (transport == null) {
            client.newBuilder()
                .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .build()
        } else {
            null
        }
        var current = url.toHttpUrlOrNull()
            ?: return NetworkExecution.TerminalFailure("INVALID_URL")
        var currentMethod = method
        var currentBody = body
        var includeCallerHeaders = true
        var redirects = 0
        while (true) {
            val validation = NetworkPolicy.validateEndpoint(current, normalizedAllowlist)
            if (validation != null) return NetworkExecution.TerminalFailure(validation)
            val response = try {
                val request = Request.Builder()
                    .url(current)
                    .apply {
                        if (includeCallerHeaders) {
                            headers.forEach { (name, value) -> header(name, value) }
                        }
                        when (currentMethod) {
                            NetworkRequestMethod.GET -> get()
                            NetworkRequestMethod.HEAD -> head()
                            NetworkRequestMethod.POST -> post(
                                (currentBody ?: ByteArray(0)).toRequestBody(requestMediaType(headers, bodyIsJson)),
                            )
                            NetworkRequestMethod.PUT -> put(
                                (currentBody ?: ByteArray(0)).toRequestBody(requestMediaType(headers, bodyIsJson)),
                            )
                            NetworkRequestMethod.PATCH -> patch(
                                (currentBody ?: ByteArray(0)).toRequestBody(requestMediaType(headers, bodyIsJson)),
                            )
                            NetworkRequestMethod.DELETE -> if (currentBody == null) delete() else delete(
                                currentBody.toRequestBody(requestMediaType(headers, bodyIsJson)),
                            )
                        }
                    }
                    .apply {
                        if (!includeCallerHeaders || headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                            header("User-Agent", USER_AGENT)
                        }
                        if (!includeCallerHeaders || headers.keys.none { it.equals("Accept", ignoreCase = true) }) {
                            header("Accept", "application/json, text/plain;q=0.9, text/*;q=0.8, */*;q=0.5")
                        }
                    }
                    .build()
                transport?.execute(request, timeoutMillis)
                    ?: requireNotNull(requestClient).newCall(request).await()
            } catch (_: BlockedAddressException) {
                return NetworkExecution.TerminalFailure("NETWORK_ADDRESS_BLOCKED")
            } catch (error: IOException) {
                return if (error.hasBlockedAddressCause()) {
                    NetworkExecution.TerminalFailure("NETWORK_ADDRESS_BLOCKED")
                } else {
                    NetworkExecution.RetryableFailure("NETWORK_IO")
                }
            }
            response.use {
                if (it.code in REDIRECT_CODES) {
                    NetworkPolicy.redirectError(allowRedirects)?.let {
                        return NetworkExecution.TerminalFailure(it)
                    }
                    if (redirects >= maxRedirects) return NetworkExecution.TerminalFailure("TOO_MANY_REDIRECTS")
                    val location = it.header("Location")
                        ?: return NetworkExecution.TerminalFailure("INVALID_REDIRECT")
                    val redirected = current.resolve(location)
                        ?: return NetworkExecution.TerminalFailure("INVALID_REDIRECT")
                    includeCallerHeaders = includeCallerHeaders && sameOrigin(current, redirected)
                    if (it.code in setOf(301, 302, 303) && currentMethod !in setOf(NetworkRequestMethod.GET, NetworkRequestMethod.HEAD)) {
                        currentMethod = NetworkRequestMethod.GET
                        currentBody = null
                    }
                    current = redirected
                    redirects += 1
                    continue
                }
                if (!acceptHttpErrors) {
                    if (it.code in 500..599) return NetworkExecution.RetryableFailure("HTTP_${it.code}")
                    if (it.code !in 200..299) return NetworkExecution.TerminalFailure("HTTP_${it.code}")
                }
                val body = it.body.readBounded(maxResponseBytes)
                    ?: return NetworkExecution.TerminalFailure("RESULT_TOO_LARGE")
                val text = isTextResponse(it)
                return NetworkExecution.Success(
                    statusCode = it.code,
                    finalUrl = current.toString(),
                    contentType = it.header("Content-Type")?.substringBefore(';'),
                    body = if (text) body.toString(Charsets.UTF_8) else Base64.getEncoder().encodeToString(body),
                    bodyEncoding = if (text) NetworkBodyEncoding.TEXT else NetworkBodyEncoding.BASE64,
                    headers = it.exposedHeaders(),
                )
            }
        }
    }

    private fun isTextResponse(response: Response): Boolean {
        val mediaType = response.body.contentType() ?: return true
        val subtype = mediaType.subtype.lowercase(Locale.ROOT)
        return mediaType.type.equals("text", ignoreCase = true) ||
            subtype == "json" || subtype.endsWith("+json")
    }

    private class ValidatingDns(private val delegate: Dns) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = delegate.lookup(hostname)
            if (addresses.isEmpty() || addresses.any(AddressPolicy::isForbidden)) {
                throw BlockedAddressException()
            }
            return addresses
        }
    }

    private companion object {
        const val USER_AGENT = "ToolBox/0.3.1 (Android)"
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        const val MIN_TIMEOUT_MILLIS = 1_000L
        const val MAX_TIMEOUT_MILLIS = 600_000L
        const val DEFAULT_RESPONSE_BYTES = 4 * 1_024 * 1_024
        const val MAX_PROXY_RESPONSE_BYTES = 64 * 1_024 * 1_024
        const val DEFAULT_MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

enum class NetworkRequestMethod { GET, POST, PUT, PATCH, DELETE, HEAD }

enum class NetworkBodyEncoding { TEXT, BASE64 }

private fun requestMediaType(headers: Map<String, String>, bodyIsJson: Boolean) =
    headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
        ?.value
        ?.toMediaTypeOrNull()
        ?: if (bodyIsJson) JSON_MEDIA_TYPE else TEXT_MEDIA_TYPE

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val TEXT_MEDIA_TYPE = "text/plain; charset=utf-8".toMediaType()
private val HIDDEN_RESPONSE_HEADERS = setOf(
    "connection",
    "proxy-authenticate",
    "set-cookie",
    "set-cookie2",
    "transfer-encoding",
    "upgrade",
)

private fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
    first.scheme == second.scheme && first.host == second.host && first.port == second.port

internal object NetworkPolicy {
    fun validateEndpoint(url: HttpUrl, allowedHosts: Set<String>): String? {
        if (!url.isHttps) return "HTTPS_REQUIRED"
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return "URL_CREDENTIALS_FORBIDDEN"
        val host = normalizeHost(url.host) ?: return "INVALID_HOST"
        if (isIpLiteralHost(host)) return "IP_LITERAL_FORBIDDEN"
        if (allowedHosts.none { allowed -> hostMatches(host, allowed) }) {
            return "NETWORK_HOST_NOT_ALLOWED"
        }
        return null
    }

    fun redirectError(allowRedirects: Boolean): String? = if (allowRedirects) null else "REDIRECTS_DISABLED"
}

sealed interface NetworkExecution {
    data class Success(
        val statusCode: Int,
        val finalUrl: String,
        val contentType: String?,
        val body: String,
        val bodyEncoding: NetworkBodyEncoding = NetworkBodyEncoding.TEXT,
        val headers: Map<String, String> = emptyMap(),
    ) : NetworkExecution

    data class RetryableFailure(val errorCode: String) : NetworkExecution
    data class TerminalFailure(val errorCode: String) : NetworkExecution
}

internal object AddressPolicy {
    fun isForbidden(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true
        val bytes = address.address
        return when (address) {
            is Inet4Address -> isForbiddenIpv4(bytes)
            is Inet6Address -> isForbiddenIpv6(bytes)
            else -> true
        }
    }

    private fun isForbiddenIpv4(bytes: ByteArray, offset: Int = 0): Boolean {
        val first = bytes[offset].unsigned()
        val second = bytes[offset + 1].unsigned()
        val third = bytes[offset + 2].unsigned()
        return first == 0 || first == 10 || first == 127 || first >= 224 ||
            (first == 100 && second in 64..127) ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 0) ||
            (first == 192 && second == 31 && third == 196) ||
            (first == 192 && second == 52 && third == 193) ||
            (first == 192 && second == 88 && third == 99) ||
            (first == 192 && second == 168) ||
            (first == 192 && second == 175 && third == 48) ||
            (first == 198 && second in 18..19) ||
            (first == 198 && second == 51 && third == 100) ||
            (first == 203 && second == 0 && third == 113)
    }

    private fun isForbiddenIpv6(bytes: ByteArray): Boolean {
        if (isIpv4Mapped(bytes)) return isForbiddenIpv4(bytes, 12)
        if (isIpv4Compatible(bytes)) return true
        if (isWellKnownNat64(bytes)) return isForbiddenIpv4(bytes, 12)
        return isUniqueLocal(bytes) ||
            isLocalUseNat64(bytes) ||
            isDiscardOnly(bytes) ||
            isDocumentation(bytes) ||
            isBenchmarking(bytes) ||
            isTeredo(bytes) ||
            is6to4(bytes) ||
            isOrchid(bytes)
    }

    private fun isIpv4Mapped(bytes: ByteArray): Boolean =
        bytes.take(10).all { it.unsigned() == 0 } && bytes[10].unsigned() == 0xff && bytes[11].unsigned() == 0xff

    private fun isIpv4Compatible(bytes: ByteArray): Boolean =
        bytes.take(12).all { it.unsigned() == 0 }

    private fun isWellKnownNat64(bytes: ByteArray): Boolean =
        bytes[0].unsigned() == 0x00 && bytes[1].unsigned() == 0x64 &&
            bytes[2].unsigned() == 0xff && bytes[3].unsigned() == 0x9b &&
            bytes[4].unsigned() == 0 && bytes[5].unsigned() == 0 &&
            bytes[6].unsigned() == 0 && bytes[7].unsigned() == 0 &&
            bytes[8].unsigned() == 0 && bytes[9].unsigned() == 0 &&
            bytes[10].unsigned() == 0 && bytes[11].unsigned() == 0

    private fun isUniqueLocal(bytes: ByteArray): Boolean = (bytes[0].unsigned() and 0xfe) == 0xfc

    private fun isLocalUseNat64(bytes: ByteArray): Boolean =
        bytes[0].unsigned() == 0x00 && bytes[1].unsigned() == 0x64 &&
            bytes[2].unsigned() == 0xff && bytes[3].unsigned() == 0x9b &&
            bytes[4].unsigned() == 0 && bytes[5].unsigned() == 1

    private fun isDiscardOnly(bytes: ByteArray): Boolean =
        bytes[0].unsigned() == 0x01 && bytes.drop(1).take(7).all { it.unsigned() == 0 }

    private fun isDocumentation(bytes: ByteArray): Boolean =
        bytes[0].unsigned() == 0x20 && bytes[1].unsigned() == 0x01 &&
            bytes[2].unsigned() == 0x0d && bytes[3].unsigned() == 0xb8

    private fun isBenchmarking(bytes: ByteArray): Boolean =
        bytes[0].unsigned() == 0x20 && bytes[1].unsigned() == 0x01 &&
            bytes[2].unsigned() == 0x00 && bytes[3].unsigned() == 0x02

    private fun isTeredo(bytes: ByteArray): Boolean =
        bytes[0].unsigned() == 0x20 && bytes[1].unsigned() == 0x01 &&
            bytes[2].unsigned() == 0x00 && bytes[3].unsigned() == 0x00

    private fun is6to4(bytes: ByteArray): Boolean = bytes[0].unsigned() == 0x20 && bytes[1].unsigned() == 0x02

    private fun isOrchid(bytes: ByteArray): Boolean =
        bytes[0].unsigned() == 0x20 && bytes[1].unsigned() == 0x01 &&
            bytes[2].unsigned() in 0x10..0x2f
}

private class BlockedAddressException : UnknownHostException("Blocked network destination")

private fun Throwable.hasBlockedAddressCause(): Boolean =
    generateSequence(this) { it.cause }.any { it is BlockedAddressException }

internal fun normalizeHost(value: String): String? = runCatching {
    val wildcard = value.startsWith("*.")
    val source = if (wildcard) value.substring(2) else value
    val normalized = IDN.toASCII(source.trim().trimEnd('.'), IDN.USE_STD3_ASCII_RULES)
        .lowercase(Locale.ROOT)
    require(normalized.isNotBlank())
    if (wildcard) "*.$normalized" else normalized
}.getOrNull()

internal fun hostMatches(host: String, allowed: String): Boolean =
    if (allowed.startsWith("*.")) {
        host.endsWith(".${allowed.substring(2)}") && host != allowed.substring(2)
    } else {
        host == allowed
    }

private fun isIpLiteralHost(host: String): Boolean =
    host.contains(':') || IPV4_LITERAL.matches(host)

private fun Response.exposedHeaders(): Map<String, String> = headers.names()
    .asSequence()
    .filterNot { it.lowercase(Locale.ROOT) in HIDDEN_RESPONSE_HEADERS }
    .mapNotNull { name -> headers[name]?.takeIf { it.length <= 4_096 }?.let { name to it } }
    .take(64)
    .toMap(linkedMapOf())

private fun Byte.unsigned(): Int = toInt() and 0xff

private val IPV4_LITERAL = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")

private fun ResponseBody.readBounded(maxBytes: Int): ByteArray? {
    byteStream().use { input ->
        val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

private suspend fun Call.await(): Response = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resumeWith(Result.success(response)) else response.close()
            }
        },
    )
}

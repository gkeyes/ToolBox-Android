package io.toolbox.host.background

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.Locale
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
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

    private val client by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .cache(null)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .dns(dns)
            .build()
    }

    internal fun clientForRequest(timeoutMillis: Long): OkHttpClient = client.newBuilder()
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    /** Legacy destination options are ignored; the caller enforces the network capability grant. */
    suspend fun httpGet(
        url: String,
        allowedHosts: Set<String> = emptySet(),
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

    /** HTTPS and resource limits apply; legacy allowlist and redirect switches are ignored. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun request(
        url: String,
        method: NetworkRequestMethod,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        bodyIsJson: Boolean = false,
        allowedHosts: Set<String> = emptySet(),
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
        val requestClient = if (transport == null) {
            clientForRequest(timeoutMillis)
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
            val validation = NetworkPolicy.validateEndpoint(current)
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
                if (transport != null) {
                    val received = transport.execute(request, timeoutMillis)
                    try {
                        runInterruptible(Dispatchers.IO) { received.readResponse(maxResponseBytes, acceptHttpErrors) }
                    } finally {
                        received.close()
                    }
                } else {
                    requireNotNull(requestClient).newCall(request).awaitResponse(maxResponseBytes, acceptHttpErrors)
                }
            } catch (error: IOException) {
                return error.toNetworkFailure()
            }
            response.let {
                if (it.code in REDIRECT_CODES) {
                    if (redirects >= maxRedirects) return NetworkExecution.TerminalFailure("TOO_MANY_REDIRECTS")
                    val location = it.location
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
                val body = it.body ?: return NetworkExecution.TerminalFailure("RESULT_TOO_LARGE")
                val text = it.isText
                return NetworkExecution.Success(
                    statusCode = it.code,
                    finalUrl = current.toString(),
                    contentType = it.contentType,
                    body = if (text) body.toString(Charsets.UTF_8) else Base64.getEncoder().encodeToString(body),
                    bodyEncoding = if (text) NetworkBodyEncoding.TEXT else NetworkBodyEncoding.BASE64,
                    headers = it.headers,
                )
            }
        }
    }

    internal suspend fun openStream(
        options: ToolNetworkRequest,
        control: ToolNetworkStreamControl,
    ): ToolNetworkStream = withContext(Dispatchers.IO) { openResponse(options, control) }

    private suspend fun openResponse(
        options: ToolNetworkRequest,
        control: ToolNetworkStreamControl,
    ): ToolNetworkStream {
        val (
            url,
            method,
            headers,
            body,
            bodyIsJson,
            _, // Legacy allowlist is no longer an authorization boundary.
            _, // HTTPS redirects are always followed within the redirect limit.
            timeoutMillis,
            maxResponseBytes,
            acceptHttpErrors,
        ) = options
        if (timeoutMillis !in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS) {
            throw ToolNetworkFailure("INVALID_TIMEOUT")
        }
        if (maxResponseBytes !in 1..MAX_PROXY_RESPONSE_BYTES) {
            throw ToolNetworkFailure("INVALID_RESPONSE_LIMIT")
        }
        val requestClient = if (transport == null) clientForRequest(timeoutMillis) else null
        var current = url.toHttpUrlOrNull() ?: throw ToolNetworkFailure("INVALID_URL")
        var currentMethod = method
        var currentBody = body
        var includeCallerHeaders = true
        var redirects = 0
        while (true) {
            control.requireActive()
            NetworkPolicy.validateEndpoint(current)?.let { throw ToolNetworkFailure(it) }
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
                    ?: requireNotNull(requestClient).newCall(request).also(control::attach).await()
            } catch (error: IOException) {
                control.requireActive()
                throw error.toStreamFailure()
            }
            control.attach(response)
            if (response.code in REDIRECT_CODES) {
                response.use {
                    if (redirects >= maxRedirects) throw ToolNetworkFailure("TOO_MANY_REDIRECTS")
                    val location = it.header("Location") ?: throw ToolNetworkFailure("INVALID_REDIRECT")
                    val redirected = current.resolve(location) ?: throw ToolNetworkFailure("INVALID_REDIRECT")
                    includeCallerHeaders = includeCallerHeaders && sameOrigin(current, redirected)
                    if (
                        it.code in setOf(301, 302, 303) &&
                        currentMethod !in setOf(NetworkRequestMethod.GET, NetworkRequestMethod.HEAD)
                    ) {
                        currentMethod = NetworkRequestMethod.GET
                        currentBody = null
                    }
                    current = redirected
                    redirects += 1
                }
                continue
            }
            if (!acceptHttpErrors) {
                if (response.code in 500..599) {
                    throw ToolNetworkFailure("HTTP_${response.code}", retryable = true)
                }
                if (response.code !in 200..299) throw ToolNetworkFailure("HTTP_${response.code}")
            }
            return ToolNetworkStream(response, current.toString(), control, maxResponseBytes)
        }
    }

    private companion object {
        const val USER_AGENT = "ToolBox/0.6.6 (Android)"
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
        const val MIN_TIMEOUT_MILLIS = 1_000L
        const val MAX_TIMEOUT_MILLIS = 3_600_000L
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
    fun validateEndpoint(url: HttpUrl): String? {
        if (!url.isHttps) return "HTTPS_REQUIRED"
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return "URL_CREDENTIALS_FORBIDDEN"
        return null
    }
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

private fun IOException.toNetworkFailure(): NetworkExecution = when {
    this is InterruptedIOException -> NetworkExecution.RetryableFailure("NETWORK_TIMEOUT")
    else -> NetworkExecution.RetryableFailure("NETWORK_IO")
}

internal fun IOException.toStreamFailure(): ToolNetworkFailure = when {
    this is ToolNetworkFailure -> this
    this is InterruptedIOException -> ToolNetworkFailure("NETWORK_TIMEOUT", retryable = true)
    else -> ToolNetworkFailure("NETWORK_IO", retryable = true)
}

internal fun Response.exposedHeaders(): Map<String, String> = headers.names()
    .asSequence()
    .filterNot { it.lowercase(Locale.ROOT) in HIDDEN_RESPONSE_HEADERS }
    .mapNotNull { name -> headers[name]?.takeIf { it.length <= 4_096 }?.let { name to it } }
    .take(64)
    .toMap(linkedMapOf())

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

internal data class BufferedNetworkResponse(
    val code: Int,
    val location: String?,
    val contentType: String?,
    val isText: Boolean,
    val headers: Map<String, String>,
    val body: ByteArray?,
)

private fun Response.readResponse(maxBytes: Int, acceptHttpErrors: Boolean): BufferedNetworkResponse {
    val mediaType = body.contentType()
    val subtype = mediaType?.subtype?.lowercase(Locale.ROOT)
    val isText = mediaType == null || mediaType.type.equals("text", ignoreCase = true) ||
        subtype == "json" || subtype?.endsWith("+json") == true
    return BufferedNetworkResponse(
        code = code,
        location = header("Location"),
        contentType = header("Content-Type")?.substringBefore(';'),
        isText = isText,
        headers = exposedHeaders(),
        // Redirect/error bodies are not consumed; the next endpoint must still use HTTPS.
        body = if (code in setOf(301, 302, 303, 307, 308) || (!acceptHttpErrors && code !in 200..299)) {
            ByteArray(0)
        } else {
            body.readBounded(maxBytes)
        },
    )
}

private suspend fun Call.await(): Response = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response, onCancellation = { _, value, _ -> value.closeOnIo() })
            }
        },
    )
}

// Keep the continuation (and its Call.cancel hook) alive until the body has been consumed.
// OkHttp's worker reads the stream; no blocking network read occupies the RPC dispatcher.
internal suspend fun Call.awaitResponse(maxBytes: Int, acceptHttpErrors: Boolean): BufferedNetworkResponse =
    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!continuation.isActive) throw IOException("Request cancelled")
                            it.readResponse(maxBytes, acceptHttpErrors)
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            },
        )
    }

package io.toolbox.host.background

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal fun interface ToolNetworkTransport {
    suspend fun execute(request: Request, timeoutMillis: Long): Response
}

class ToolNetworkProxy private constructor(
    private val dns: Dns,
    private val transport: ToolNetworkTransport?,
    private val resources: NetworkResources,
    private val configureClient: (OkHttpClient.Builder) -> Unit,
) {
    constructor(dns: Dns = Dns.SYSTEM) : this(dns, null, NetworkResources.shared, {})

    internal constructor(
        transport: ToolNetworkTransport,
        dns: Dns = Dns.SYSTEM,
        resources: NetworkResources = NetworkResources.shared,
    ) : this(dns, transport, resources, {})

    internal constructor(
        resources: NetworkResources,
        configureClient: (OkHttpClient.Builder) -> Unit,
    ) : this(Dns.SYSTEM, null, resources, configureClient)

    private val client by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .cache(null)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .proxySelector(LiveSystemProxySelector)
            .dispatcher(okhttp3.Dispatcher().apply {
                // Shared, cancellable resource admission runs before enqueue; these are not product quotas.
                maxRequests = Int.MAX_VALUE
                maxRequestsPerHost = Int.MAX_VALUE
            })
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .dns(dns)
            .apply(configureClient)
            .build()
    }

    internal fun clientForRequest(timeoutMillis: Long): OkHttpClient = client.newBuilder()
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    /** The caller enforces the network capability grant. */
    suspend fun httpGet(
        url: String,
        timeoutMillis: Long = 0,
        maxResponseBytes: Long? = null,
        resourceOwner: String = "background",
    ): NetworkExecution = request(url, NetworkRequestMethod.GET, timeoutMillis = timeoutMillis,
        maxResponseBytes = maxResponseBytes, acceptHttpErrors = false, resourceOwner = resourceOwner)

    suspend fun request(
        url: String,
        method: NetworkRequestMethod,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        bodyIsJson: Boolean = false,
        timeoutMillis: Long = 0,
        maxResponseBytes: Long? = null,
        acceptHttpErrors: Boolean = true,
        resourceOwner: String = "background",
    ): NetworkExecution = requestWithControl(
        ToolNetworkRequest(url, method, headers, body, bodyIsJson, timeoutMillis, maxResponseBytes,
            acceptHttpErrors, resourceOwner), ToolNetworkStreamControl(),
    )

    internal suspend fun requestWithControl(options: ToolNetworkRequest, control: ToolNetworkStreamControl): NetworkExecution {
        val retained = mutableListOf<AutoCloseable>()
        val released = java.util.concurrent.atomic.AtomicBoolean()
        val release = { if (released.compareAndSet(false, true)) retained.forEach(AutoCloseable::close); Unit }
        var transferred = false
        try {
            val stream = openStream(options, control)
            val output = ByteArrayOutputStream()
            while (true) {
                val chunk = stream.read(NetworkResources.AUTO_CHUNK_BYTES)
                try {
                    if (chunk.done) break
                    if (output.size().toLong() + chunk.data.size > Int.MAX_VALUE - 8L) {
                        throw ToolNetworkFailure("RESULT_TOO_LARGE")
                    }
                    // Covers growing output, final byte-array copy and UTF-16/Base64/JSON materialization.
                    retained += resources.reserveExact(chunk.data.size.toLong() * 8)
                    output.write(chunk.data)
                } finally { chunk.release() }
            }
            val bytes = output.toByteArray()
            val media = stream.response.body.contentType()
            val subtype = media?.subtype?.lowercase(Locale.ROOT)
            val text = media == null || media.type.equals("text", true) || subtype == "json" || subtype?.endsWith("+json") == true
            return NetworkExecution.Success(
                statusCode = stream.response.code,
                finalUrl = stream.finalUrl,
                contentType = stream.response.header("Content-Type")?.substringBefore(';'),
                body = if (text) bytes.toString(Charsets.UTF_8) else Base64.getEncoder().encodeToString(bytes),
                bodyEncoding = if (text) NetworkBodyEncoding.TEXT else NetworkBodyEncoding.BASE64,
                headers = stream.response.exposedHeaders(),
                release = release,
            ).also { transferred = true }
        } catch (error: IOException) {
            val failure = error.toStreamFailure()
            return if (failure.retryable) NetworkExecution.RetryableFailure(failure.code)
            else NetworkExecution.TerminalFailure(failure.code)
        } finally {
            control.cancel()
            if (!transferred) release()
        }
    }

    internal suspend fun openStream(options: ToolNetworkRequest, control: ToolNetworkStreamControl): ToolNetworkStream {
        try {
            if (options.timeoutMillis < 0 || options.timeoutMillis > Int.MAX_VALUE) throw ToolNetworkFailure("INVALID_TIMEOUT")
            if (options.maxResponseBytes != null && options.maxResponseBytes !in 1..MAX_SAFE_NETWORK_BYTES) {
                throw ToolNetworkFailure("INVALID_RESPONSE_LIMIT")
            }
            control.attach(resources.admit(options.resourceOwner,
                NetworkResources.OPERATION_BYTES + (options.body?.size?.toLong() ?: 0) * 2, control))
            return openResponse(options, control)
        } catch (error: Exception) { control.cancel(); throw error }
    }

    private suspend fun openResponse(options: ToolNetworkRequest, control: ToolNetworkStreamControl): ToolNetworkStream {
        val requestClient = if (transport == null) clientForRequest(options.timeoutMillis) else null
        var current = options.url.toHttpUrlOrNull() ?: throw ToolNetworkFailure("INVALID_URL")
        var currentMethod = options.method
        var currentBody = options.body
        var currentHeaders = sanitizedRequestHeaders(options.headers)
        val visited = mutableSetOf<String>()
        while (true) {
            if (!visited.add("$currentMethod $current")) throw ToolNetworkFailure("REDIRECT_LOOP")
            control.requireActive()
            NetworkPolicy.validateEndpoint(current)?.let { throw ToolNetworkFailure(it) }
            val request = Request.Builder().url(current).apply {
                currentHeaders.forEach { (name, value) -> header(name, value) }
                val media = currentHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.toMediaTypeOrNull()
                    ?: if (options.bodyIsJson) JSON_MEDIA_TYPE else TEXT_MEDIA_TYPE
                when (currentMethod) {
                    NetworkRequestMethod.GET -> get()
                    NetworkRequestMethod.HEAD -> head()
                    NetworkRequestMethod.POST -> post((currentBody ?: ByteArray(0)).toRequestBody(media))
                    NetworkRequestMethod.PUT -> put((currentBody ?: ByteArray(0)).toRequestBody(media))
                    NetworkRequestMethod.PATCH -> patch((currentBody ?: ByteArray(0)).toRequestBody(media))
                    NetworkRequestMethod.DELETE -> if (currentBody == null) delete() else delete(currentBody.toRequestBody(media))
                }
                if (currentHeaders.keys.none { it.equals("User-Agent", true) }) header("User-Agent", "ToolBox (Android)")
                if (currentHeaders.keys.none { it.equals("Accept", true) }) header("Accept", "application/json, text/plain;q=0.9, text/*;q=0.8, */*;q=0.5")
            }.build()
            val response = try {
                transport?.execute(request, options.timeoutMillis)
                    ?: requireNotNull(requestClient).newCall(request).also(control::attach).awaitHeaders()
            } catch (error: IOException) { control.requireActive(); throw error.toStreamFailure() }
            control.attach(response)
            if (response.code == 407) throw ToolNetworkFailure("PROXY_AUTHENTICATION_REQUIRED")
            if (response.code in REDIRECT_CODES) {
                response.use {
                    val location = it.header("Location") ?: throw ToolNetworkFailure("INVALID_REDIRECT")
                    val redirected = current.resolve(location) ?: throw ToolNetworkFailure("INVALID_REDIRECT")
                    if (!sameOrigin(current, redirected)) currentHeaders = currentHeaders.filterKeys {
                        it.lowercase(Locale.ROOT) in CROSS_ORIGIN_HEADERS
                    }
                    if (it.code in setOf(301, 302, 303) && currentMethod !in setOf(NetworkRequestMethod.GET, NetworkRequestMethod.HEAD)) {
                        currentMethod = NetworkRequestMethod.GET
                        currentBody = null
                        currentHeaders = currentHeaders.filterKeys { it.lowercase(Locale.ROOT) !in BODY_HEADERS }
                    }
                    current = redirected
                }
                continue
            }
            if (!options.acceptHttpErrors && response.code !in 200..299) {
                throw ToolNetworkFailure("HTTP_${response.code}", retryable = response.code in 500..599)
            }
            if (options.maxResponseBytes != null && response.body.contentLength() > options.maxResponseBytes) {
                throw ToolNetworkFailure("RESULT_TOO_LARGE")
            }
            return ToolNetworkStream(response, current.toString(), control, options.maxResponseBytes, resources)
        }
    }
}

/** Re-read the platform selector for every route, including changes after this client was created. */
internal object LiveSystemProxySelector : ProxySelector() {
    override fun select(uri: URI): List<Proxy> = ProxySelector.getDefault()?.takeUnless { it === this }?.select(uri)
        ?.takeIf { it.isNotEmpty() } ?: listOf(Proxy.NO_PROXY)
    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
        ProxySelector.getDefault()?.takeUnless { it === this }?.connectFailed(uri, sa, ioe)
    }
}

// Deterministic redirect table: retain only these non-credential standard request headers across origins.
// Authorization, Cookie, Proxy-Authorization, Origin, Referer and ALL unclassified/custom fields (including
// X-API-Key) are removed. Removed fields are never restored on a return redirect. The destination is not allowlisted.
internal val CROSS_ORIGIN_HEADERS = setOf(
    "accept", "accept-encoding", "accept-language", "range", "if-range", "if-match", "if-none-match",
    "if-modified-since", "if-unmodified-since", "cache-control", "pragma", "user-agent",
    "content-type", "content-language", "content-encoding",
)
private val BODY_HEADERS = setOf("content-type", "content-length", "content-encoding", "content-language", "content-location", "digest")
private val HOP_HEADERS = setOf("connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length")
private fun sanitizedRequestHeaders(headers: Map<String, String>): Map<String, String> {
    val nominated = headers.entries.filter { it.key.equals("Connection", true) }
        .flatMap { it.value.split(',') }.map { it.trim().lowercase(Locale.ROOT) }.toSet()
    return headers.filterKeys { it.lowercase(Locale.ROOT) !in HOP_HEADERS + nominated }
}
private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val TEXT_MEDIA_TYPE = "text/plain; charset=utf-8".toMediaType()
private val HIDDEN_RESPONSE_HEADERS = HOP_HEADERS + setOf("set-cookie", "set-cookie2")
private fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean = first.scheme == second.scheme && first.host == second.host && first.port == second.port

internal object NetworkPolicy {
    fun validateEndpoint(url: HttpUrl): String? {
        if (!url.isHttps) return "HTTPS_REQUIRED"
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return "URL_CREDENTIALS_FORBIDDEN"
        return null
    }
}

enum class NetworkRequestMethod { GET, POST, PUT, PATCH, DELETE, HEAD }
enum class NetworkBodyEncoding { TEXT, BASE64 }
sealed interface NetworkExecution {
    data class Success(val statusCode: Int, val finalUrl: String, val contentType: String?, val body: String,
        val bodyEncoding: NetworkBodyEncoding = NetworkBodyEncoding.TEXT, val headers: Map<String, String> = emptyMap(),
        val release: () -> Unit = {}) : NetworkExecution
    data class RetryableFailure(val errorCode: String) : NetworkExecution
    data class TerminalFailure(val errorCode: String) : NetworkExecution
}
internal fun IOException.toStreamFailure(): ToolNetworkFailure = when {
    this is ToolNetworkFailure -> this
    // OkHttp CONNECT authentication failures do not expose a Response.
    (message?.contains("407") == true || message == "Failed to authenticate with proxy") -> ToolNetworkFailure("PROXY_AUTHENTICATION_REQUIRED")
    this is InterruptedIOException -> ToolNetworkFailure("NETWORK_TIMEOUT", retryable = true)
    else -> ToolNetworkFailure("NETWORK_IO", retryable = true)
}
internal fun Response.exposedHeaders(): Map<String, String> = headers.names().asSequence()
    .filterNot { it.lowercase(Locale.ROOT) in HIDDEN_RESPONSE_HEADERS }
    .mapNotNull { name -> headers[name]?.let { name to it } }.toMap(linkedMapOf())

private suspend fun Call.awaitHeaders(): Response = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }
        override fun onResponse(call: Call, response: Response) {
            // The callback only transfers ownership. A slow body never occupies OkHttp's dispatcher slot.
            continuation.resume(response, onCancellation = { _, value, _ -> value.closeOnIo() })
        }
    })
}

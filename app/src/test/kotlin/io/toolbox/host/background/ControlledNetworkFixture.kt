package io.toolbox.host.background

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Real TLS and CONNECT sockets without a public service or extra test dependency. */
internal class ControlledNetworkFixture : AutoCloseable {
    data class Request(val target: String, val headers: Map<String, String>)
    private val executor = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val servers = mutableListOf<ServerSocket>()
    val failures = CopyOnWriteArrayList<Throwable>()
    val ssl: SSLContext
    val trust: X509TrustManager

    init {
        val store = KeyStore.getInstance("PKCS12").apply {
            ControlledNetworkFixture::class.java.getResourceAsStream("/network/localhost.p12").use { load(requireNotNull(it), "test-only".toCharArray()) }
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "test-only".toCharArray()) }
        val trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        trust = trusts.trustManagers.filterIsInstance<X509TrustManager>().single()
        ssl = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trusts.trustManagers, null) }
    }

    fun proxy(resources: NetworkResources = NetworkResources(availableHeap = { 256L * 1024 * 1024 })) =
        ToolNetworkProxy(resources) { it.sslSocketFactory(ssl.socketFactory, trust) }

    fun https(handler: (Request, Socket) -> Unit): String {
        val server = ssl.serverSocketFactory.createServerSocket(0, 200, InetAddress.getLoopbackAddress())
        listen(server) { socket -> handler(readRequest(socket), socket) }
        return "https://localhost:${server.localPort}"
    }

    fun connectProxy(connects: MutableList<String>, authenticate: Boolean = false): Int {
        val server = ServerSocket(0, 200, InetAddress.getLoopbackAddress())
        listen(server) { client ->
            val request = readRequest(client)
            connects.add(request.target)
            if (authenticate) {
                client.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=fixture\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } else {
                val target = request.target.split(':')
                val remote = Socket(target[0], target[1].toInt()).also(sockets::add)
                client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                client.getOutputStream().flush()
                executor.execute { runCatching { client.getInputStream().copyTo(remote.getOutputStream()) }; runCatching { remote.close() } }
                runCatching { remote.getInputStream().copyTo(client.getOutputStream()) }
                remote.close()
            }
        }
        return server.localPort
    }

    private fun listen(server: ServerSocket, handler: (Socket) -> Unit) {
        servers += server
        executor.execute {
            while (!server.isClosed) {
                val socket = try { server.accept().also(sockets::add) } catch (_: Exception) { break }
                executor.execute {
                    socket.use {
                        try { handler(it) } catch (error: Exception) {
                            // Cancellation and rejection deliberately close sockets during TLS/body IO.
                            if (error !is java.io.IOException) failures.add(error)
                        }
                    }
                    sockets.remove(socket)
                }
            }
        }
    }

    private fun readRequest(socket: Socket): Request {
        // Read one byte at a time: a CONNECT parser must not prefetch tunneled TLS bytes.
        fun line(): String {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val byte = socket.getInputStream().read()
                if (byte < 0) throw java.io.EOFException()
                if (byte == 10) return out.toString(Charsets.US_ASCII).trimEnd('\r')
                out.write(byte)
            }
        }
        val first = line().split(' ')
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = line()
            if (line.isEmpty()) break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        val count = headers["content-length"]?.toInt() ?: 0
        repeat(count) { if (socket.getInputStream().read() < 0) throw java.io.EOFException() }
        return Request(first[1], headers)
    }

    fun respond(socket: Socket, status: Int = 200, headers: Map<String, String> = emptyMap(), body: String = "ok") {
        val bytes = body.toByteArray()
        val head = buildString {
            append("HTTP/1.1 $status Fixture\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        socket.getOutputStream().write(head.toByteArray())
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    override fun close() {
        servers.forEach { runCatching { it.close() } }
        sockets.forEach { runCatching { it.close() } }
        executor.shutdownNow()
    }
}

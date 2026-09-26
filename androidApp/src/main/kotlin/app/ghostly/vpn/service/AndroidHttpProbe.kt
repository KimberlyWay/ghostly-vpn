package app.ghostly.vpn.service

import app.ghostly.core.JsonX
import app.ghostly.core.vpn.Probe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI

/**
 * HTTP latency probes with our own request (GET or HEAD, see [Probe]) — libv2ray's built-in delay
 * test always sends its own request, so for HEAD we measure through a SOCKS port ourselves.
 */
object AndroidHttpProbe {

    /** Best of two requests through a loopback SOCKS port, ms; negative on failure. */
    fun socks(port: Int, url: String): Long {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        var best = -1L
        repeat(2) {
            val t0 = System.nanoTime()
            val ok = runCatching {
                val c = URI(url).toURL().openConnection(proxy) as HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 6000
                c.instanceFollowRedirects = false
                c.requestMethod = Probe.httpMethod
                val code = c.responseCode
                c.disconnect()
                code in 200..399
            }.getOrDefault(false)
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ok && (best < 0 || ms < best)) best = ms
        }
        return best
    }

    /** A throwaway Xray instance with one SOCKS inbound in front of [pingConfig]'s outbound. */
    fun viaXray(pingConfig: JsonObject, url: String): Long {
        val port = ServerSocket(0).use { it.localPort }
        val config = JsonObject(
            pingConfig + ("inbounds" to JsonArray(listOf(buildJsonObject {
                put("listen", "127.0.0.1")
                put("port", port)
                put("protocol", "socks")
                putJsonObject("settings") { put("udp", false) }
            }))),
        )
        val core = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(code: Long, message: String?): Long = 0
        })
        return try {
            core.startLoop(JsonX.encodeToString(JsonObject.serializer(), config), 0)
            if (!waitForPort(port, 4000)) -1L else socks(port, url)
        } catch (_: Exception) {
            -1L
        } finally {
            runCatching { core.stopLoop() }
        }
    }

    private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess) return true
            Thread.sleep(25)
        }
        return false
    }
}

package app.ghostly.core

import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.RoutingMode
import app.ghostly.core.sub.SubscriptionParser
import app.ghostly.core.xray.Ingress
import app.ghostly.core.xray.LocalProxy
import app.ghostly.core.xray.XrayConfigBuilder
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test

/**
 * Writes the configs the app would build for a real subscription (GHOSTLY_SUB_JSON = path to a
 * `?fmt=xray` dump) under several settings into build/configs, so `xray run -test` can check them.
 * No-op when the env var is missing (CI).
 */
class DumpConfigs {
    @Test
    fun dump() {
        val src = System.getenv("GHOSTLY_SUB_JSON") ?: return
        val parsed = SubscriptionParser.parse(File(src).readText(), emptyMap(), "p")
        val out = File("build/configs").apply { deleteRecursively(); mkdirs() }
        val variants = mapOf(
            "default" to AppSettings(),
            "ads" to AppSettings(blockAds = true),
            "global" to AppSettings(routingMode = RoutingMode.GLOBAL),
            "rules" to AppSettings(directDomains = listOf("example.ru"), proxyDomains = listOf("youtube.com"), blockDomains = listOf("ads.example")),
            "fragment_mux" to AppSettings(fragment = true, mux = true),
        )
        parsed.servers.forEachIndexed { i, server ->
            variants.forEach { (name, settings) ->
                val cfg = XrayConfigBuilder.build(server, settings, Ingress.Proxy(LocalProxy(20808 + i, 21808 + i, user = "u", pass = "p"), 0))
                File(out, "s${i}_$name.json").writeText(JsonX.encodeToString(JsonObject.serializer(), cfg))
            }
        }
        println("dumped ${parsed.servers.size * variants.size} configs")
    }
}

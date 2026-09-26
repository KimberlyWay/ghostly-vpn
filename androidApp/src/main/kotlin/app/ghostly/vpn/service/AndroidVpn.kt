package app.ghostly.vpn.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import app.ghostly.core.JsonX
import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.Server
import app.ghostly.core.vpn.Traffic
import app.ghostly.core.vpn.VpnBackend
import app.ghostly.core.vpn.VpnState
import app.ghostly.core.xray.XrayConfigBuilder
import go.Seq
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import libv2ray.Libv2ray
import java.io.File

/** What the service should bring up next. Lives in-process; the service reads it on start. */
data class TunnelRequest(val server: Server, val settings: AppSettings)

/**
 * Android [VpnBackend]: hands the request to [GhostlyVpnService] (a foreground VpnService that owns
 * the TUN fd and the Xray core) and mirrors its state for the UI.
 */
object AndroidVpn : VpnBackend {

    private lateinit var app: Context

    internal val mutableState = MutableStateFlow<VpnState>(VpnState.Idle)
    override val state: StateFlow<VpnState> = mutableState.asStateFlow()

    internal val mutableTraffic = MutableStateFlow(Traffic())
    override val traffic: StateFlow<Traffic> = mutableTraffic.asStateFlow()

    @Volatile internal var request: TunnelRequest? = null

    /** Loopback SOCKS into the running core, for our own requests (subscriptions, pings). */
    @Volatile override var appPort: Int? = null
        internal set

    /** Installed by the activity: launches the system VPN consent dialog. */
    @Volatile var permissionLauncher: ((CompletableDeferred<Boolean>) -> Unit)? = null

    private var coreReady = false

    fun init(context: Context) {
        app = context.applicationContext
        ensureCore()
    }

    @Synchronized
    internal fun ensureCore() {
        if (coreReady) return
        // gomobile's asset reader needs the context; geo files fall back to the aar assets
        // unless newer ones were downloaded into files/assets.
        Seq.setContext(app)
        val assets = File(app.filesDir, "assets").apply { mkdirs() }
        Libv2ray.initCoreEnv(assets.absolutePath, "")
        coreReady = true
    }

    override fun needsPermission(): Boolean = VpnService.prepare(app) != null

    override suspend fun requestPermission(): Boolean {
        if (!needsPermission()) return true
        val launcher = permissionLauncher ?: return false
        val result = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) { launcher(result) }
        return result.await()
    }

    override suspend fun connect(server: Server, settings: AppSettings) {
        request = TunnelRequest(server, settings)
        mutableState.value = VpnState.Connecting
        val intent = Intent(app, GhostlyVpnService::class.java).setAction(GhostlyVpnService.ACTION_START)
        ContextCompat.startForegroundService(app, intent)
    }

    override suspend fun disconnect() {
        if (state.value == VpnState.Idle) return
        mutableState.value = VpnState.Disconnecting
        app.startService(Intent(app, GhostlyVpnService::class.java).setAction(GhostlyVpnService.ACTION_STOP))
    }

    override suspend fun ping(server: Server, url: String): Long = withContext(Dispatchers.IO) {
        val config = XrayConfigBuilder.buildPing(server) ?: return@withContext -1L
        try {
            Libv2ray.measureOutboundDelay(JsonX.encodeToString(JsonObject.serializer(), config), url)
        } catch (_: Exception) {
            -1L
        }
    }

    /** The running core, set by the service while connected. */
    @Volatile internal var liveCore: libv2ray.CoreController? = null

    override suspend fun healthCheck(url: String): Long = withContext(Dispatchers.IO) {
        val core = liveCore ?: return@withContext -1L
        try {
            core.measureDelay(url)
        } catch (_: Exception) {
            -1L
        }
    }

    override fun coreVersion(): String = runCatching { Libv2ray.checkVersionX() }.getOrDefault("Xray")
}

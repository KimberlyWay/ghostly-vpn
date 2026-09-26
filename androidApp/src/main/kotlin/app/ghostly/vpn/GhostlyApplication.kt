package app.ghostly.vpn

import android.annotation.SuppressLint
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import app.ghostly.core.GhostlyController
import app.ghostly.core.vpn.AppEntry
import app.ghostly.core.vpn.PlatformInfo
import app.ghostly.vpn.service.AndroidVpn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GhostlyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        AndroidVpn.init(this)
    }

    val platform by lazy { AndroidPlatform(this) }

    val controller by lazy { GhostlyController(platform, AndroidVpn) }

    companion object {
        lateinit var instance: GhostlyApplication
            private set
    }
}

class AndroidPlatform(private val context: Context) : PlatformInfo {
    override val os = "Android"
    override val osVersion: String = Build.VERSION.RELEASE
    override val deviceModel: String = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL)
        .distinct().joinToString(" ")

    @SuppressLint("HardwareIds")
    override val hwid: String = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        ?.takeIf { it.length >= 8 } ?: "ghostly-${Build.FINGERPRINT.hashCode().toUInt()}"

    override val appVersion: String = BuildConfig.VERSION_NAME
    override val dataDir: String = context.filesDir.absolutePath
    override val supportsPerAppSplit = true

    /** Set by the visible activity: QR scanner needs an activity to launch from. */
    var activityQrScanner: ((onResult: (String) -> Unit) -> Unit)? = null

    override val qrScanner: ((onResult: (String) -> Unit) -> Unit)?
        get() = activityQrScanner

    override val systemVpnSettings: (() -> Unit) = {
        context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun copyToClipboard(text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Ghostly", text))
    }

    override fun readClipboard(): String? {
        val cm = context.getSystemService(ClipboardManager::class.java)
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }

    override fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun haptic() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
        }
        if (Build.VERSION.SDK_INT >= 29) vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        else vibrator.vibrate(VibrationEffect.createOneShot(12, 80))
    }

    override fun lanAddress(): String? = runCatching {
        // Real Wi-Fi/Ethernet address: skip loopback, down and virtual adapters (our own TUN too).
        val virtual = Regex("(?i)tun|tap|xray|ghostly|wintun|utun|vbox|vmware|veth|docker|br-|virtual|hyper-v|loopback|zerotier|tailscale|radmin|hamachi")
        java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback && !virtual.containsMatchIn(it.name + " " + (it.displayName ?: "")) }
            .flatMap { java.util.Collections.list(it.inetAddresses) }
            .filterIsInstance<java.net.Inet4Address>()
            .map { it.hostAddress }
            .filter { it.startsWith("192.168.") || it.startsWith("10.") || Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(it) }
            .filterNot { it.startsWith("172.19.0.") } // our tunnel subnet
            .sortedBy { if (it.startsWith("192.168.")) 0 else 1 }
            .firstOrNull()
    }.getOrNull()

    override fun isPortFree(port: Int, listen: String): Boolean = runCatching {
        java.net.ServerSocket().use { it.reuseAddress = false; it.bind(java.net.InetSocketAddress(listen, port)) }
        // 0.0.0.0 can bind next to another app's 127.0.0.1 listener on Windows — check loopback too.
        if (listen == "0.0.0.0") java.net.ServerSocket().use { it.bind(java.net.InetSocketAddress("127.0.0.1", port)) }
        true
    }.getOrDefault(false)

    override suspend fun tcpPing(host: String, port: Int, timeoutMs: Int): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val addr = java.net.InetSocketAddress(host, port) // DNS outside the timer
            if (addr.isUnresolved) return@runCatching -1L
            java.net.Socket().use { s ->
                val t0 = System.nanoTime()
                s.connect(addr, timeoutMs)
                ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)
            }
        }.getOrDefault(-1L)
    }

    /** Underlying network (not our VPN): Wi-Fi vs mobile decides white-list behaviour. */
    override fun networkType(): app.ghostly.core.vpn.NetType = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val nets = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
            .filter {
                it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            }
        when {
            nets.any { it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) } -> app.ghostly.core.vpn.NetType.WIFI
            nets.any { it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) } -> app.ghostly.core.vpn.NetType.ETHERNET
            nets.any { it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) } -> app.ghostly.core.vpn.NetType.CELLULAR
            else -> app.ghostly.core.vpn.NetType.UNKNOWN
        }
    }.getOrDefault(app.ghostly.core.vpn.NetType.UNKNOWN)

    override val updateAsset: String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "Ghostly-Android.apk"
        "armeabi-v7a" -> "Ghostly-Android-armv7.apk"
        else -> "Ghostly-Android-universal.apk"
    }

    override suspend fun downloadVerified(urls: List<String>, sha256: String, size: Long, onProgress: (Float) -> Unit): String =
        downloadVerifiedTo(java.io.File(context.cacheDir, "updates/$updateAsset"), urls, sha256, size, onProgress)

    override fun installUpdate(path: String) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            throw IllegalStateException("Разрешите Ghostly устанавливать приложения и нажмите «Обновить» ещё раз")
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.updates", java.io.File(path))
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override suspend fun installedApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != context.packageName }
            .map { info ->
                AppEntry(
                    packageName = info.packageName,
                    label = info.loadLabel(pm).toString(),
                    isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 && info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0,
                )
            }
    }
}

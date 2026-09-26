package app.ghostly.desktop

import app.ghostly.core.vpn.PlatformInfo
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.security.MessageDigest

enum class HostOs { WINDOWS, MACOS, LINUX }

val hostOs: HostOs = System.getProperty("os.name").lowercase().let {
    when {
        "win" in it -> HostOs.WINDOWS
        "mac" in it -> HostOs.MACOS
        else -> HostOs.LINUX
    }
}

class DesktopPlatform : PlatformInfo {
    override val os: String = when (hostOs) {
        HostOs.WINDOWS -> "Windows"
        HostOs.MACOS -> "macOS"
        HostOs.LINUX -> "Linux"
    }
    override val osVersion: String = System.getProperty("os.version")
    override val deviceModel: String = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("PC")
    override val appVersion: String = System.getProperty("jpackage.app-version") ?: "0.1.0-dev"
    override val isDesktop = true

    override val dataDir: String = when (hostOs) {
        HostOs.WINDOWS -> File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Ghostly")
        HostOs.MACOS -> File(System.getProperty("user.home"), "Library/Application Support/Ghostly")
        HostOs.LINUX -> File(System.getenv("XDG_DATA_HOME") ?: (System.getProperty("user.home") + "/.local/share"), "ghostly")
    }.apply { mkdirs() }.absolutePath

    /** Stable machine id (MachineGuid / IOPlatformUUID / machine-id), hashed so the raw id never leaves the PC. */
    override val hwid: String by lazy {
        val raw = runCatching {
            when (hostOs) {
                HostOs.WINDOWS -> exec("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid")
                    .lineSequence().firstOrNull { "MachineGuid" in it }?.trim()?.split(Regex("\\s+"))?.lastOrNull()
                HostOs.MACOS -> exec("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
                    .lineSequence().firstOrNull { "IOPlatformUUID" in it }?.substringAfterLast("= ")?.trim('"', ' ')
                HostOs.LINUX -> File("/etc/machine-id").takeIf { it.isFile }?.readText()?.trim()
            }
        }.getOrNull() ?: (System.getProperty("user.name") + deviceModel)
        MessageDigest.getInstance("SHA-256").digest(("ghostly:" + raw).toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
    }

    override fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }.onFailure {
            when (hostOs) {
                HostOs.LINUX -> ProcessBuilder("xdg-open", url).start()
                HostOs.MACOS -> ProcessBuilder("open", url).start()
                HostOs.WINDOWS -> ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
            }
        }
    }

    override fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
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

    /** Autostart entry: HKCU Run key / LaunchAgent / XDG autostart, launching minimized to tray. */
    override fun setStartOnBoot(enabled: Boolean) {
        val exe = ProcessHandle.current().info().command().orElse(null) ?: return
        // Only meaningful for the installed app, not for `gradlew run`.
        if (exe.endsWith("java.exe") || exe.endsWith("/java")) return
        runCatching {
            when (hostOs) {
                HostOs.WINDOWS -> {
                    val key = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
                    if (enabled) com.sun.jna.platform.win32.Advapi32Util.registrySetStringValue(
                        com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, key, "Ghostly", "\"$exe\" --autostart",
                    )
                    else if (com.sun.jna.platform.win32.Advapi32Util.registryValueExists(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, key, "Ghostly"))
                        com.sun.jna.platform.win32.Advapi32Util.registryDeleteValue(com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, key, "Ghostly")
                }
                HostOs.MACOS -> {
                    val plist = File(System.getProperty("user.home"), "Library/LaunchAgents/app.ghostly.desktop.plist")
                    if (enabled) plist.apply { parentFile.mkdirs() }.writeText(
                        """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>app.ghostly.desktop</string>
<key>ProgramArguments</key><array><string>$exe</string><string>--autostart</string></array>
<key>RunAtLoad</key><true/>
</dict></plist>
""",
                    ) else plist.delete()
                }
                HostOs.LINUX -> {
                    val f = File(System.getProperty("user.home"), ".config/autostart/ghostly.desktop")
                    if (enabled) f.apply { parentFile.mkdirs() }.writeText(
                        "[Desktop Entry]\nType=Application\nName=Ghostly\nExec=\"$exe\" --autostart\nX-GNOME-Autostart-enabled=true\n",
                    )
                    else f.delete()
                }
            }
        }
    }

    override fun readClipboard(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()
}

internal fun exec(vararg cmd: String): String {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    return out
}

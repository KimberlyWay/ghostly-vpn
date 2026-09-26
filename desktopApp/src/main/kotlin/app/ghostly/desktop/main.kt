package app.ghostly.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.ghostly.core.GhostlyController
import app.ghostly.core.vpn.VpnState
import app.ghostly.ui.GhostlyApp
import kotlinx.coroutines.runBlocking
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    val platform = DesktopPlatform()
    var controller: GhostlyController? = null
    val raise = androidx.compose.runtime.mutableIntStateOf(0)
    // Second launch (e.g. a ghostly:// link) → hand the link to the running Ghostly and quit.
    val first = SingleInstance.acquire(platform.dataDir, args) { forwarded ->
        forwarded.firstOrNull { !it.startsWith("--") }?.let { link -> controller?.import(link) }
        raise.intValue++
    }
    if (!first) return
    if (!platform.portable) SingleInstance.registerUrlScheme()
    val backend = DesktopXrayBackend(platform)
    // TUN needs admin: relaunch the installed app elevated (UAC prompt) right away instead of failing on connect.
    val exe = ProcessHandle.current().info().command().orElse("")
    if (hostOs == HostOs.WINDOWS && exe.endsWith("Ghostly.exe", true) && "--elevated" !in args && !backend.isElevated()) {
        val saved = runCatching { java.io.File(platform.dataDir, "settings.json").readText() }.getOrDefault("")
        if ("\"desktopMode\":\"TUN\"" in saved.replace(" ", "")) {
            val argList = (args.toList() + "--elevated").joinToString(",") { "'" + it.replace("'", "''") + "'" }
            val ok = runCatching {
                ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command",
                    "Start-Process -FilePath '${exe.replace("'", "''")}' -Verb RunAs -ArgumentList $argList").start().waitFor() == 0
            }.getOrDefault(false)
            if (ok) {
                SingleInstance.release()
                return
            }
        }
    }
    val c = GhostlyController(platform, backend)
    controller = c
    // ghostly://… or a subscription URL passed on the command line (e.g. from a URL handler).
    args.firstOrNull { !it.startsWith("--") }?.let { c.import(it) }
    val autostart = "--autostart" in args
    if (autostart && c.settings.value.startOnBoot) {
        kotlinx.coroutines.runBlocking { c.connect() }
    }

    val icon = DesktopPlatform::class.java.classLoader.getResourceAsStream("ghostly.png")
        ?.use { ImageIO.read(it) }?.toComposeImageBitmap()?.let { BitmapPainter(it) }

    application {
        val state by c.state.collectAsState()
        val windowState = rememberWindowState(
            size = DpSize(1180.dp, 780.dp),
            position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center),
            isMinimized = autostart,
        )

        fun quit() {
            runBlocking { c.disconnect() }
            exitApplication()
        }
        platform.quitForUpdate = { javax.swing.SwingUtilities.invokeLater { quit() } }

        if (icon != null) {
            Tray(
                icon = icon,
                tooltip = if (state is VpnState.Connected) "Ghostly — защищено" else "Ghostly",
                onAction = { windowState.isMinimized = false },
                menu = {
                    Item(if (state is VpnState.Connected) "Отключить" else "Подключить", onClick = { c.toggle() })
                    Separator()
                    Item("Выход", onClick = ::quit)
                },
            )
        }

        Window(
            onCloseRequest = ::quit,
            state = windowState,
            title = "Ghostly",
            icon = icon,
        ) {
            window.minimumSize = java.awt.Dimension(380, 600)
            if (hostOs == HostOs.WINDOWS) WindowsChrome.darkTitleBar(window)
            androidx.compose.runtime.LaunchedEffect(raise.intValue) {
                if (raise.intValue > 0) {
                    windowState.isMinimized = false
                    window.toFront()
                    window.requestFocus()
                }
            }
            GhostlyApp(c)
        }
    }
}

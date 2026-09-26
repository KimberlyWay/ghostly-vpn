package app.ghostly.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.ghostly.core.GhostlyController
import app.ghostly.core.vpn.VpnState
import app.ghostly.ui.components.AccentButton
import app.ghostly.ui.components.AuroraBackground
import app.ghostly.ui.components.GhostMark
import app.ghostly.ui.components.SoftButton
import app.ghostly.ui.components.pressScale
import app.ghostly.ui.screens.HomeScreen
import app.ghostly.ui.screens.ServersScreen
import app.ghostly.ui.screens.SettingsScreen
import app.ghostly.ui.theme.Ghost
import app.ghostly.ui.theme.GhostlyTheme
import app.ghostly.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val SITE_URL = "https://ghostlinknex.online"

internal enum class Tab(val title: String) { HOME("Главная"), SERVERS("Серверы"), SETTINGS("Настройки") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GhostlyApp(controller: GhostlyController) {
    val settings by controller.settings.collectAsState()
    val onboarded by controller.onboarded.collectAsState()
    val state by controller.state.collectAsState()

    GhostlyTheme(settings.accent.argb, settings.reduceMotion) { androidx.compose.runtime.CompositionLocalProvider(app.ghostly.ui.components.LocalHaptic provides { controller.haptic() }) {
        var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
        var addOpen by remember { mutableStateOf(false) }
        var pickerOpen by remember { mutableStateOf(false) }
        var toast by remember { mutableStateOf<String?>(null) }
        var wideLayout by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            controller.events.collect { msg ->
                toast = msg
                delay(3200)
                if (toast == msg) toast = null
            }
        }
        LaunchedEffect(Unit) {
            if (settings.autoConnect && controller.state.value == VpnState.Idle && controller.selectedServer() != null) controller.connect()
        }

        AuroraBackground(energy = if (state is VpnState.Connected) 1f else 0f) {
            val insets = WindowInsets.safeDrawing.asPaddingValues()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 900.dp
                when {
                    !onboarded -> Onboarding(controller, onAdd = { addOpen = true })
                    wide -> DesktopShell(controller, tab, { tab = it }, onAdd = { addOpen = true })
                    else -> {
                        val pad = PaddingValues(top = insets.calculateTopPadding() + 8.dp, bottom = insets.calculateBottomPadding() + 96.dp)
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = { (fadeIn(Motion.quick(260)) + scaleIn(initialScale = 0.985f)) togetherWith fadeOut(Motion.quick(160)) },
                            modifier = Modifier.fillMaxSize(),
                        ) { t ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.widthIn(max = 620.dp).fillMaxSize()) {
                                    when (t) {
                                        Tab.HOME -> HomeScreen(controller, onPickServer = { pickerOpen = true }, contentPadding = pad)
                                        Tab.SERVERS -> ServersScreen(controller, pad, onAdd = { addOpen = true })
                                        Tab.SETTINGS -> SettingsScreen(controller, pad)
                                    }
                                }
                            }
                        }
                        TabBar(tab, { controller.haptic(); tab = it }, Modifier.align(Alignment.BottomCenter).padding(bottom = insets.calculateBottomPadding() + 14.dp))
                    }
                }
                SideEffect { wideLayout = wide }
            }

            // Toast
            AnimatedVisibility(
                toast != null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = insets.calculateTopPadding() + 10.dp, start = 16.dp, end = 16.dp),
            ) {
                var last by remember { mutableStateOf("") }
                toast?.let { last = it }
                Box(
                    Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xF01A1328))
                        .border(1.dp, Ghost.colors.accent.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
                        .clickable { toast = null }.padding(horizontal = 16.dp, vertical = 12.dp),
                ) { Text(last, style = MaterialTheme.typography.bodyMedium.copy(color = Ghost.colors.ink)) }
            }
        }

        if (addOpen && wideLayout) {
            Dialog(onDismissRequest = { addOpen = false }) {
                Box(
                    Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF130E1D))
                        .border(1.dp, Brush.verticalGradient(listOf(Ghost.colors.accent.copy(alpha = 0.4f), Color.White.copy(alpha = 0.05f))), RoundedCornerShape(28.dp))
                        .padding(top = 24.dp),
                ) { AddSheet(controller) { addOpen = false } }
            }
        } else if (addOpen) {
            ModalBottomSheet(
                onDismissRequest = { addOpen = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFF110C1A), scrimColor = Color.Black.copy(alpha = 0.55f),
            ) { AddSheet(controller) { addOpen = false } }
        }
        if (pickerOpen) {
            ModalBottomSheet(
                onDismissRequest = { pickerOpen = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
                containerColor = Color(0xFF110C1A), scrimColor = Color.Black.copy(alpha = 0.55f),
            ) {
                Text("Выбор сервера", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 22.dp))
                ServersScreen(
                    controller, PaddingValues(bottom = 24.dp),
                    onAdd = { pickerOpen = false; addOpen = true },
                    onPicked = { pickerOpen = false }, showHeader = false,
                )
            }
        }
    }
}}

// ---------------------------------------------------------------------------- tab bar

@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier) {
    val c = Ghost.colors
    Row(
        modifier
            .clip(RoundedCornerShape(30.dp))
            .background(Color(0xE6130E1D))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f))), RoundedCornerShape(30.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tab.entries.forEach { t ->
            val active = t == selected
            val interaction = remember { MutableInteractionSource() }
            val bg by animateColorAsState(if (active) c.accent.copy(alpha = 0.2f) else Color.Transparent, Motion.quick())
            val tint by animateColorAsState(if (active) c.accent else c.ink3, Motion.quick())
            val w by animateDpAsState(if (active) 122.dp else 56.dp, Motion.bouncy())
            Row(
                Modifier.width(w).height(48.dp).pressScale(interaction, 0.9f).clip(RoundedCornerShape(24.dp)).background(bg)
                    .clickable(interaction, null) { onSelect(t) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                if (t == Tab.HOME) GhostMark(Modifier.size(24.dp), happy = if (active) 1f else 0f, pokeable = false)
                else Icon(if (t == Tab.SERVERS) Icons.Rounded.Dns else Icons.Rounded.Settings, null, tint = tint, modifier = Modifier.size(22.dp))
                AnimatedVisibility(active, enter = fadeIn() + scaleIn(initialScale = 0.6f), exit = fadeOut() + scaleOut(targetScale = 0.6f)) {
                    Text(t.title, style = MaterialTheme.typography.labelMedium, color = tint, modifier = Modifier.padding(start = 7.dp), maxLines = 1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- onboarding

@Composable
private fun Onboarding(controller: GhostlyController, onAdd: () -> Unit) {
    val c = Ghost.colors
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        GhostMark(Modifier.size(150.dp))
        Spacer(Modifier.height(18.dp))
        Text("Ghostly VPN", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Свободный интернет без тормозов.\nYouTube, Instagram, Telegram — как будто ничего и не блокировали.",
            style = MaterialTheme.typography.bodyLarge.copy(color = c.ink2), textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(34.dp))
        Column(Modifier.widthIn(max = 380.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AccentButton("Вставить ссылку из буфера", {
                controller.haptic()
                controller.import(controller.platform.readClipboard().orEmpty())
            }, Modifier.fillMaxWidth(), Icons.Rounded.ContentPaste)
            SoftButton("Другие способы", onAdd, Modifier.fillMaxWidth(), Icons.Rounded.QrCodeScanner)
            SoftButton("Купить подписку", { controller.platform.openUrl(SITE_URL) }, Modifier.fillMaxWidth(), Icons.Rounded.ShoppingBag, tint = c.accent)
        }
        Spacer(Modifier.height(20.dp))
        Text("Подходит любая подписка VLESS / VMess / Trojan / Shadowsocks / Hysteria2", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------------------- add sheet

@Composable
private fun AddSheet(controller: GhostlyController, close: () -> Unit) {
    val c = Ghost.colors
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val done: (Boolean) -> Unit = { ok -> busy = false; if (ok) close() }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Text("Добавить", style = MaterialTheme.typography.headlineSmall)
        Text("Подписка, ссылка на сервер или JSON-конфиг", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SourceTile("Из буфера", Icons.Rounded.ContentPaste, Modifier.weight(1f)) {
                busy = true
                controller.import(controller.platform.readClipboard().orEmpty(), done)
            }
            controller.platform.qrScanner?.let { scan ->
                SourceTile("QR-код", Icons.Rounded.QrCodeScanner, Modifier.weight(1f)) {
                    scan { result -> busy = true; controller.import(result, done) }
                }
            }
            SourceTile("Купить", Icons.Rounded.ShoppingBag, Modifier.weight(1f)) { controller.platform.openUrl(SITE_URL) }
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(14.dp),
        ) {
            if (text.isEmpty()) Text("https://… или vless://…", style = MaterialTheme.typography.bodyMedium, color = c.ink3)
            BasicTextField(
                text, { text = it },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth().height(80.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        AccentButton(if (busy) "Загружаю…" else "Добавить", {
            busy = true
            controller.import(text, done)
        }, Modifier.fillMaxWidth(), enabled = text.isNotBlank() && !busy)
    }
}

@Composable
private fun SourceTile(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val c = Ghost.colors
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier.pressScale(interaction, 0.94f).clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.14f), c.accent2.copy(alpha = 0.06f))))
            .border(1.dp, c.accent.copy(alpha = 0.22f), RoundedCornerShape(20.dp))
            .clickable(interaction, null, onClick = onClick).padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

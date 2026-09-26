package app.ghostly.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ghostly.core.GhostlyController
import app.ghostly.core.model.Profile
import app.ghostly.core.model.Server
import app.ghostly.core.vpn.Traffic
import app.ghostly.core.vpn.VpnState
import app.ghostly.ui.Format
import app.ghostly.ui.components.ConnectOrb
import app.ghostly.ui.components.GlassCard
import app.ghostly.ui.components.GlowBar
import app.ghostly.ui.components.IconBubble
import app.ghostly.ui.components.OrbState
import app.ghostly.ui.components.PingPill
import app.ghostly.ui.components.RollingText
import app.ghostly.ui.components.Sparkline
import app.ghostly.ui.components.Tag
import app.ghostly.ui.components.appear
import app.ghostly.ui.protocolLabel
import app.ghostly.ui.theme.Ghost
import app.ghostly.ui.theme.Motion
import app.ghostly.ui.title
import app.ghostly.ui.transportLabel
import kotlinx.coroutines.delay

/** Everything the home screen shows, gathered once and shared by the phone and desktop layouts. */
@Stable
class HomeModel(
    val state: VpnState,
    val orb: OrbState,
    val traffic: Traffic,
    val server: Server?,
    val profile: Profile?,
    val ping: Long?,
    val pinging: Boolean,
    val now: Long,
    val down: SnapshotStateList<Float>,
    val up: SnapshotStateList<Float>,
)

@Composable
fun rememberHome(controller: GhostlyController): HomeModel {
    val state by controller.state.collectAsState()
    val traffic by controller.backend.traffic.collectAsState()
    val selectedId by controller.selectedServerId.collectAsState()
    val profiles by controller.profiles.collectAsState()
    val pings by controller.pings.collectAsState()
    val pinging by controller.pinging.collectAsState()

    val server = remember(selectedId, profiles) { controller.selectedServer() }
    val profile = remember(server, profiles) { server?.let { controller.profileOf(it.id) } ?: profiles.firstOrNull() }
    val orb = when (state) {
        is VpnState.Connected -> OrbState.CONNECTED
        VpnState.Connecting, VpnState.Disconnecting -> OrbState.CONNECTING
        is VpnState.Failed -> OrbState.ERROR
        VpnState.Idle -> OrbState.IDLE
    }
    val down = remember { mutableStateListOf<Float>() }
    val up = remember { mutableStateListOf<Float>() }
    LaunchedEffect(traffic) {
        down.add(traffic.downSpeed.toFloat()); if (down.size > 48) down.removeAt(0)
        up.add(traffic.upSpeed.toFloat()); if (up.size > 48) up.removeAt(0)
    }
    LaunchedEffect(orb) { if (orb != OrbState.CONNECTED) { down.clear(); up.clear() } }
    var now by remember { mutableLongStateOf(GhostlyController.now()) }
    LaunchedEffect(Unit) { while (true) { now = GhostlyController.now(); delay(1000) } }
    return HomeModel(state, orb, traffic, server, profile, pings[server?.id]?.ms, server?.id in pinging, now, down, up)
}

// ============================================================================ phone

@Composable
fun HomeScreen(controller: GhostlyController, onPickServer: () -> Unit, contentPadding: PaddingValues) {
    val m = rememberHome(controller)
    val c = Ghost.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            app.ghostly.ui.components.GhostMark(Modifier.size(44.dp), happy = if (m.orb == OrbState.CONNECTED) 1f else 0.3f)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Ghostly", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold))
                Text(m.profile?.name ?: "VPN", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            m.profile?.supportUrl?.let { url -> IconBubble(Icons.Rounded.SupportAgent, onClick = { controller.platform.openUrl(url) }) }
        }
        Spacer(Modifier.height(18.dp))
        HomeHero(m, controller, 236.dp)
        Spacer(Modifier.height(22.dp))
        AnimatedVisibility(m.orb == OrbState.CONNECTED, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SpeedCard("Загрузка", Icons.Rounded.ArrowDownward, m.traffic.downSpeed, m.traffic.downTotal, m.down, c.ok, Modifier.weight(1f).appear(0))
                SpeedCard("Отдача", Icons.Rounded.ArrowUpward, m.traffic.upSpeed, m.traffic.upTotal, m.up, c.accent, Modifier.weight(1f).appear(1))
            }
        }
        ServerCard(m, onPickServer, Modifier.appear(2))
        if (m.profile?.info != null) {
            Spacer(Modifier.height(12.dp))
            SubscriptionCard(m.profile, m.now, controller, Modifier.appear(3))
        }
        Spacer(Modifier.height(20.dp))
    }
}

// ============================================================================ desktop

/**
 * Wide layout: orb centre stage with live stats under it, the quick server panel on the right.
 */
@Composable
fun HomeDesktop(controller: GhostlyController, onAdd: () -> Unit) {
    val m = rememberHome(controller)
    val c = Ghost.colors
    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            // Top line: which subscription, and when it runs out.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Главная", style = MaterialTheme.typography.headlineMedium)
                    Text(m.profile?.name ?: "Добавь подписку, чтобы начать", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                m.profile?.supportUrl?.let { url -> IconBubble(Icons.Rounded.SupportAgent, onClick = { controller.platform.openUrl(url) }) }
            }
            Spacer(Modifier.height(12.dp))
            HomeHero(m, controller, 300.dp)
            Spacer(Modifier.height(26.dp))
            Row(Modifier.widthIn(max = 760.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SpeedCard("Загрузка", Icons.Rounded.ArrowDownward, m.traffic.downSpeed, m.traffic.downTotal, m.down, c.ok, Modifier.weight(1f).appear(0), dim = m.orb != OrbState.CONNECTED)
                SpeedCard("Отдача", Icons.Rounded.ArrowUpward, m.traffic.upSpeed, m.traffic.upTotal, m.up, c.accent, Modifier.weight(1f).appear(1), dim = m.orb != OrbState.CONNECTED)
                Column(Modifier.weight(0.8f).appear(2), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    StatTile(Icons.Rounded.NetworkPing, "Пинг", m.ping?.takeIf { it > 0 }?.let { "$it мс" } ?: "—", pingColor(m.ping))
                    StatTile(Icons.Rounded.Timer, "Сессия", (m.state as? VpnState.Connected)?.let { Format.duration(m.now - it.since) } ?: "—", c.ink)
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                if (m.profile?.info != null) SubscriptionCard(m.profile, m.now, controller, Modifier.appear(3))
            }
            Spacer(Modifier.height(10.dp))
        }
        // Right: servers always at hand.
        GlassCard(Modifier.width(400.dp).fillMaxHeight().appear(1), padding = 0.dp, strong = true) {
            Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Серверы", style = MaterialTheme.typography.titleLarge)
                    Text("Нажми, чтобы переключиться", style = MaterialTheme.typography.bodySmall)
                }
            }
            ServersScreen(controller, PaddingValues(bottom = 12.dp), onAdd = onAdd, showHeader = false, compact = true)
        }
    }
}

@Composable
private fun pingColor(ms: Long?): Color {
    val c = Ghost.colors
    return when {
        ms == null || ms <= 0 -> c.ink3
        ms < 180 -> c.ok
        ms < 450 -> c.warn
        else -> c.bad
    }
}

// ============================================================================ pieces

@Composable
fun HomeHero(m: HomeModel, controller: GhostlyController, orbSize: Dp) {
    val c = Ghost.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ConnectOrb(m.orb, onClick = {
            controller.haptic()
            controller.toggle()
        }, size = orbSize)
        Spacer(Modifier.height(14.dp))
        AnimatedContent(
            targetState = m.orb,
            transitionSpec = { (fadeIn(Motion.quick()) + slideInVertically { it / 3 }) togetherWith (fadeOut(Motion.quick(200)) + slideOutVertically { -it / 3 }) },
        ) { s ->
            Text(
                when (s) {
                    OrbState.IDLE -> "Не подключено"
                    OrbState.CONNECTING -> if (m.state == VpnState.Disconnecting) "Отключаюсь…" else "Подключаюсь…"
                    OrbState.CONNECTED -> "Ты под защитой"
                    OrbState.ERROR -> "Не удалось подключиться"
                },
                style = MaterialTheme.typography.headlineMedium,
                color = if (s == OrbState.ERROR) c.bad else c.ink,
            )
        }
        Spacer(Modifier.height(4.dp))
        when (val st = m.state) {
            is VpnState.Connected -> RollingText(
                Format.duration(m.now - st.since),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                color = c.ok,
            )
            is VpnState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    st.message, style = MaterialTheme.typography.bodySmall, color = c.ink3, textAlign = TextAlign.Center,
                    maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 420.dp),
                )
                Spacer(Modifier.height(8.dp))
                app.ghostly.ui.components.SoftButton("Скопировать ошибку", {
                    controller.platform.copyToClipboard(st.message)
                    controller.haptic()
                }, icon = Icons.Rounded.ContentCopy)
            }
            else -> Text(
                when (st) {
                    VpnState.Connecting -> "Устанавливаю туннель"
                    else -> if (controller.platform.isDesktop) "Нажми на призрака, чтобы включить" else "Коснись призрака, чтобы включить"
                },
                style = MaterialTheme.typography.bodyMedium, color = c.ink3, textAlign = TextAlign.Center, maxLines = 3,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
    }
}

@Composable
fun SpeedCard(
    label: String, icon: ImageVector, speed: Long, total: Long,
    history: List<Float>, color: Color, modifier: Modifier, dim: Boolean = false,
) {
    val c = Ghost.colors
    GlassCard(modifier, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.ink3)
        }
        Spacer(Modifier.height(10.dp))
        RollingText(
            if (dim) "—" else Format.speed(speed),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            color = if (dim) c.ink3 else c.ink,
        )
        Text(if (dim) "нет соединения" else "всего ${Format.bytes(total)}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Sparkline(history, color, Modifier.fillMaxWidth().height(36.dp))
    }
}

@Composable
private fun StatTile(icon: ImageVector, label: String, value: String, color: Color) {
    val c = Ghost.colors
    GlassCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = c.ink3, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.ink3)
        }
        Spacer(Modifier.height(6.dp))
        RollingText(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), color = color)
    }
}

@Composable
fun ServerCard(m: HomeModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ghost.colors
    val server = m.server
    GlassCard(modifier.fillMaxWidth(), onClick = onClick, strong = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ServerAvatar(server, 46.dp)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text("Сервер", style = MaterialTheme.typography.labelSmall, color = c.ink3)
                if (server == null) {
                    Text("Не выбран", style = MaterialTheme.typography.titleMedium)
                } else {
                    val t = server.title()
                    Text(t.title + (t.subtitle?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Tag(server.protocolLabel(), color = c.accent)
                        server.transportLabel()?.let { Tag(it) }
                    }
                }
            }
            if (server != null && !server.isAuto) PingPill(m.ping, m.pinging)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.ink3)
        }
    }
}

@Composable
fun ServerAvatar(server: Server?, size: Dp) {
    val c = Ghost.colors
    val flag = server?.title()?.flag
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.34f))
            .background(Brush.linearGradient(listOf(c.accent.copy(alpha = 0.26f), c.accent2.copy(alpha = 0.12f)))),
        contentAlignment = Alignment.Center,
    ) {
        when {
            flag != null -> Text(flag, fontSize = (size.value * 0.46f).sp)
            server?.isAuto == true -> Icon(Icons.Rounded.AutoAwesome, null, tint = c.accent, modifier = Modifier.size(size * 0.48f))
            server?.protocol == "hysteria" -> Icon(Icons.Rounded.Bolt, null, tint = c.warn, modifier = Modifier.size(size * 0.5f))
            else -> Icon(Icons.Rounded.Shield, null, tint = c.accent, modifier = Modifier.size(size * 0.46f))
        }
    }
}

@Composable
fun SubscriptionCard(profile: Profile, now: Long, controller: GhostlyController, modifier: Modifier = Modifier) {
    val c = Ghost.colors
    val info = profile.info ?: return
    GlassCard(modifier.fillMaxWidth(), onClick = profile.webPageUrl?.let { url -> { controller.platform.openUrl(url) } }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Подписка", style = MaterialTheme.typography.labelSmall, color = c.ink3)
                Text(
                    if (info.unlimitedTime) "Бессрочно" else Format.expiryPhrase(info.expire, now),
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        info.unlimitedTime -> c.ink
                        info.expire * 1000 <= now -> c.bad
                        info.expire * 1000 - now < 3 * 86_400_000L -> c.warn
                        else -> c.ink
                    },
                )
            }
            if (info.pools.isEmpty()) Text(
                if (info.unlimitedTraffic) "${Format.bytes(info.used)} · ∞" else "${Format.bytes(info.used)} из ${Format.bytes(info.total)}",
                style = MaterialTheme.typography.labelMedium, color = c.ink2,
            )
        }
        if (info.pools.isNotEmpty()) {
            info.pools.forEach { p ->
                Spacer(Modifier.height(12.dp))
                PoolBar(p)
            }
            if (info.cycleEnd > 0) {
                Spacer(Modifier.height(8.dp))
                Text("Трафик обновится через ${Format.remaining(info.cycleEnd, now)}", style = MaterialTheme.typography.bodySmall)
            }
        } else if (!info.unlimitedTraffic) {
            Spacer(Modifier.height(12.dp))
            val frac = info.used.toFloat() / info.total.toFloat()
            GlowBar(frac, if (frac > 0.9f) c.bad else if (frac > 0.7f) c.warn else c.accent)
        }
    }
}

/** One traffic pool: title, used / total and a glowing bar (mint for regular, violet for white lists). */
@Composable
fun PoolBar(p: app.ghostly.core.model.TrafficPool) {
    val c = Ghost.colors
    val frac = if (p.total > 0) p.used.toFloat() / p.total else 0f
    val color = when {
        frac > 0.95f -> c.bad
        frac > 0.8f -> c.warn
        p.id == "wl" -> c.accent
        else -> c.ok
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(p.title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Text(
            if (p.total > 0) "${Format.bytes(p.used)} из ${Format.bytes(p.total)}" else "${Format.bytes(p.used)} · ∞",
            style = MaterialTheme.typography.labelMedium, color = c.ink2,
        )
    }
    Spacer(Modifier.height(6.dp))
    GlowBar(frac, color)
}

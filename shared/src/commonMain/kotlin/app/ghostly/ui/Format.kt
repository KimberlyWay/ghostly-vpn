package app.ghostly.ui

import app.ghostly.core.model.Server
import kotlin.math.roundToLong

object Format {

    fun bytes(b: Long): String {
        if (b < 1024) return "$b Б"
        val units = listOf("КБ", "МБ", "ГБ", "ТБ")
        var v = b.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return "${oneDecimal(v)} ${units[i]}"
    }

    fun speed(bps: Long): String = if (bps < 1024) "$bps Б/с" else bytes(bps) + "/с"

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return "${two(h)}:${two(m)}:${two(sec)}"
    }

    /** "Осталось 3 дня" / "Истекла" — a full phrase for subscription cards. */
    fun expiryPhrase(expireSec: Long, nowMs: Long): String =
        if (expireSec * 1000 <= nowMs) "Подписка истекла" else "Осталось ${remaining(expireSec, nowMs)}"

    /** "3 дня", "21 день", "5 часов" — Russian plural forms. */
    fun remaining(expireSec: Long, nowMs: Long): String {
        val left = expireSec * 1000 - nowMs
        if (left <= 0) return "0 дней"
        val days = left / 86_400_000
        if (days >= 1) return "$days ${plural(days, "день", "дня", "дней")}"
        val hours = (left / 3_600_000).coerceAtLeast(1)
        return "$hours ${plural(hours, "час", "часа", "часов")}"
    }

    fun plural(n: Long, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1L && m100 != 11L -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
    }

    private fun two(v: Long) = v.toString().padStart(2, '0')

    private fun oneDecimal(v: Double): String {
        val r = (v * 10).roundToLong()
        return if (r % 10 == 0L || v >= 100) "${(v).roundToLong()}" else "${r / 10},${r % 10}"
    }
}

/** Server name split for display: flag emoji, title, subtitle ("Обычный · Стандарт" → "Обычный" / "Стандарт"). */
data class ServerTitle(val flag: String?, val title: String, val subtitle: String?)

fun Server.title(): ServerTitle {
    var n = name.trim()
    val flag = leadingFlag(n)
    if (flag != null) n = n.removePrefix(flag).trim()
    val parts = n.split(" · ", " | ", " - ").map { it.trim() }.filter { it.isNotEmpty() }
    return if (parts.size >= 2) ServerTitle(flag, parts.first(), parts.drop(1).joinToString(" · "))
    else ServerTitle(flag, n.ifEmpty { name }, null)
}

/** A regional-indicator pair at the start of the string (a flag emoji), if any. */
private fun leadingFlag(s: String): String? {
    if (s.length < 4) return null
    val a = s.codePointAtCompat(0)
    val b = s.codePointAtCompat(2)
    return if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) s.substring(0, 4) else null
}

private fun String.codePointAtCompat(i: Int): Int {
    val hi = this[i]
    if (hi.isHighSurrogate() && i + 1 < length) {
        val lo = this[i + 1]
        return ((hi.code - 0xD800) shl 10) + (lo.code - 0xDC00) + 0x10000
    }
    return hi.code
}

fun Server.protocolLabel(): String = when (protocol) {
    "vless" -> "VLESS"
    "vmess" -> "VMess"
    "trojan" -> "Trojan"
    "shadowsocks" -> "SS"
    "hysteria" -> "Hysteria2"
    "balancer" -> "Авто"
    else -> protocol.uppercase()
}

fun Server.transportLabel(): String? = when {
    isAuto -> "$transport узлов"
    protocol == "hysteria" -> "QUIC"
    security == "reality" -> "${transport?.uppercase()} · Reality"
    transport != null -> transport.uppercase()
    else -> null
}

package app.ghostly.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.ghostly.vpn.R

/**
 * The ongoing VPN notification, shared by the Xray and mihomo services: header = core with a
 * running connection timer, body = live speed, expanded = session traffic too; the core shows as a
 * badge on the right. With Xray the title is the server and the subscription joins the header.
 * With Mihomo traffic goes through the selectors (each may point at another country), so the
 * title is the subscription itself.
 */
internal object VpnNotification {

    const val CHANNEL = "vpn"

    enum class Core(val label: String, val color: Int, val glyph: Int) {
        XRAY("Xray", 0xFF4F7BFF.toInt(), R.drawable.ic_core_xray),
        MIHOMO("Mihomo", 0xFFB36BFF.toInt(), R.drawable.ic_core_mihomo),
    }

    class Stats(val up: Long, val down: Long, val upTotal: Long, val downTotal: Long)

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
    }

    /** [connectedAt] null means still connecting: no timer, [status] as the text. */
    fun build(
        context: Context,
        core: Core,
        server: String,
        subscription: String?,
        connectedAt: Long?,
        stats: Stats?,
        open: PendingIntent,
        stop: PendingIntent,
        status: String? = null,
    ): Notification {
        val speed = stats?.let { "↓ ${speed(it.down)}   ↑ ${speed(it.up)}" }
        val text = status ?: speed ?: context.getString(R.string.notif_connected)
        val sub = subscription?.takeIf { it.isNotBlank() }
        val bySubscription = core == Core.MIHOMO && sub != null
        val title = sub?.takeIf { bySubscription } ?: server.ifEmpty { context.getString(R.string.app_name) }
        val header = if (bySubscription) core.label else listOfNotNull(sub, core.label).joinToString(" · ")
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_ghost)
            .setColor(0xFFA88DFF.toInt())
            .setLargeIcon(badge(context, core))
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(header)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.notif_disconnect), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (connectedAt != null) {
            b.setWhen(connectedAt).setShowWhen(true).setUsesChronometer(true)
            val lines = listOfNotNull(
                text,
                stats?.let { "За сессию: ↓ ${bytes(it.downTotal)}  ↑ ${bytes(it.upTotal)}" },
                if (bySubscription) "Ядро: ${core.label} · маршруты по селекторам"
                else listOfNotNull(sub?.let { "Подписка: $it" }, "Ядро: ${core.label}").joinToString(" · "),
            )
            b.setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
        } else {
            b.setShowWhen(false)
        }
        return b.build()
    }

    private val badges = HashMap<Core, Bitmap>()

    /** A round coloured badge with the core's glyph, drawn once per core. */
    private fun badge(context: Context, core: Core): Bitmap = synchronized(badges) {
        badges.getOrPut(core) {
            val size = (48 * context.resources.displayMetrics.density).toInt().coerceAtLeast(96)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = core.color })
            ContextCompat.getDrawable(context, core.glyph)?.mutate()?.let { d ->
                val inset = (size * 0.24f).toInt()
                d.setBounds(inset, inset, size - inset, size - inset)
                d.draw(canvas)
            }
            bmp
        }
    }

    fun speed(bps: Long): String = when {
        bps < 1024 -> "$bps Б/с"
        bps < 1024 * 1024 -> "${bps / 1024} КБ/с"
        else -> String.format(java.util.Locale.US, "%.1f МБ/с", bps / 1048576.0)
    }

    fun bytes(n: Long): String = when {
        n < 1024 -> "$n Б"
        n < 1024 * 1024 -> "${n / 1024} КБ"
        n < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f МБ", n / 1048576.0)
        else -> String.format(java.util.Locale.US, "%.2f ГБ", n / 1073741824.0)
    }
}

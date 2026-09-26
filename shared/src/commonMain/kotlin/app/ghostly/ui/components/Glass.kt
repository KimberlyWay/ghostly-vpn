package app.ghostly.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ghostly.ui.theme.Ghost
import app.ghostly.ui.theme.LocalReduceMotion
import app.ghostly.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The site's backdrop: deep violet night, slowly drifting aurora blobs and twinkling "ghost dust".
 * [energy] 0..1 brightens it (connected state) and pulls a blob towards mint.
 */
@Composable
fun AuroraBackground(energy: Float, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = Ghost.colors
    val reduce = LocalReduceMotion.current
    val t = rememberInfiniteTransition()
    val phase by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(if (reduce) 70_000 else 30_000, easing = LinearEasing)))
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (reduce) 120_000 else 60_000, easing = LinearEasing)))
    val e by animateFloatAsState(energy, tween(1400, easing = Motion.Ease))
    val stars = remember {
        val r = Random(7)
        List(70) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 1.6f, r.nextFloat() * 6.28f, 0.4f + r.nextFloat()) }
    }
    Box(modifier.fillMaxSize().background(c.bg)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val m = maxOf(w, h)
            fun blob(cx: Float, cy: Float, r: Float, color: Color, alpha: Float) {
                drawCircle(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.4f), Color.Transparent),
                        center = Offset(cx, cy), radius = r,
                    ),
                    radius = r, center = Offset(cx, cy),
                )
            }
            blob(w * (0.15f + 0.12f * cos(phase)), h * (0.10f + 0.07f * sin(phase * 2)), m * 0.62f, c.accent2, 0.34f + 0.14f * e)
            blob(w * (0.90f + 0.08f * sin(phase)), h * (0.32f + 0.08f * cos(phase)), m * 0.50f, c.accent, 0.15f + 0.08f * e)
            blob(w * (0.45f + 0.22f * cos(phase + 2f)), h * (1.0f + 0.05f * sin(phase)), m * 0.66f, lerp(c.accent2, c.ok, e), 0.18f + 0.14f * e)
            blob(w * (0.65f + 0.1f * sin(phase * 1.5f + 1f)), h * (0.62f + 0.06f * cos(phase)), m * 0.35f, Color(0xFFFF9AC8), 0.05f + 0.04f * e)

            // Ghost dust: tiny stars drifting up and twinkling.
            stars.forEach { s ->
                val y = ((s[1] - drift * s[4] * 0.35f) % 1f + 1f) % 1f
                val tw = 0.5f + 0.5f * sin(phase * 3f * s[4] + s[3])
                drawCircle(Color.White.copy(alpha = (0.10f + 0.35f * tw) * (0.7f + 0.3f * e)), s[2].dp.toPx() * 0.8f, Offset(s[0] * w, y * h))
            }
            // Vignette keeps text readable at the bottom.
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, c.bg.copy(alpha = 0.6f)), startY = h * 0.55f, endY = h))
        }
        content()
    }
}

/** Frosted-glass surface: translucent fill, hairline border with a lit top edge, cursor spotlight on desktop. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    padding: Dp = 18.dp,
    strong: Boolean = false,
    glow: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Ghost.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lit by animateFloatAsState(if (hovered) 1f else 0f, Motion.quick(260))
    var m = modifier
    if (onClick != null) m = m.pressScale(interaction, 0.975f, hover = 1.012f)
    m = m.clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = (if (strong) 0.10f else 0.065f) + 0.03f * lit),
                    Color.White.copy(alpha = 0.025f + 0.015f * lit),
                ),
            ),
        )
    if (glow != null) m = m.background(Brush.radialGradient(listOf(glow.copy(alpha = 0.16f), Color.Transparent)))
    m = m.spotlight(c.accent)
        .border(
            1.dp,
            Brush.verticalGradient(
                listOf(
                    lerp(Color.White.copy(alpha = 0.16f), c.accent.copy(alpha = 0.55f), lit),
                    Color.White.copy(alpha = 0.04f + 0.06f * lit),
                ),
            ),
            shape,
        )
    if (onClick != null) m = m.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    Column(m.padding(padding), content = content)
}

/**
 * Springy "dock" feel: lifts a little under the mouse, shrinks while held, overshoots on release.
 * Also switches the cursor to a hand on desktop.
 */
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.94f, hover: Float = 1.04f): Modifier = composed {
    val isPressed by interaction.collectIsPressedAsState()
    val isHovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        when {
            isPressed -> pressed
            isHovered -> hover
            else -> 1f
        },
        Motion.bouncy(),
    )
    pointerHoverIcon(PointerIcon.Hand).graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Soft light that follows the cursor across a surface (the site's card spotlight). No-op on touch. */
fun Modifier.spotlight(color: Color, radius: Dp = 240.dp): Modifier = composed {
    var pos by remember { mutableStateOf(Offset.Zero) }
    var inside by remember { mutableStateOf(false) }
    val a by animateFloatAsState(if (inside) 1f else 0f, tween(if (inside) 220 else 520))
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                when (e.type) {
                    PointerEventType.Enter, PointerEventType.Move -> {
                        // Only mouse hover counts; touch drags would leave a light trail.
                        if (e.changes.none { it.pressed } || inside) {
                            pos = e.changes.first().position
                            inside = e.type == PointerEventType.Move || e.type == PointerEventType.Enter
                        }
                    }
                    PointerEventType.Exit -> inside = false
                }
            }
        }
    }.drawWithContent {
        drawContent()
        if (a > 0.01f) {
            val r = radius.toPx()
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.13f * a), Color.Transparent), pos, r), r, pos)
        }
    }
}

/**
 * Staggered entrance: fade + rise, [index] sets the delay. [enabled] = false shows the item at once —
 * lazy lists pass false for rows that were already seen, so fast scrolling doesn't replay it.
 */
fun Modifier.appear(index: Int, step: Long = 35L, enabled: Boolean = true): Modifier = composed {
    if (!enabled) return@composed this
    val reduce = LocalReduceMotion.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(14) * step))
        shown = true
    }
    val p by animateFloatAsState(if (shown) 1f else 0f, if (reduce) tween(200) else Motion.bouncy())
    graphicsLayer {
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 18.dp.toPx()
        val s = 0.96f + 0.04f * p
        scaleX = s; scaleY = s
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Ghost.colors.line))
}

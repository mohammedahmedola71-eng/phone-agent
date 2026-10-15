package ai.vox.app.ui.components

import ai.vox.app.core.model.OrbState
import ai.vox.app.ui.theme.VoxColors
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The Vox orb. Each state has its own motion:
 * inactive = dim and nearly still, idle = slow breathing, listening = ripples that follow the mic level,
 * thinking = fast orbiting arcs, speaking = pulsing core with outgoing rings, analyzing = a scanning beam.
 */
@Composable
fun VoxOrb(state: OrbState, modifier: Modifier = Modifier, micLevel: Float = 0f, orbSize: Dp = 260.dp) {
    val transition = rememberInfiniteTransition(label = "orb")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "phase")
    val spinSlow by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "spinSlow")
    val spinFast by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "spinFast")
    val breathe by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(3400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val level by animateFloatAsState(micLevel, spring(stiffness = 300f), label = "level")
    val energy by animateFloatAsState(
        when (state) {
            OrbState.INACTIVE -> 0.18f
            OrbState.IDLE -> 0.5f
            OrbState.LISTENING -> 0.8f
            OrbState.THINKING -> 0.7f
            OrbState.SPEAKING -> 0.95f
            OrbState.ANALYZING -> 0.8f
        },
        tween(700), label = "energy",
    )
    val hue by animateFloatAsState(
        when (state) {
            OrbState.THINKING -> 1f
            OrbState.ANALYZING -> 0.6f
            else -> 0f
        },
        tween(700), label = "hue",
    )

    Canvas(modifier.size(orbSize)) {
        val c = center
        val r = size.minDimension / 2f
        val main = lerp(VoxColors.Cyan, VoxColors.Blue, hue * 0.8f)
        val coreBase = r * 0.40f
        val pulse = when (state) {
            OrbState.SPEAKING -> 0.07f * (0.5f + 0.5f * sin(phase * 2f * PI.toFloat() * 3f))
            OrbState.LISTENING -> 0.04f + level * 0.16f
            OrbState.THINKING -> 0.03f * sin(phase * 2f * PI.toFloat() * 2f)
            else -> 0.025f * breathe
        }
        val coreR = coreBase * (1f + pulse)

        // Ambient glow
        drawCircle(Brush.radialGradient(listOf(main.copy(alpha = 0.40f * energy), main.copy(alpha = 0.10f * energy), Color.Transparent), c, r), r, c)

        // Outgoing rings (listening, speaking)
        if (state == OrbState.LISTENING || state == OrbState.SPEAKING) {
            for (i in 0..2) {
                val p = (phase + i / 3f) % 1f
                drawCircle(main.copy(alpha = (1f - p) * 0.55f * energy), coreR * (1.15f + p * 1.0f), c, style = Stroke(1.6.dp.toPx()))
            }
        }

        // Slow decorative dashed ring
        rotate(spinSlow, c) {
            drawCircle(main.copy(alpha = 0.28f * energy), coreR * 1.55f, c, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 10.dp.toPx()))))
        }

        // Orbiting arcs while working
        if (state == OrbState.THINKING || state == OrbState.ANALYZING) {
            rotate(spinFast, c) {
                for (i in 0..2) {
                    drawArc(
                        color = main.copy(alpha = 0.9f - i * 0.22f), startAngle = i * 120f, sweepAngle = 62f, useCenter = false,
                        topLeft = Offset(c.x - coreR * 1.32f, c.y - coreR * 1.32f), size = Size(coreR * 2.64f, coreR * 2.64f),
                        style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }

        // Core sphere
        val coreBrush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.95f * (0.4f + energy * 0.6f)), main, VoxColors.Blue.copy(alpha = 0.85f), VoxColors.BgDeep),
            center = Offset(c.x - coreR * 0.3f, c.y - coreR * 0.35f), radius = coreR * 1.5f,
        )
        drawCircle(coreBrush, coreR, c)
        drawCircle(main.copy(alpha = 0.7f * energy), coreR, c, style = Stroke(1.5.dp.toPx()))

        // Scanning beam while analyzing visuals
        if (state == OrbState.ANALYZING) {
            val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, coreR)) }
            val y = c.y - coreR + 2f * coreR * abs(sin(phase * PI.toFloat()))
            clipPath(clip) {
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.85f), Color.Transparent), y - 14.dp.toPx(), y + 14.dp.toPx()), Offset(c.x - coreR, y - 14.dp.toPx()), Size(coreR * 2, 28.dp.toPx()))
            }
        }
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t, green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t, alpha = a.alpha + (b.alpha - a.alpha) * t,
)

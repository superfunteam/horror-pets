package team.superfun.horrorpets.ui.fight

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.assets.SceneSpec
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.SceneBackdrop
import team.superfun.horrorpets.ui.common.SlabSurface
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.gameFrame
import team.superfun.horrorpets.ui.common.ignoreOkUnless
import team.superfun.horrorpets.ui.common.leaning
import team.superfun.horrorpets.ui.common.rememberOkReady
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Ink
import team.superfun.horrorpets.ui.theme.TextHi

private val GoldText = Brush.verticalGradient(listOf(Color(0xFFFFF0B0), Color(0xFFF5C543), Color(0xFFC98A12)))
private val Gold = Color(0xFFF5C543)

/**
 * "UNLOCKED!": the player just beat [pet], a killer they did not have, and it joined their stable. Its head turns up
 * in a gold medallion with light rays turning behind it, over the fight's [arena] dimmed, with "<NAME> JOINS YOUR
 * STABLE" and how many of the killers are unlocked now ([unlocked] of [PetId.entries]). OK, Back or a tap carries
 * on to the results ([onDone]).
 */
@Composable
fun UnlockedScreen(
    pet: PetId,
    unlocked: Int,
    loader: AssetLoader,
    arena: SceneSpec?,
    onDone: () -> Unit,
    focusEntry: Any? = Unit,
) {
    val focus = remember { FocusRequester() }
    GameBackHandler(enabled = focusEntry != null, onBack = onDone)
    RequestFocusOnEntry(focus, focusEntry)
    val okReady = rememberOkReady(focusEntry)
    val head = remember(loader, pet) { loader.head(pet) }
    val pop = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f)) }
    val spin = rememberInfiniteTransition(label = "rays").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9_000, easing = LinearEasing), RepeatMode.Restart),
        label = "raysTurn",
    )

    Box(Modifier.fillMaxSize().background(Ink).ignoreOkUnless(okReady), contentAlignment = Alignment.Center) {
        if (arena != null) SceneBackdrop(loader = loader, scene = arena) { }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
        Column(
            Modifier
                .width(420.dp)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                    alpha = ((pop.value - 0.6f) / 0.4f).coerceIn(0f, 1f)
                }
                .gameFrame(topEnd = 14.dp, bottomStart = 14.dp)
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                "UNLOCKED!",
                style = displayStyle(54.sp, tracking = 0.06f, lineHeight = 56.sp).copy(brush = GoldText, shadow = Shadow(Color.Black, Offset(0f, 4f), 0f)),
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            Canvas(Modifier.size(150.dp)) {
                val c = center
                val r = size.minDimension * 0.32f
                // Light rays turning behind the medallion, fading out toward their tips.
                rotate(spin.value, c) {
                    val rays = 16
                    for (i in 0 until rays) {
                        val a0 = (i * 2 * PI / rays).toFloat()
                        val a1 = a0 + (PI / rays * 0.7).toFloat()
                        val far = size.minDimension / 2f
                        val ray = Path().apply {
                            moveTo(c.x, c.y)
                            lineTo(c.x + cos(a0) * far, c.y + sin(a0) * far)
                            lineTo(c.x + cos(a1) * far, c.y + sin(a1) * far)
                            close()
                        }
                        drawPath(ray, Brush.radialGradient(listOf(Gold.copy(alpha = 0.45f), Color.Transparent), center = c, radius = far))
                    }
                }
                drawCircle(Ink, r + 7.dp.toPx(), c)
                drawCircle(Gold, r + 4.dp.toPx(), c, style = Stroke(4.dp.toPx()))
                drawCircle(Color(0xFF2A1D21), r, c)
                clipPath(Path().apply { addOval(Rect(c, r)) }) {
                    val w = (r * 2.3f).roundToInt()
                    drawImage(head, dstOffset = IntOffset((c.x - r * 1.15f).roundToInt(), (c.y - r * 1.1f).roundToInt()), dstSize = IntSize(w, w))
                }
            }
            Spacer(Modifier.height(4.dp))
            BasicText(
                "${pet.displayName.uppercase()} JOINS YOUR STABLE",
                style = displayStyle(24.sp, tracking = 0.06f).copy(textAlign = TextAlign.Center),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 24.sp, stepSize = 1.sp),
            )
            Spacer(Modifier.height(6.dp))
            BasicText(
                "$unlocked / ${PetId.entries.size} UNLOCKED",
                style = displayStyle(13.sp, FontWeight.Bold, color = Amber, tracking = 0.2f),
            )
            Spacer(Modifier.height(16.dp))
            SlabSurface(onClick = onDone, modifier = Modifier.focusRequester(focus), minWidth = 120.dp, horizontalPadding = 24.dp) { lit ->
                BasicText(
                    "OK",
                    style = displayStyle(24.sp, tracking = 0.04f, color = if (lit) TextHi else TextHi.copy(alpha = 0.8f)),
                    modifier = Modifier.leaning(),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

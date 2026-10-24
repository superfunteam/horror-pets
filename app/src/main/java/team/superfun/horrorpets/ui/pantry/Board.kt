package team.superfun.horrorpets.ui.pantry

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.ui.common.SLAB_FOCUS_SCALE
import team.superfun.horrorpets.ui.common.SlabHeight
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.drawSlab
import team.superfun.horrorpets.ui.common.gameFrame
import team.superfun.horrorpets.ui.common.isTouchInput
import team.superfun.horrorpets.ui.common.leaning
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Ink
import team.superfun.horrorpets.ui.theme.TextHi

/** The cutting board's picture is this much wider than it is tall (768 x 563 px). */
internal const val BOARD_ASPECT = 768f / 563f

/**
 * The board's two places, as fractions of its picture: along the middle of its top face, which leans up to the right,
 * the first a little left of centre and the second a little right. The "+" sits between them.
 */
internal val PlaceAt = listOf(Offset(0.31f, 0.5f), Offset(0.74f, 0.38f))
internal val PlusAt = Offset(0.525f, 0.44f)

/** An item on the board is this share of the board's width across. */
internal const val BOARD_ITEM_SHARE = 0.29f

/** The remote's cursor round an item on the board reaches this far past it on every side. */
private val BoardCursorPad = 10.dp

/**
 * The cutting board, [width] wide: its two places hold the shelf items [places] names (shelf indices, null for an
 * empty place, see [boardPlaces]), each standing over its name. An empty place is a dashed ring burnt into the wood.
 * A tap on an item takes it off again ([onTap] with its shelf index). The [heart] dish wears its heart here too. The
 * remote's [focus] (a place, or null when the remote is elsewhere) puts the gold cursor round the item there. The "+"
 * between the places goes while the board is a stage for something else ([plus] false: a cook, a meal's gains).
 */
@Composable
internal fun Board(
    loader: AssetLoader,
    shelf: List<PantryItem>,
    places: List<Int?>,
    heart: PantryItem?,
    width: Dp,
    focus: Int?,
    plus: Boolean,
    onTap: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val height = width / BOARD_ASPECT
    val itemSize = width * BOARD_ITEM_SHARE
    val board = rememberBoardImage(loader)
    Box(modifier.size(width, height)) {
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val image = board ?: return@drawBehind
                    // A hard drop shadow, as the game's slabs have, then the board.
                    translate(left = 4.dp.toPx(), top = 9.dp.toPx()) {
                        drawImage(
                            image,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(image.width, image.height),
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            alpha = 0.55f,
                            colorFilter = ColorFilter.tint(Color.Black),
                            filterQuality = FilterQuality.Low,
                        )
                    }
                    drawImage(
                        image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(image.width, image.height),
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        filterQuality = FilterQuality.High,
                    )
                    for ((place, at) in PlaceAt.withIndex()) {
                        if (places.getOrNull(place)?.let { it in shelf.indices } == true) continue
                        val center = Offset(size.width * at.x, size.height * at.y)
                        val r = itemSize.toPx() * 0.36f
                        drawCircle(Color.Black.copy(alpha = 0.16f), radius = r, center = center)
                        drawCircle(
                            Color(0xFF2A1408).copy(alpha = 0.55f),
                            radius = r,
                            center = center,
                            style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx())), cap = StrokeCap.Round),
                        )
                    }
                    if (plus) drawPlus(Offset(size.width * PlusAt.x, size.height * PlusAt.y), 24.dp.toPx())
                },
        )
        for ((place, at) in PlaceAt.withIndex()) {
            val index = places.getOrNull(place) ?: continue
            val item = shelf.getOrNull(index) ?: continue
            // Keyed on what stands there, so a new item starts afresh.
            key(place, index, item) {
                val lit by animateFloatAsState(if (focus == place) 1f else 0f, tween(160), label = "boardLit")
                Spacer(
                    Modifier
                        .offset(width * at.x - itemSize / 2f - BoardCursorPad, height * at.y - itemSize / 2f - BoardCursorPad)
                        .size(itemSize + BoardCursorPad * 2)
                        .drawBehind { drawCursor(lit) },
                )
                BoardItem(loader, item, item == heart, itemSize, Modifier.offset(width * at.x - itemSize / 2f, height * at.y - itemSize / 2f)) {
                    onTap(index)
                }
                ItemName(
                    item.title,
                    maxWidth = width * 0.46f,
                    centerX = width * at.x,
                    top = height * at.y + itemSize / 2f - 4.dp,
                )
            }
        }
    }
}

/** An item on the board: tap it to take it off again. */
@Composable
private fun BoardItem(loader: AssetLoader, item: PantryItem, heart: Boolean, size: Dp, modifier: Modifier, onTap: () -> Unit) {
    ItemArt(
        loader,
        item,
        modifier
            .size(size)
            .focusProperties { canFocus = false }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        heart = heart,
    )
}

/** An item's name on a small dark tag, centred on [centerX] at [top], wrapping to two lines when it must. */
@Composable
internal fun ItemName(title: String, maxWidth: Dp, centerX: Dp, top: Dp, modifier: Modifier = Modifier) {
    BasicText(
        title.uppercase(),
        style = displayStyle(15.sp, FontWeight.Bold, tracking = 0.05f, lineHeight = 16.sp).copy(textAlign = TextAlign.Center),
        maxLines = 2,
        modifier = modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints(maxWidth = maxWidth.roundToPx()))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place((centerX.toPx() - placeable.width / 2f).roundToInt(), top.roundToPx())
                }
            }
            .gameFrame(topEnd = 6.dp, bottomStart = 6.dp, inner = false)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** A chunky gold "+" with a dark rim, [size] across, centred on [center]. */
private fun DrawScope.drawPlus(center: Offset, size: Float) {
    val half = size / 2f
    val bar = size * 0.3f
    for ((color, width) in listOf(Ink to bar + 4.dp.toPx(), Amber to bar)) {
        drawLine(color, Offset(center.x - half, center.y), Offset(center.x + half, center.y), strokeWidth = width, cap = StrokeCap.Round)
        drawLine(color, Offset(center.x, center.y - half), Offset(center.x, center.y + half), strokeWidth = width, cap = StrokeCap.Round)
    }
}

/** The cutting board's picture, decoded off the main thread (null until then). */
@Composable
private fun rememberBoardImage(loader: AssetLoader): ImageBitmap? {
    val image = remember(loader) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(loader) { image.value = withContext(Dispatchers.IO) { loader.cuttingBoard() } }
    return image.value
}

/** The board's two buttons. */
internal enum class KitchenButton { COOK, FEED }

/** COOK and FEED's width. */
internal val KitchenButtonWidth = 150.dp

/** The gap between COOK and FEED. */
internal val KitchenButtonGap = 26.dp

/**
 * COOK and FEED under the board, as Home's arcade slabs: lit as the remote's [focused] one (null when the remote is
 * on the shelf) or under a finger, their labels dim while they can't act (COOK needs two items on the board, FEED one). A
 * tap calls [onPress] whether or not it can act, so the screen answers a press on a dim one as it does the remote's.
 */
@Composable
internal fun KitchenButtons(
    canCook: Boolean,
    canFeed: Boolean,
    focused: KitchenButton?,
    onPress: (KitchenButton) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(KitchenButtonGap), verticalAlignment = Alignment.CenterVertically) {
        KitchenSlab("Cook", canCook, focused == KitchenButton.COOK) { onPress(KitchenButton.COOK) }
        KitchenSlab("Feed", canFeed, focused == KitchenButton.FEED) { onPress(KitchenButton.FEED) }
    }
}

/**
 * One of COOK and FEED: a slab like Home's action bar. The remote's focus is the pantry's own (the screen keeps it, see
 * `PantryScreen`), so [focused] lights it rather than Compose focus; a finger lights it while pressed. A slab that
 * can't act ([enabled] false) has a dim label and never fills red.
 */
@Composable
private fun KitchenSlab(label: String, enabled: Boolean, focused: Boolean, onTap: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val touch = isTouchInput()
    val lit = if (touch) pressed else focused
    // A slab that can't act never fills red: lit, it only warms a little, so the focus still shows on it.
    val glow by animateFloatAsState(if (lit) (if (enabled) 1f else 0.22f) else 0f, tween(200), label = "kitchenSlabGlow")
    val scale by animateFloatAsState(
        when {
            touch -> if (pressed) 0.95f else 1f
            focused -> SLAB_FOCUS_SCALE
            else -> 1f
        },
        tween(180),
        label = "kitchenSlabScale",
    )
    val ink by animateFloatAsState(if (enabled) (if (lit) 1f else 0.8f) else 0.3f, tween(200), label = "kitchenSlabInk")
    Box(
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusProperties { canFocus = false }
            .clickable(interactionSource = interaction, indication = null, onClick = onTap)
            .size(KitchenButtonWidth, SlabHeight)
            .drawBehind { drawSlab(glow) },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label.uppercase(),
            style = displayStyle(24.sp, tracking = 0.03f, color = TextHi.copy(alpha = ink)),
            maxLines = 1,
            modifier = Modifier.leaning(),
        )
    }
}

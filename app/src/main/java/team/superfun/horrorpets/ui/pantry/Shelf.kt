package team.superfun.horrorpets.ui.pantry

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.game.pantry.FlavorMark
import team.superfun.horrorpets.game.pantry.flavorMark
import team.superfun.horrorpets.game.pantry.methodHint
import team.superfun.horrorpets.model.PantryItem
import team.superfun.horrorpets.ui.common.MAX_FONT_SCALE
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.softGlow
import team.superfun.horrorpets.ui.common.spriteFilter
import team.superfun.horrorpets.ui.switcher.carouselDragRow
import team.superfun.horrorpets.ui.switcher.carouselFlingTarget
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.training.TrainingGold

/** A shelf item stands in a box this big (its picture fitted inside, standing on the plank). */
internal val ShelfItemSize = 64.dp

/** The focused item grows this much inside its cursor (and a cooked dish landing there lands that big). */
internal const val FOCUS_SCALE = 1.12f

/** The remote's cursor: a gold-edged frame round the focused item, standing on the plank, at most this big. */
private val CursorSize = 80.dp
private val CursorCut = 10.dp

/** The cursor's bottom edge sits this far below where the items stand, on the plank's top. */
private val CursorSink = 3.dp

/** Where the items stand, down from the top of the shelf's box: room above for the cursor and its glow. */
internal val ShelfStandY = CursorSize + 6.dp

/** The plank under them: its top seen from a little above, then its front edge. */
private val PlankTopRise = 6.dp
private val PlankTopDepth = 5.dp
private val PlankFront = 16.dp

/** Every item's name label hangs from the plank's front edge, its top this far below where the items stand. */
private val LabelTop = PlankTopDepth + PlankFront - 2.dp

/** A label's name is this big at most, and shrinks to fit (see [labelFontSize]) as small as [LABEL_MIN_SP]. */
private const val LABEL_MAX_SP = 13f
private const val LABEL_MIN_SP = 10f
private const val LABEL_STEP_SP = 0.5f

/** The room between two labels side by side, and round a name inside its label. */
private val LabelGap = 6.dp
private val LabelPadX = 4.dp
private val LabelPadY = 3.dp
private val LabelCut = 5.dp

/** A dish's flavor mark (see [flavorMarkPath]) is this big, and its name moves over by its width and a hair more. */
private val MarkSize = 9.dp
private val MarkRoom = MarkSize + 2.dp

/**
 * A label at its tallest, at the biggest font scale the game allows: two lines at the biggest size, or a lit way to
 * cook's name over the two lines its hint wraps to (see [LabelHintStyle]), which must still clear the help strip.
 */
private val LabelMaxHeight =
    (max(LABEL_LINES * LABEL_MAX_SP, LABEL_MAX_SP + LABEL_LINES * LABEL_MIN_SP) * MAX_FONT_SCALE).dp + LabelPadY * 2

/** The shelf's whole box: the items, the plank and the labels under it. */
internal val ShelfHeight = ShelfStandY + LabelTop + LabelMaxHeight + 2.dp

/** How far an item on the board fades in its shelf slot (see [ItemArt]'s dim). */
private const val ON_BOARD_DIM = 1f

/**
 * The pantry's shelf: a wooden plank along the bottom of the kitchen with [shelf]'s items standing on it, [window]'s
 * run of them in its slots (a long shelf slides left and right under them as the window moves, and a finger can drag
 * it). Every item has its name on a small label hanging from the plank under it. Items on the [board] stand dimmed in
 * their place, labels too; the [heart] dish wears its heart, and the [known] items (the ones that have made a dish
 * with what is on the board) a gold check. A groove in the plank before each slot in [breaks] parts one kind of item
 * from the next. [focus] is the remote's focus (in a gold cursor, a little bigger, its label lit gold), or null when
 * the remote is not on the shelf (it is on the buttons, or a finger is in charge). The [hidden] slot stands empty,
 * label and all (a cooked dish is still on its way to it).
 *
 * A tap on an item calls [onTap] with its index on [shelf] as it is drawn now; a drag that ends calls [onScrolled]
 * with the window's first slot as it settles. Neither happens while [enabled] says no (read as a finger comes down):
 * the kitchen's input is held. Gold arrows on the plank's ends say the shelf goes on past them.
 */
@Composable
internal fun Shelf(
    loader: AssetLoader,
    shelf: List<PantryItem>,
    board: List<Int>,
    heart: PantryItem?,
    breaks: Set<Int>,
    known: Set<Int>,
    window: ShelfWindow,
    focus: Int?,
    hidden: Int?,
    enabled: () -> Boolean,
    onTap: (index: Int) -> Unit,
    onScrolled: (first: Int) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val scroll = remember { Animatable(window.first.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val latestShelf by rememberUpdatedState(shelf)
    val latestWindow by rememberUpdatedState(window)
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnScrolled by rememberUpdatedState(onScrolled)
    val latestEnabled by rememberUpdatedState(enabled)

    // A shelf shorter than its slots stands in the middle of the plank; it slides over as it grows or shrinks.
    val lead = animateFloatAsState(shelfLead(shelf.size, window.slots), spring(dampingRatio = 0.9f, stiffness = 500f), label = "shelfLead")

    // The window moves (the remote's focus, a cook): the shelf slides after it, unless a finger has it.
    LaunchedEffect(window.first) {
        if (!dragging) scroll.animateTo(window.first.toFloat(), spring(dampingRatio = 0.9f, stiffness = 600f))
    }

    Box(modifier) {
        ShelfBody(
            loader = loader,
            shelf = shelf,
            board = board,
            heart = heart,
            breaks = breaks,
            known = known,
            window = window,
            focus = focus,
            hidden = hidden,
            scroll = { scroll.value - lead.value },
            scrolledTo = { floor(scroll.value - lead.value).toInt() },
            gestures = Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Held (coming in, a cook on show, away): this finger does nothing, all the way to its lift.
                    if (!latestEnabled()) return@awaitEachGesture
                    val pitch = size.width.toFloat() / latestWindow.slots
                    val start = scroll.value
                    val tracker = VelocityTracker()
                    tracker.addPointerInputChange(down)
                    var moved = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPointerInputChange(change)
                        val dx = change.position.x - down.position.x
                        if (!change.pressed) {
                            if (moved) {
                                change.consume()
                                val count = max(0, latestShelf.size - latestWindow.slots) + 1
                                val velocity = -tracker.calculateVelocity().x / pitch
                                val target = carouselFlingTarget(scroll.value, velocity, count)
                                dragging = false
                                latestOnScrolled(target)
                                scope.launch { scroll.animateTo(target.toFloat(), spring(dampingRatio = 0.9f, stiffness = 500f)) }
                            } else {
                                // A tap: the slot under the finger, on the shelf as it is drawn now.
                                val index = floor(scroll.value - lead.value + down.position.x / pitch).toInt()
                                if (index in latestShelf.indices) latestOnTap(index)
                            }
                            break
                        }
                        if (!moved && abs(dx) > viewConfiguration.touchSlop) {
                            moved = true
                            dragging = true
                        }
                        if (moved) {
                            change.consume()
                            val count = max(0, latestShelf.size - latestWindow.slots) + 1
                            val row = carouselDragRow(start, dx, pitch, count)
                            scope.launch { scroll.snapTo(row) }
                        }
                    }
                    // A gesture cut short (another finger, the system) still lets the shelf settle.
                    if (dragging) {
                        dragging = false
                        val target = scroll.value.roundToInt().coerceIn(0, max(0, latestShelf.size - latestWindow.slots))
                        latestOnScrolled(target)
                        scope.launch { scroll.animateTo(target.toFloat()) }
                    }
                }
            },
        )
    }
}

/**
 * [Shelf]'s plank, items and labels, the shelf slid to [scroll] (in slots, a short shelf's lead taken off) and
 * [scrolledTo] the whole slot of it; [gestures] takes the items' touches.
 */
@Composable
private fun ShelfBody(
    loader: AssetLoader,
    shelf: List<PantryItem>,
    board: List<Int>,
    heart: PantryItem?,
    breaks: Set<Int>,
    known: Set<Int>,
    window: ShelfWindow,
    focus: Int?,
    hidden: Int?,
    scroll: () -> Float,
    scrolledTo: () -> Int,
    gestures: Modifier,
) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val density = LocalDensity.current
    val standPx = with(density) { ShelfStandY.toPx() }
    val labelTopPx = with(density) { (ShelfStandY + LabelTop).toPx() }
    val labelSize = rememberLabelSizes(
        plain = labelTextWidth(constraints.maxWidth, window.slots, marked = false, density),
        marked = labelTextWidth(constraints.maxWidth, window.slots, marked = true, density),
    )
    // Only the whole slot the shelf has slid to recomposes; the slide itself is read while placing.
    val near by remember { derivedStateOf { scrolledTo() } }
    val from = max(0, near - 2)
    val to = min(shelf.size - 1, near + window.slots + 1)

    Box(Modifier.fillMaxSize()) {
        // The plank, a little wider than the slots so the end items stand on wood.
        Spacer(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val plank = PlankPaint(this, ShelfStandY.toPx())
                    val grooveTop = ShelfStandY.toPx() - GrooveRise.toPx()
                    val grooveBottom = (ShelfStandY + PlankTopDepth + PlankFront).toPx()
                    onDrawBehind {
                        val count = shelf.size
                        val s = scroll()
                        plank.draw(this, moreLeft = s > 0.05f, moreRight = s + window.slots < count - 0.05f)
                        // A groove in the wood before each slot that starts a new kind of item.
                        val pitch = size.width / window.slots
                        for (slot in breaks) {
                            val x = (slot - s) * pitch
                            if (x < 0f || x > size.width) continue
                            drawLine(GrooveDark, Offset(x, grooveTop), Offset(x, grooveBottom), strokeWidth = 2.dp.toPx())
                            drawLine(GrooveLight, Offset(x + 2.dp.toPx(), grooveTop), Offset(x + 2.dp.toPx(), grooveBottom), strokeWidth = 1.dp.toPx())
                        }
                    }
                },
        )
        // The items and their labels, clipped at the shelf's ends (and nowhere else: the focused one grows up past the
        // box, and its label's glow spills under it).
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent { clipRect(0f, -size.height, size.width, size.height * 2f) { this@drawWithContent.drawContent() } }
                .then(gestures),
        ) {
            for (index in from..to) {
                val item = shelf[index]
                // Keyed on the index and the item there: after a cook, a slot whose item changed starts afresh.
                key(index, item) {
                    ShelfSlot(
                        loader = loader,
                        item = item,
                        onBoard = index in board,
                        heart = item == heart,
                        known = index in known,
                        focused = index == focus,
                        hidden = index == hidden,
                        x = { pitch -> (index - scroll() + 0.5f) * pitch },
                        slots = window.slots,
                        standPx = standPx,
                        labelTopPx = labelTopPx,
                        labelSize = labelSize(item),
                    )
                }
            }
        }
    }
}

/**
 * One item on the shelf, standing at its slot's centre ([x] of the slot pitch), in its cursor when [focused], and its
 * name under it on a label (its top [labelTopPx] down the shelf, its name [labelSize]). A dish's label wears its
 * flavor's mark; a way to cook's says what it does while the remote is on it.
 */
@Composable
private fun ShelfSlot(
    loader: AssetLoader,
    item: PantryItem,
    onBoard: Boolean,
    heart: Boolean,
    known: Boolean,
    focused: Boolean,
    hidden: Boolean,
    x: (pitch: Float) -> Float,
    slots: Int,
    standPx: Float,
    labelTopPx: Float,
    labelSize: TextUnit,
) {
    val lit by animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "shelfLit")
    val dim by animateFloatAsState(if (onBoard) ON_BOARD_DIM else 0f, tween(200), label = "shelfDim")
    val density = LocalDensity.current
    val sinkPx = with(density) { CursorSink.toPx() }
    Box(
        Modifier.fillMaxSize().layout { measurable, constraints ->
            // The slot's own box, its bottom on the plank at its centre.
            val pitch = constraints.maxWidth.toFloat() / slots
            val side = min(CursorSize.toPx(), pitch - 6.dp.toPx()).roundToInt()
            val placeable = measurable.measure(Constraints.fixed(side, CursorSize.roundToPx()))
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place((x(pitch) - placeable.width / 2f).roundToInt(), (standPx + sinkPx - placeable.height).roundToInt())
            }
        },
    ) {
        Spacer(Modifier.fillMaxSize().drawBehind { drawCursor(lit) })
        if (!hidden) ItemArt(
            loader,
            item,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = CursorSink)
                .size(ShelfItemSize)
                .graphicsLayer {
                    val grow = 1f + (FOCUS_SCALE - 1f) * lit
                    scaleX = grow
                    scaleY = grow
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
            heart = heart,
            known = known,
            dim = { dim },
        )
    }
    if (!hidden) {
        ShelfLabel(
            item.title,
            labelSize,
            mark = flavorMark(item),
            hint = methodHint(item)?.takeIf { focused },
            lit = { lit },
            dim = { dim },
            x = x,
            slots = slots,
            topPx = labelTopPx,
        )
    }
}

/** A lit cursor's gold edge, paler than Amber, as the game's frames light up. */
private val CursorEdge = Color(0xFFFFE08A)

/**
 * The remote's cursor round a shelf item, [lit] (0..1) of the way in: a chamfered frame like the game's windows
 * (top-right and bottom-left corners cut), dark glass inside so the item stands out, a gold edge and a gold halo.
 */
internal fun DrawScope.drawCursor(lit: Float) {
    if (lit <= 0f) return
    val cut = CursorCut.toPx()
    val stroke = 2.dp.toPx()
    softGlow(Amber, alpha = 0.5f * lit, cornerRadius = 4.dp.toPx(), spread = 12.dp, layers = 6)
    val frame = chamfer(size, stroke / 2f, cut)
    drawPath(frame, Brush.verticalGradient(listOf(Color(0xFF1B1215).copy(alpha = 0.55f * lit), Color(0xFF070507).copy(alpha = 0.75f * lit))))
    drawPath(frame, CursorEdge.copy(alpha = lit), style = Stroke(stroke))
    drawPath(chamfer(size, 5.dp.toPx(), cut - 3.dp.toPx()), Amber.copy(alpha = 0.35f * lit), style = Stroke(1.dp.toPx()))
}

/** A [size] rectangle inset by [inset], its top-right and bottom-left corners cut off [cut]. */
private fun chamfer(size: Size, inset: Float, cut: Float): Path = Path().apply {
    moveTo(inset, inset)
    lineTo(size.width - inset - cut, inset)
    lineTo(size.width - inset, inset + cut)
    lineTo(size.width - inset, size.height - inset)
    lineTo(inset + cut, size.height - inset)
    lineTo(inset, size.height - inset - cut)
    close()
}

/**
 * A label's name, [size] big: centred, on lines as tall as the type, two lines balanced where the system can ("FINGER
 * DOG / IN A BUN", not "FINGER DOG IN / A BUN").
 */
private fun labelStyle(size: TextUnit): TextStyle =
    displayStyle(size, FontWeight.Bold, tracking = 0.04f, lineHeight = size).copy(textAlign = TextAlign.Center, lineBreak = LineBreak.Heading)

/** How wide a label is on a shelf [shelfWidth] px wide in [slots] slots: its slot, less the gap to the next one. */
private fun Density.labelWidth(shelfWidth: Int, slots: Int): Int =
    (shelfWidth.toFloat() / max(1, slots) - LabelGap.toPx()).roundToInt().coerceAtLeast(1)

/** The room before a label's name: its padding, and the room for a flavor mark too when it wears one ([marked]). */
private fun labelPadStart(marked: Boolean): Dp = if (marked) LabelPadX + MarkRoom else LabelPadX

/** How wide the name inside such a label may be (px), less the room for a flavor mark when it wears one ([marked]). */
private fun labelTextWidth(shelfWidth: Int, slots: Int, marked: Boolean, density: Density): Int =
    with(density) { (labelWidth(shelfWidth, slots) - labelPadStart(marked).roundToPx() - LabelPadX.roundToPx()).coerceAtLeast(1) }

/**
 * The size each item's name is set at on its label (see [labelFontSize]): in [plain] px, or in [marked] px for a dish,
 * whose flavor mark takes some of its label. Worked out once per item for as long as the widths hold.
 */
@Composable
private fun rememberLabelSizes(plain: Int, marked: Int): (item: PantryItem) -> TextUnit {
    val measurer = rememberTextMeasurer()
    return remember(measurer, plain, marked) {
        val sizes = HashMap<PantryItem, TextUnit>()
        val pick: (PantryItem) -> TextUnit = { item ->
            sizes.getOrPut(item) {
                val label = labelText(item.title)
                val width = if (flavorMark(item) == null) plain else marked
                labelFontSize(label, LABEL_MAX_SP, LABEL_MIN_SP, LABEL_STEP_SP) { sp ->
                    val laid = measurer.measure(label, labelStyle(sp.sp), constraints = Constraints(maxWidth = width))
                    List(laid.lineCount) { laid.getLineEnd(it) }
                }.sp
            }
        }
        pick
    }
}

/**
 * [title] on its label under its item: centred on the slot ([x] of the slot pitch, among [slots]), its top [topPx]
 * down the shelf, never wider than its slot (less the gap to the next), the name at [size] on at most two lines. A
 * dark little plate behind it, which lights gold with a glow as the item takes the remote's focus ([lit], 0..1) and
 * fades with the item when it is on the board ([dim]); both are read while drawing. A dish wears its flavor's [mark]
 * at the plate's left edge, its name moved over for it. A way to cook's [hint] (given while the remote is on it) goes
 * under its name, which is one word and leaves the second line to it, coming up gold as the label lights (and fading
 * with it on the board).
 */
@Composable
private fun ShelfLabel(
    title: String,
    size: TextUnit,
    mark: FlavorMark?,
    hint: String?,
    lit: () -> Float,
    dim: () -> Float,
    x: (pitch: Float) -> Float,
    slots: Int,
    topPx: Float,
) {
    Column(
        modifier = Modifier
            .layout { measurable, constraints ->
                val pitch = constraints.maxWidth.toFloat() / slots
                val placeable = measurable.measure(Constraints(maxWidth = labelWidth(constraints.maxWidth, slots)))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place((x(pitch) - placeable.width / 2f).roundToInt(), topPx.roundToInt())
                }
            }
            .labelPlate(mark, lit, dim)
            .padding(start = labelPadStart(mark != null), end = LabelPadX, top = LabelPadY, bottom = LabelPadY),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            labelText(title),
            style = labelStyle(size),
            maxLines = LABEL_LINES,
            overflow = TextOverflow.Ellipsis,
            color = { labelInk(lit(), dim()) },
        )
        if (hint != null) {
            BasicText(
                hint.uppercase(),
                style = LabelHintStyle,
                maxLines = LABEL_LINES,
                overflow = TextOverflow.Ellipsis,
                color = { TrainingGold.copy(alpha = lit() * (1f - 0.5f * dim())) },
            )
        }
    }
}

/**
 * A lit way to cook's hint on its label: the board tag's gold hint (see [HintStyle]) at the label's smallest size, as
 * most hints wrap in a label's width ("BOILS, POACHES / AND SIMMERS") and the label must still clear the help strip.
 */
private val LabelHintStyle = HintStyle.copy(fontSize = LABEL_MIN_SP.sp, lineHeight = LABEL_MIN_SP.sp)

/** A label's name: soft white, lit pale gold, half faded on the board. */
private fun labelInk(lit: Float, dim: Float): Color {
    val ink = lerp(LabelInk, CursorEdge, lit)
    return ink.copy(alpha = ink.alpha * (1f - 0.5f * dim))
}

private val LabelInk = TextHi.copy(alpha = 0.86f)

/** An unlit label's edge: a faint gold line. */
private val LabelEdge = Amber.copy(alpha = 0.3f)

/**
 * A label's plate: dark glass cut like the cursor, its edge going gold as it is [lit], faded as it is [dim]. A dish's
 * flavor [mark] stands on it at the left, before the name, and fades with it.
 */
private fun Modifier.labelPlate(mark: FlavorMark?, lit: () -> Float, dim: () -> Float): Modifier = drawWithCache {
    val edge = 1.dp.toPx()
    val plate = chamfer(size, edge, LabelCut.toPx())
    val fill = Brush.verticalGradient(listOf(Color(0xFF1B1215), Color(0xFF070507)))
    val markSize = MarkSize.toPx()
    val markShape = mark?.let { flavorMarkPath(it, Offset(LabelPadX.toPx() + markSize / 2f, size.height / 2f), markSize) }
    onDrawBehind {
        val l = lit()
        val fade = 1f - 0.5f * dim()
        if (l > 0f) softGlow(Amber, alpha = 0.4f * l * fade, cornerRadius = 3.dp.toPx(), spread = 10.dp, layers = 5)
        drawPath(plate, fill, alpha = (0.72f + 0.24f * l) * fade)
        drawPath(plate, lerp(LabelEdge, CursorEdge, l), alpha = fade, style = Stroke(edge * (1f + l)))
        if (mark != null && markShape != null) drawPath(markShape, flavorMarkColor(mark), alpha = fade)
    }
}

/** A flavor mark's colour: blood red for savory, and the pink and green of a sugar rush and an iron stomach. */
private fun flavorMarkColor(mark: FlavorMark): Color = when (mark) {
    FlavorMark.DROP -> BloodGlow
    FlavorMark.STAR -> SweetPink
    FlavorMark.BLOT -> GrossGreen
}

private val SweetPink = Color(0xFFFF7AB8)
private val GrossGreen = Color(0xFF8BE37A)

/**
 * A dish's flavor [mark]'s shape, [size] across, centred on [center]: a teardrop for savory, a four-point star for
 * sweet, a blot of three overlapping circles for gross (see [flavorMarkColor]).
 */
private fun flavorMarkPath(mark: FlavorMark, center: Offset, size: Float): Path {
    val r = size / 2f
    return Path().apply {
        when (mark) {
            FlavorMark.DROP -> {
                // A round belly low down, its sides drawn up to a point.
                val belly = Offset(center.x, center.y + r * 0.32f)
                val br = r * 0.66f
                moveTo(center.x, center.y - r)
                cubicTo(center.x + br * 0.25f, belly.y - br * 1.4f, belly.x + br, belly.y - br * 0.7f, belly.x + br, belly.y)
                arcTo(Rect(belly, br), startAngleDegrees = 0f, sweepAngleDegrees = 180f, forceMoveTo = false)
                cubicTo(belly.x - br, belly.y - br * 0.7f, center.x - br * 0.25f, belly.y - br * 1.4f, center.x, center.y - r)
                close()
            }
            FlavorMark.STAR -> {
                // Four long points on the axes, pinched in between.
                for (i in 0 until 8) {
                    val reach = if (i % 2 == 0) r else r * 0.4f
                    val angle = Math.toRadians(-90.0 + i * 45.0)
                    val x = center.x + reach * cos(angle).toFloat()
                    val y = center.y + reach * sin(angle).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            FlavorMark.BLOT -> {
                // A big splat with two smaller ones run into it, lopsided so it never reads as a card's club.
                addOval(Rect(Offset(center.x - r * 0.2f, center.y + r * 0.1f), r * 0.62f))
                addOval(Rect(Offset(center.x + r * 0.5f, center.y - r * 0.3f), r * 0.4f))
                addOval(Rect(Offset(center.x + r * 0.42f, center.y + r * 0.58f), r * 0.3f))
            }
        }
    }
}

/**
 * [item]'s picture, fitted into this element and standing on its bottom edge over a soft contact shadow. [dim] (0..1,
 * read while drawing) fades and greys it, as an item on the board stands in its shelf slot. [heart] puts the
 * found-favorite heart on its shoulder; [known] a gold check on the other (it has made a dish with what is on the
 * board before).
 */
@Composable
internal fun ItemArt(
    loader: AssetLoader,
    item: PantryItem,
    modifier: Modifier,
    heart: Boolean = false,
    known: Boolean = false,
    dim: () -> Float = { 0f },
) {
    val image = rememberItemImage(loader, item)
    Spacer(
        modifier.drawBehind {
            val bitmap = image ?: return@drawBehind
            val fit = min(size.width / bitmap.width, size.height / bitmap.height)
            val w = bitmap.width * fit
            val h = bitmap.height * fit
            val left = (size.width - w) / 2f
            val top = size.height - h
            val d = dim()
            // The shadow it casts on what it stands on.
            drawOval(
                Color.Black.copy(alpha = 0.45f * (1f - 0.6f * d)),
                topLeft = Offset(size.width / 2f - w * 0.38f, size.height - h * 0.07f),
                size = Size(w * 0.76f, h * 0.12f),
            )
            drawImage(
                image = bitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bitmap.width, bitmap.height),
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                alpha = 1f - 0.55f * d,
                colorFilter = when {
                    d >= 1f -> OnBoardFilter
                    d > 0f -> spriteFilter(null, 0f, 0.35f * d, 1f - 0.85f * d, 0f)
                    else -> null
                },
                filterQuality = FilterQuality.High,
            )
            if (heart) drawHeart(Offset(left + w - w * 0.08f, top + h * 0.1f), size.minDimension * 0.3f, alpha = 1f - 0.5f * d)
            if (known) drawKnownCheck(Offset(left + w * 0.1f, top + h * 0.12f), size.minDimension * 0.13f, alpha = 1f - 0.5f * d)
        },
    )
}

/** A groove in the plank, where one kind of item ends and the next begins: a dark cut with a lit edge. */
private val GrooveRise = 10.dp
private val GrooveDark = Color.Black.copy(alpha = 0.45f)
private val GrooveLight = Color(0x33FFE08A)

/**
 * The "made this before" mark: a gold disc with a dark check in it, [r] in radius at [center], as the heart is drawn
 * on the other shoulder.
 */
private fun DrawScope.drawKnownCheck(center: Offset, r: Float, alpha: Float) {
    drawCircle(Color(0xFF1A1208).copy(alpha = alpha), r * 1.2f, center)
    drawCircle(CursorEdge.copy(alpha = alpha), r, center)
    val check = Path().apply {
        moveTo(center.x - r * 0.5f, center.y + r * 0.02f)
        lineTo(center.x - r * 0.12f, center.y + r * 0.42f)
        lineTo(center.x + r * 0.55f, center.y - r * 0.4f)
    }
    drawPath(check, Color(0xFF1A1208).copy(alpha = alpha), style = Stroke(width = r * 0.32f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** An item on the board, greyed and darkened in its shelf slot (built once: it is drawn every frame the shelf slides). */
private val OnBoardFilter = spriteFilter(null, 0f, 0.35f, 0.15f, 0f)

/** [item]'s picture: at once if the loader has it decoded, else decoded off the main thread (null until then). */
@Composable
private fun rememberItemImage(loader: AssetLoader, item: PantryItem): ImageBitmap? {
    val image = remember(loader, item) { mutableStateOf(loader.readyPantryItem(item)) }
    LaunchedEffect(loader, item) {
        // Asked for even when ready, so the loader counts it as used and keeps it.
        val decoded = withContext(Dispatchers.IO) { loader.pantryItem(item) }
        if (image.value == null) image.value = decoded
    }
    return image.value
}

/** The found-favorite heart, [size] across, centred on [center]: blood red with a white rim and a dark drop. */
internal fun DrawScope.drawHeart(center: Offset, size: Float, alpha: Float = 1f) {
    val heart = Path().apply {
        val w = size / 2f
        val h = size * 0.46f
        moveTo(center.x, center.y + h)
        cubicTo(center.x - w * 1.25f, center.y + h * 0.05f, center.x - w * 0.9f, center.y - h * 1.15f, center.x, center.y - h * 0.45f)
        cubicTo(center.x + w * 0.9f, center.y - h * 1.15f, center.x + w * 1.25f, center.y + h * 0.05f, center.x, center.y + h)
        close()
    }
    val rim = size * 0.16f
    translate(top = rim * 0.6f) { drawPath(heart, Color.Black.copy(alpha = 0.5f * alpha), style = Stroke(rim * 1.6f, join = StrokeJoin.Round)) }
    drawPath(heart, Color.White.copy(alpha = alpha), style = Stroke(rim, join = StrokeJoin.Round))
    drawPath(heart, BloodGlow.copy(alpha = alpha))
    // A glint, so it reads as a glossy badge like the art.
    drawCircle(Color.White.copy(alpha = 0.55f * alpha), radius = size * 0.08f, center = Offset(center.x - size * 0.16f, center.y - size * 0.12f))
}

/** Dark warm wood, lit from the kitchen's fire: the plank's top (seen from just above) and its front edge. */
private val PlankTop = Brush.verticalGradient(listOf(Color(0xFF8A5A30), Color(0xFF5E3B1E)))
private val PlankFace = listOf(Color(0xFF4E301A), Color(0xFF2A180C))
private val PlankEdgeLight = Color(0x66FFD9A0)
private val PlankGrain = Color(0x33000000)
private val BracketIron = Color(0xFF1B1513)

/** The plank's paint for a box this size, the items standing [standY] px down it (built once per size). */
private class PlankPaint(scope: Density, standY: Float) {
    private val rise = with(scope) { PlankTopRise.toPx() }
    private val depth = with(scope) { PlankTopDepth.toPx() }
    private val front = with(scope) { PlankFront.toPx() }
    private val overhang = with(scope) { 14.dp.toPx() }
    private val unit = with(scope) { 1.dp.toPx() }
    private val top = standY - rise
    private val faceTop = standY + depth
    private val faceBottom = faceTop + front
    private val face = Brush.verticalGradient(PlankFace, startY = faceTop, endY = faceBottom)

    fun draw(scope: DrawScope, moreLeft: Boolean, moreRight: Boolean) = with(scope) {
        val left = -overhang
        val right = size.width + overhang
        val width = right - left
        // Iron brackets under each end, then the plank's shadow on the wall below it.
        for (bx in listOf(left + overhang * 2.2f, right - overhang * 2.2f)) {
            drawRect(BracketIron, topLeft = Offset(bx - unit * 3f, faceBottom - unit), size = Size(unit * 6f, front * 1.1f))
        }
        drawRect(
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), startY = faceBottom, endY = faceBottom + front),
            topLeft = Offset(left + unit * 4f, faceBottom),
            size = Size(width - unit * 8f, front),
        )
        drawRect(PlankTop, topLeft = Offset(left, top), size = Size(width, faceTop - top))
        drawRect(face, topLeft = Offset(left, faceTop), size = Size(width, faceBottom - faceTop))
        // The grain along the front, and the light catching its top edge.
        for ((i, y) in listOf(0.3f, 0.55f, 0.8f).withIndex()) {
            val gy = faceTop + (faceBottom - faceTop) * y
            val inset = width * (0.04f + 0.07f * i)
            drawLine(PlankGrain, Offset(left + inset, gy), Offset(right - inset * 0.6f, gy), strokeWidth = unit)
        }
        drawLine(PlankEdgeLight, Offset(left, faceTop), Offset(right, faceTop), strokeWidth = unit)
        drawLine(Color.Black.copy(alpha = 0.6f), Offset(left, faceBottom), Offset(right, faceBottom), strokeWidth = unit)
        // More shelf past an end: a gold arrow burnt into the front edge there.
        val mid = (faceTop + faceBottom) / 2f
        val arrow = front * 0.62f
        if (moreLeft) drawPath(endArrow(Offset(unit * 2f + arrow / 2f, mid), arrow, left = true), Amber)
        if (moreRight) drawPath(endArrow(Offset(size.width - unit * 2f - arrow / 2f, mid), arrow, left = false), Amber)
    }

    private fun endArrow(center: Offset, size: Float, left: Boolean): Path = Path().apply {
        val dir = if (left) -1f else 1f
        moveTo(center.x + dir * size / 2f, center.y)
        lineTo(center.x - dir * size / 2f, center.y - size / 2f)
        lineTo(center.x - dir * size / 2f, center.y + size / 2f)
        close()
    }
}

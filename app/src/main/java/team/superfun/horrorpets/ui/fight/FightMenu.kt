package team.superfun.horrorpets.ui.fight

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.gameFrame
import team.superfun.horrorpets.ui.common.isTouchInput
import team.superfun.horrorpets.ui.common.rememberTouchTick
import team.superfun.horrorpets.ui.common.softGlow
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.theme.TextLo

// ---- the command window: the fight's moves as an RPG battle menu, down the left of the screen ----

/** The command window's width: SPECIAL, the widest command, after the cursor's gutter, inside the frame. */
internal val CommandWindowWidth = 124.dp

/** The help window under the command window, and the gap between the two. */
internal val HelpHeight = 34.dp
internal val HelpGap = 12.dp

/** A command row: compact for a remote, a fingertip tall on a phone (which draws the same 540dp-tall canvas). */
private val RowHeight = 34.dp
private val TouchRowHeight = 44.dp

/** Where a command's label starts in its row; the machete cursor sits in front of it. */
private val CursorGutter = 32.dp

private val WindowChamfer = 11.dp
private val SpecialGold = Color(0xFFF5C543)
private val LitBand = Brush.horizontalGradient(
    0f to Blood.copy(alpha = 0.95f),
    0.55f to Blood.copy(alpha = 0.55f),
    1f to Color.Transparent,
)

/** Small print under-explaining each move: the rock-paper-scissors at a glance. */
internal fun moveHint(move: Move, pet: PetId): String = when (move) {
    Move.STRIKE -> "Quick hit  ·  interrupts a Heavy"
    Move.HEAVY -> "Big hit  ·  breaks a Block, but a Strike cuts it off"
    Move.BLOCK -> "Stops a Strike and counters  ·  a Heavy breaks it"
    Move.SPECIAL -> "${specialName(pet)}  ·  never misses"
    Move.RUN -> "Forfeit the fight  ·  costs a little mood"
}

/** A [moveHint] as the help window shows it: in capitals, with each `·` between its parts in Amber. */
internal fun helpText(hint: String): AnnotatedString = buildAnnotatedString {
    hint.uppercase().split('·').map { it.trim() }.forEachIndexed { i, part ->
        if (i > 0) withStyle(SpanStyle(color = Amber, fontWeight = FontWeight.ExtraBold)) { append(" · ") }
        append(part)
    }
}

/**
 * The moves in a framed "COMMAND" window: `STRIKE HEAVY BLOCK [SPECIAL]`, a rule, `RUN`. Up and Down move the
 * machete cursor (it follows focus) and OK picks; under a finger a tap picks. SPECIAL is there only with a
 * full meter, and glows gold until picked. [onFocused] hears the move the cursor is on (or, under a finger,
 * the one last pressed), for the help window.
 */
@Composable
internal fun CommandWindow(
    specialReady: Boolean,
    requesters: Map<Move, FocusRequester>,
    canFocus: Boolean,
    onFocused: (Move) -> Unit,
    onPick: (Move) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowHeight = if (isTouchInput()) TouchRowHeight else RowHeight
    // No focus restorer here: focus only ever comes back to the menu by request (on entry, after a
    // turn, after the dialog), and a restorer would redirect those requests to its own choice.
    Column(
        modifier
            .width(CommandWindowWidth)
            .gameFrame(topEnd = WindowChamfer, bottomStart = WindowChamfer)
            .padding(start = 5.dp, end = 5.dp, top = 10.dp, bottom = 8.dp),
    ) {
        BasicText(
            "COMMAND",
            Modifier.padding(start = 9.dp),
            style = displayStyle(11.sp, FontWeight.Bold, color = Amber, tracking = 0.24f),
            maxLines = 1,
        )
        Box(
            Modifier
                .padding(start = 7.dp, end = 11.dp, top = 6.dp, bottom = 4.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(Amber.copy(alpha = 0.5f), Color.Transparent))),
        )
        val moves = buildList {
            add(Move.STRIKE)
            add(Move.HEAVY)
            add(Move.BLOCK)
            if (specialReady) add(Move.SPECIAL)
        }
        for (move in moves) {
            key(move) {
                CommandRow(move, rowHeight, requesters.getValue(move), canFocus, onFocused, onPick)
            }
        }
        Box(
            Modifier
                .padding(horizontal = 7.dp, vertical = 5.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(Amber.copy(alpha = 0.3f)),
        )
        CommandRow(Move.RUN, rowHeight - 6.dp, requesters.getValue(Move.RUN), canFocus, onFocused, onPick)
    }
}

@Composable
private fun CommandRow(
    move: Move,
    height: Dp,
    requester: FocusRequester,
    canFocus: Boolean,
    onFocused: (Move) -> Unit,
    onPick: (Move) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    // A finger pressing a command shows its help, as the cursor does (a remote's OK only presses the focused one).
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed) { if (pressed) onFocused(move) }
    CommandItem(
        onClick = { onPick(move) },
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .onFocusChanged { if (it.isFocused) onFocused(move) }
            .focusRequester(requester)
            .focusProperties { this.canFocus = canFocus },
    ) { lit -> CommandLabel(move, lit) }
}

@Composable
private fun CommandLabel(move: Move, lit: Boolean) {
    when (move) {
        Move.SPECIAL -> {
            // A breathing gold halo while it waits to be used, read in the draw phase so it never recomposes.
            val pulse = rememberInfiniteTransition(label = "specialCommand").animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(620), RepeatMode.Reverse),
                label = "specialCommandPulse",
            )
            BasicText(
                "SPECIAL",
                Modifier.drawBehind {
                    if (!lit) softGlow(SpecialGold, alpha = 0.32f * pulse.value, cornerRadius = size.height / 2f, spread = 10.dp)
                },
                style = displayStyle(24.sp, color = if (lit) TextHi else SpecialGold, tracking = 0.02f),
                maxLines = 1,
            )
        }
        Move.RUN -> BasicText(
            "RUN",
            style = displayStyle(19.sp, FontWeight.Bold, color = if (lit) TextHi else TextHi.copy(alpha = 0.5f), tracking = 0.06f),
            maxLines = 1,
        )
        else -> BasicText(
            move.name,
            style = displayStyle(24.sp, color = if (lit) TextHi else TextHi.copy(alpha = 0.6f), tracking = 0.02f),
            maxLines = 1,
        )
    }
}

/**
 * One command: while it is lit (focused, or pressed under a finger, as a [team.superfun.horrorpets.ui.common.FocusPill]
 * lights) a blood-red band runs behind it and the machete cursor points at its [content], which is told
 * whether it is lit. OK, or a tap with the `ui_select` tick, calls [onClick].
 */
@Composable
internal fun CommandItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable (lit: Boolean) -> Unit,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val lit = if (isTouchInput()) pressed else focused
    val band by animateFloatAsState(if (lit) 1f else 0f, tween(140), label = "commandBand")
    val tick = rememberTouchTick("ui_select")
    Box(
        modifier
            .clickable(interactionSource = interaction, indication = null) {
                tick()
                onClick()
            }
            .drawBehind { if (band > 0f) drawRect(LitBand, alpha = band) }
            .padding(start = CursorGutter, end = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (lit) MacheteCursor(Modifier.offset(x = 3.dp - CursorGutter))
        content(lit)
    }
}

/** The cursor: a little machete pointing at the lit command, bobbing toward it. */
@Composable
private fun MacheteCursor(modifier: Modifier = Modifier) {
    val bob = rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(350, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "cursorBob",
    )
    Canvas(
        modifier
            .graphicsLayer { translationX = bob.value * 3.dp.toPx() }
            .size(30.dp, 16.dp),
    ) { drawMachete() }
}

private val Handle = Color(0xFF3B2618)
private val HandleEdge = Color(0xFF140A05)
private val Steel = Color(0xFF2A2A2E)
private val BladeShine = Brush.verticalGradient(
    listOf(Color(0xFFF6F6F6), Color(0xFFB9BCC2), Color(0xFF6D7078)),
    startY = 4.5f,
    endY = 12f,
)

/** The machete, drawn in a 44x16 box scaled to fit: wooden handle, steel guard, shining blade, one red drip. */
private fun DrawScope.drawMachete() {
    val k = size.width / 44f
    scale(k, k, pivot = Offset.Zero) {
        drawRoundRect(Handle, topLeft = Offset(1f, 5f), size = Size(12f, 7f), cornerRadius = CornerRadius(2.5f))
        drawRoundRect(HandleEdge, topLeft = Offset(1f, 5f), size = Size(12f, 7f), cornerRadius = CornerRadius(2.5f), style = Stroke(1f))
        drawRoundRect(Steel, topLeft = Offset(12f, 3.5f), size = Size(3f, 10f), cornerRadius = CornerRadius(1f))
        val blade = Path().apply {
            moveTo(15f, 4.5f)
            lineTo(39f, 6f)
            quadraticTo(43f, 8f, 39f, 10.5f)
            lineTo(15f, 12f)
            close()
        }
        drawPath(blade, BladeShine)
        drawPath(blade, Steel, style = Stroke(0.8f))
        val drip = Path().apply {
            moveTo(24f, 8.5f)
            quadraticTo(26f, 11.5f, 24f, 13.5f)
            quadraticTo(22.5f, 11.5f, 24f, 8.5f)
            close()
        }
        drawPath(drip, Blood)
    }
}

/** The explanation of the move under the cursor, in its own small window along the floor. */
@Composable
internal fun HelpWindow(hint: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(HelpHeight)
            .gameFrame(topEnd = 10.dp, bottomStart = 0.dp, inner = false)
            .padding(start = 14.dp, end = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            helpText(hint),
            style = displayStyle(16.sp, FontWeight.Bold, color = TextHi.copy(alpha = 0.9f), tracking = 0.06f),
            maxLines = 1,
        )
    }
}

// ---- "Run away?" ----

private val ChoiceWidth = 112.dp
private val ChoiceHeight = 40.dp
private val TouchChoiceHeight = 48.dp

/**
 * "Run away?" in a command window over a scrim; [modifier] goes on the window (the caller animates it in).
 * NO and YES are commands like the moves; NO has the cursor first, and Back is No. The scrim takes every
 * touch that misses the two, so a tap can never reach the moves under it.
 */
@Composable
internal fun RunDialog(onYes: () -> Unit, onNo: () -> Unit, modifier: Modifier = Modifier) {
    val noFocus = remember { FocusRequester() }
    GameBackHandler(onBack = onNo)
    LaunchedEffect(Unit) { runCatching { noFocus.requestFocus() } }
    val choiceHeight = if (isTouchInput()) TouchChoiceHeight else ChoiceHeight
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } }
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier
                .gameFrame(topEnd = 14.dp, bottomStart = 14.dp)
                .padding(horizontal = 40.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText("RUN AWAY?", style = displayStyle(56.sp, tracking = 0.08f, lineHeight = 56.sp), maxLines = 1)
            Spacer(Modifier.height(8.dp))
            BasicText(
                "YOU FORFEIT THE FIGHT AND LOSE A LITTLE MOOD.",
                style = displayStyle(16.sp, FontWeight.Bold, color = TextLo, tracking = 0.06f),
                maxLines = 1,
            )
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CommandItem(onNo, Modifier.focusRequester(noFocus).width(ChoiceWidth).height(choiceHeight)) { lit ->
                    BasicText("NO", style = choiceStyle(lit), maxLines = 1)
                }
                CommandItem(onYes, Modifier.width(ChoiceWidth).height(choiceHeight)) { lit ->
                    BasicText("YES", style = choiceStyle(lit), maxLines = 1)
                }
            }
        }
    }
}

private fun choiceStyle(lit: Boolean) =
    displayStyle(26.sp, color = if (lit) TextHi else TextHi.copy(alpha = 0.6f), tracking = 0.04f)

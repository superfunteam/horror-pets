package team.superfun.horrorpets.ui.pvp

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.superfun.horrorpets.ui.CODE_LENGTH
import team.superfun.horrorpets.ui.LobbyPhase
import team.superfun.horrorpets.ui.PvpProblem
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.gameFrame
import team.superfun.horrorpets.ui.common.safeInset
import team.superfun.horrorpets.ui.fight.CommandItem
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.theme.Ink
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.theme.TextLo

/**
 * A phone's NFC, to join a PvP room by tapping the phones together: [listen] reads a room code off a phone
 * that is hosting one (see `PvpTapService`) until [stop]. Null where there is no NFC (every TV).
 */
interface TapToJoin {
    fun listen(onCode: (String) -> Unit)

    fun stop()
}

val LocalTapToJoin = staticCompositionLocalOf<TapToJoin?> { null }

private val Gold = Color(0xFFF5C543)
private val TitleGlow = Shadow(color = Blood.copy(alpha = 0.7f), offset = Offset.Zero, blurRadius = 30f)

/** What a [PvpProblem] says. */
internal fun problemText(problem: PvpProblem): String = when (problem) {
    PvpProblem.OFFLINE -> "Can't reach the PvP server. Check your internet."
    PvpProblem.NO_ROOM -> "No room has that code. Check it with your friend."
    PvpProblem.FULL -> "Someone else got into that room first."
    PvpProblem.VERSION -> "You're on different versions of the game. Update both."
    PvpProblem.LOST -> "Lost the connection, so the fight is off."
}

/**
 * The PvP lobby, from the opponent switcher's PvP Battle: HOST A ROOM shows a 4-digit code to give a friend and
 * waits for them; JOIN A ROOM takes a friend's code on a keypad (D-pad and OK, a remote's number keys, or taps),
 * joining as the fourth digit goes in. On a phone with NFC, tapping it against a phone that is hosting joins that
 * room from the menu or the keypad. Back steps out: from hosting or joining to the menu, from the menu to the
 * opponent switcher.
 */
@Composable
fun PvpLobbyScreen(
    phase: LobbyPhase,
    onHost: () -> Unit,
    onJoin: () -> Unit,
    onDigit: (Int) -> Unit,
    onErase: () -> Unit,
    onTapCode: (String) -> Unit,
    onBack: () -> Unit,
    focusEntry: Any? = Unit,
) {
    GameBackHandler(enabled = focusEntry != null, onBack = onBack)
    val tap = LocalTapToJoin.current
    val latestTapCode by rememberUpdatedState(onTapCode)
    // A tap joins from the menu or the keypad: listen there (and never while hosting, when this phone is the one
    // being read).
    val listening = focusEntry != null && (phase is LobbyPhase.Menu || (phase is LobbyPhase.Joining && !phase.busy))
    DisposableEffect(tap, listening) {
        if (tap != null && listening) tap.listen { latestTapCode(it) }
        onDispose { tap?.stop() }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF2A0C12), Ink), radius = 1400f)),
    ) {
        val safe = safeInset()
        Column(
            Modifier.fillMaxSize().padding(horizontal = safe.horizontal, vertical = safe.vertical),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                "PVP BATTLE",
                style = displayStyle(54.sp, tracking = 0.12f, lineHeight = 54.sp).copy(shadow = TitleGlow, textAlign = TextAlign.Center),
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                "FIGHT A FRIEND'S KILLER  ·  PHONE OR TV",
                style = displayStyle(14.sp, FontWeight.Bold, color = Gold, tracking = 0.2f),
                maxLines = 1,
            )
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when (phase) {
                    is LobbyPhase.Menu -> MenuWindow(phase, tap != null, onHost, onJoin, focusEntry)
                    is LobbyPhase.Hosting -> HostWindow(phase, tap != null, onBack, focusEntry)
                    is LobbyPhase.Joining -> JoinWindow(phase, tap != null, onDigit, onErase, focusEntry)
                }
            }
        }
    }
}

/**
 * Gives [requester] focus when its window comes up, on screen entry or when the lobby moves between windows (the
 * screen stays the same one, so focusing on screen entry alone would not do).
 *
 * When the window it replaces loses its focused command, Compose hands focus to the new window's first command on
 * its very first frame, before that command listens for its focus, so it would never light up. So the focus is
 * dropped and given again a frame later, when the command hears it.
 */
@Composable
private fun FocusOnShow(requester: FocusRequester, focusEntry: Any?) {
    val focus = LocalFocusManager.current
    LaunchedEffect(focusEntry) {
        if (focusEntry == null) return@LaunchedEffect
        withFrameNanos { }
        focus.clearFocus(force = true)
        runCatching { requester.requestFocus() }
    }
}

/** A lobby window: the game's frame, a gold [title] over a fading rule, then its content. */
@Composable
private fun LobbyWindow(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .gameFrame(topEnd = 14.dp, bottomStart = 14.dp)
            .padding(horizontal = 26.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(title, style = displayStyle(13.sp, FontWeight.Bold, color = Amber, tracking = 0.24f), maxLines = 1)
        Box(
            Modifier
                .padding(top = 8.dp, bottom = 14.dp)
                .width(220.dp)
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Amber.copy(alpha = 0.6f), Color.Transparent))),
        )
        content()
    }
}

@Composable
private fun MenuWindow(phase: LobbyPhase.Menu, canTap: Boolean, onHost: () -> Unit, onJoin: () -> Unit, focusEntry: Any?) {
    val first = remember { FocusRequester() }
    FocusOnShow(first, focusEntry)
    LobbyWindow("CHOOSE ONE", Modifier.width(440.dp)) {
        LobbyCommand("HOST A ROOM", "Get a code to give your friend", onHost, Modifier.focusRequester(first))
        Spacer(Modifier.height(8.dp))
        LobbyCommand("JOIN A ROOM", "Type the code your friend got", onJoin)
        phase.problem?.let {
            Spacer(Modifier.height(14.dp))
            Note(problemText(it), BloodGlow)
        }
        Spacer(Modifier.height(14.dp))
        Note(if (canTap) "On phones, just tap them back to back to join" else "Both players need the game and the internet", TextLo)
    }
}

/** A command with its explanation under it, lit with the machete cursor like the fight's. */
@Composable
private fun LobbyCommand(label: String, hint: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    CommandItem(onClick = onClick, modifier = modifier.fillMaxWidth().height(58.dp)) { lit ->
        Column {
            BasicText(label, style = displayStyle(28.sp, color = if (lit) TextHi else TextHi.copy(alpha = 0.75f), tracking = 0.06f, lineHeight = 28.sp), maxLines = 1)
            BasicText(hint.uppercase(), style = displayStyle(12.sp, FontWeight.Bold, color = TextLo, tracking = 0.12f, lineHeight = 14.sp), maxLines = 1)
        }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    BasicText(
        text.uppercase(),
        style = displayStyle(13.sp, FontWeight.Bold, color = color, tracking = 0.12f, lineHeight = 16.sp).copy(textAlign = TextAlign.Center),
    )
}

@Composable
private fun HostWindow(phase: LobbyPhase.Hosting, canTap: Boolean, onCancel: () -> Unit, focusEntry: Any?) {
    val cancel = remember { FocusRequester() }
    FocusOnShow(cancel, focusEntry)
    val code = phase.code
    LobbyWindow("YOUR ROOM CODE", Modifier.width(500.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (i in 0 until CODE_LENGTH) DigitTile(code?.getOrNull(i)?.toString(), lit = code != null)
        }
        Spacer(Modifier.height(16.dp))
        WaitingLine(if (code == null) "OPENING A ROOM" else "WAITING FOR A CHALLENGER")
        Spacer(Modifier.height(10.dp))
        Note(
            if (canTap) "Your friend picks PvP Battle, then Join a room and types this code, or taps phones with you" else "Your friend picks PvP Battle, then Join a room and types this code",
            TextLo,
        )
        Spacer(Modifier.height(14.dp))
        CommandItem(onClick = onCancel, modifier = Modifier.focusRequester(cancel).width(180.dp).height(40.dp)) { lit ->
            BasicText("CANCEL", style = displayStyle(24.sp, color = if (lit) TextHi else TextHi.copy(alpha = 0.7f), tracking = 0.06f), maxLines = 1)
        }
    }
}

/** One digit of a code in a gold tile: blank (or a pulsing dot while the code is coming) until it is known. */
@Composable
private fun DigitTile(digit: String?, lit: Boolean, cursor: Boolean = false) {
    val pulse = rememberInfiniteTransition(label = "digit").animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "digitPulse",
    )
    Box(
        Modifier
            .size(width = 64.dp, height = 84.dp)
            .gameFrame(topEnd = 10.dp, bottomStart = 10.dp, lit = { if (lit && digit != null) 0.6f else if (cursor) pulse.value * 0.8f else 0f }),
        contentAlignment = Alignment.Center,
    ) {
        if (digit != null) {
            BasicText(digit, style = displayStyle(58.sp, color = Gold, lineHeight = 58.sp), maxLines = 1)
        } else if (!lit || cursor) {
            BasicText("•", style = displayStyle(40.sp, color = TextLo, lineHeight = 40.sp), maxLines = 1, modifier = Modifier.graphicsLayer { alpha = pulse.value })
        }
    }
}

/** [text] with three dots that fill in and start over, to say something is under way. */
@Composable
private fun WaitingLine(text: String) {
    val t = rememberInfiniteTransition(label = "waiting").animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(1600)),
        label = "waitingDots",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(text, style = displayStyle(22.sp, color = TextHi, tracking = 0.1f), maxLines = 1)
        // Fixed width, so the line never shifts as the dots come and go.
        Box(Modifier.width(30.dp)) {
            Row {
                for (i in 0 until 3) {
                    BasicText(".", style = displayStyle(22.sp, color = TextHi, tracking = 0.1f), maxLines = 1, modifier = Modifier.graphicsLayer { alpha = if (t.value > i + 1) 1f else 0f })
                }
            }
        }
    }
}

/** The keypad's keys, row by row: digits, then erase and 0. */
private val KeypadRows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(ERASE, 0))
private const val ERASE = -1

@Composable
private fun JoinWindow(phase: LobbyPhase.Joining, canTap: Boolean, onDigit: (Int) -> Unit, onErase: () -> Unit, focusEntry: Any?) {
    val first = remember { FocusRequester() }
    FocusOnShow(first, focusEntry)
    // After a failed try the keypad starts over: put the cursor back on 1.
    LaunchedEffect(phase.problem) { if (phase.problem != null) runCatching { first.requestFocus() } }
    LobbyWindow(
        "ENTER A ROOM CODE",
        Modifier
            // As wide as a 4:3 screen allows (648dp inside its safe inset), so the keypad clears the frame.
            .width(640.dp)
            // A remote's number keys and Delete work wherever the cursor is.
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = digitOf(e.key)
                when {
                    digit != null -> onDigit(digit).let { true }
                    e.key == Key.Backspace || e.key == Key.Delete -> onErase().let { true }
                    else -> false
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (i in 0 until CODE_LENGTH) {
                        DigitTile(phase.digits.getOrNull(i)?.toString(), lit = true, cursor = !phase.busy && i == phase.digits.length)
                    }
                }
                Spacer(Modifier.height(14.dp))
                when {
                    phase.busy -> WaitingLine("JOINING")
                    phase.problem != null -> Note(problemText(phase.problem), BloodGlow)
                    canTap -> Note("Or tap phones back to back", TextLo)
                    else -> Note("Use the keypad or your remote's numbers", TextLo)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KeypadRows.forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEachIndexed { c, key ->
                            val mod = if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier
                            KeypadKey(key, onClick = { if (key == ERASE) onErase() else onDigit(key) }, modifier = mod)
                        }
                    }
                }
            }
        }
    }
}

/** One keypad key: a digit, or ⌫. Lit with the machete cursor like any command. */
@Composable
private fun KeypadKey(key: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    CommandItem(onClick = onClick, modifier = modifier.size(width = 60.dp, height = 42.dp)) { lit ->
        BasicText(
            if (key == ERASE) "⌫" else "$key",
            style = displayStyle(28.sp, color = if (lit) TextHi else TextHi.copy(alpha = 0.75f), lineHeight = 28.sp),
            maxLines = 1,
        )
    }
}

/** The digit a number key (top row or keypad) types, if it is one. */
private fun digitOf(key: Key): Int? = when (key) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}

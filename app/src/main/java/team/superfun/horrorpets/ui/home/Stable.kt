package team.superfun.horrorpets.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.game.xpToNext
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.KeyCap
import team.superfun.horrorpets.ui.common.RingMedallion
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.gameFrame
import team.superfun.horrorpets.ui.common.isTouchInput
import team.superfun.horrorpets.ui.common.rememberTouchTick
import team.superfun.horrorpets.ui.common.ringValues
import team.superfun.horrorpets.ui.common.safeInset
import team.superfun.horrorpets.ui.common.softGlow
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.theme.TextLo

/** How long the badge shows each of the other killers before moving on to the next. */
private const val CYCLE_MS = 4_000L

private val BadgeWidth = 132.dp
private val BadgeMedallion = 120.dp
private val Gold = Color(0xFFF5C543)
private val SlotLit = Brush.verticalGradient(
    0f to Color(0x00C1121F),
    0.3f to Color(0xD9C1121F),
    0.7f to Color(0xD9C1121F),
    1f to Color(0x00C1121F),
)

/** The killer the badge shows when it opens the stable, so the menu can start on them. */
internal fun badgePet(others: List<PetState>, index: Int): PetState? = others.getOrNull(index.mod(others.size.coerceAtLeast(1)))

/**
 * The stable at a glance, top right of Home once there is someone else in it: one of [others] (the stable's other
 * killers, in roster order) as a [RingMedallion], their head in their Fed, Fun, Strength and Mood rings, with their
 * name, level and XP under it. With several it moves on to the next every [CYCLE_MS] (gold diamonds show which),
 * holding still while focused; [onShown] hears who is up. Focusable from the action bar (Up), unless [canFocus] is
 * off; OK or a tap opens the stable ([onOpen]).
 */
@Composable
internal fun StableBadge(
    others: List<PetState>,
    loader: AssetLoader,
    onOpen: () -> Unit,
    onShown: (PetId) -> Unit,
    focusRequester: FocusRequester,
    canFocus: Boolean,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val touch = isTouchInput()
    val lit = if (touch) pressed else focused
    val glow = animateFloatAsState(if (lit) 1f else 0f, tween(200), label = "badgeGlow")
    val scale by animateFloatAsState(
        targetValue = when {
            touch -> if (pressed) 0.96f else 1f
            focused -> 1.06f
            else -> 1f
        },
        animationSpec = tween(200),
        label = "badgeScale",
    )
    val tick = rememberTouchTick("ui_select")
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(others.size, focused) {
        if (others.size < 2 || focused) return@LaunchedEffect
        while (true) {
            delay(CYCLE_MS)
            index = (index + 1) % others.size
        }
    }
    val pet = badgePet(others, index) ?: return
    LaunchedEffect(pet.id) { onShown(pet.id) }

    Column(
        modifier
            .width(BadgeWidth)
            .graphicsLayer {
                // Grow away from the screen corner so the badge never leaves the safe inset.
                transformOrigin = TransformOrigin(1f, 0f)
                scaleX = scale
                scaleY = scale
            }
            .focusRequester(focusRequester)
            .focusProperties { this.canFocus = canFocus }
            .clickable(interactionSource = interaction, indication = null) {
                tick()
                onOpen()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Crossfade(targetState = pet, animationSpec = tween(400), label = "badgePet") { shown ->
            RingMedallion(
                head = remember(loader, shown.id) { loader.head(shown.id) },
                values = ringValues(shown),
                modifier = Modifier
                    .size(BadgeMedallion)
                    .drawBehind {
                        val l = glow.value
                        if (l > 0f) softGlow(Amber, alpha = 0.5f * l, cornerRadius = size.minDimension / 2f, spread = 14.dp)
                    },
            )
        }
        Column(
            Modifier
                .offset(y = (-4).dp)
                .width(BadgeWidth)
                .gameFrame(topEnd = 8.dp, bottomStart = 8.dp, inner = false, lit = { glow.value })
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    pet.id.displayName.uppercase(),
                    style = displayStyle(17.sp, tracking = 0.04f),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 17.sp, stepSize = 1.sp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                BasicText("LV ${pet.level}", style = displayStyle(14.sp, color = Gold, tracking = 0.04f))
            }
            Spacer(Modifier.height(5.dp))
            XpBar((pet.xp.toFloat() / xpToNext(pet.level)).coerceIn(0f, 1f), Modifier.fillMaxWidth().height(5.dp))
        }
        if (others.size > 1) {
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                others.forEachIndexed { i, _ ->
                    Box(
                        Modifier
                            .size(6.dp)
                            .rotate(45f)
                            .background(if (i == index % others.size) Gold else TextHi.copy(alpha = 0.3f)),
                    )
                }
            }
        }
    }
}

/**
 * The stable, over a dimmed Home: all of the killers in roster order. Unlocked ones are [RingMedallion]s with their
 * name and level (the one on screen tagged HERE); locked ones are dark silhouettes, "???" and BEAT TO UNLOCK. A
 * header says how many are unlocked. Left and Right move between the unlocked ones (the focused slot lights up
 * blood red), starting on [first]; OK or a tap goes to see that one ([onPick]); Back, or a tap outside the window,
 * closes it ([onClose]).
 */
@Composable
internal fun StableMenu(game: GameState, first: PetId?, loader: AssetLoader, onPick: (PetId) -> Unit, onClose: () -> Unit) {
    GameBackHandler(onBack = onClose)
    val touch = isTouchInput()
    val requesters = remember { PetId.entries.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) {
        val start = first?.takeIf { game.isUnlocked(it) } ?: game.pets.keys.firstOrNull { it != game.selected } ?: game.selected
        if (start != null) runCatching { requesters.getValue(start).requestFocus() }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            // Taps, not clickable: a clickable is focusable too, and Back would only move focus out to it.
            .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center,
    ) {
        val safe = safeInset()
        Column(
            Modifier
                .width(minOf(780.dp, maxWidth - safe.horizontal * 2))
                // The window takes its own taps, so only a tap outside it closes the stable.
                .pointerInput(Unit) { detectTapGestures { } }
                .gameFrame(topEnd = 14.dp, bottomStart = 14.dp)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText("YOUR STABLE", style = displayStyle(30.sp, tracking = 0.04f), maxLines = 1)
                Spacer(Modifier.weight(1f))
                BasicText(
                    "${game.pets.size} / ${PetId.entries.size} UNLOCKED",
                    style = displayStyle(16.sp, color = Gold, tracking = 0.16f),
                    maxLines = 1,
                )
            }
            Box(
                Modifier
                    .padding(top = 8.dp, bottom = 12.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Brush.horizontalGradient(listOf(Amber.copy(alpha = 0.55f), Color.Transparent))),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (id in PetId.entries) {
                    val pet = game.pets[id]
                    StableSlot(
                        id = id,
                        pet = pet,
                        here = id == game.selected,
                        loader = loader,
                        requester = requesters.getValue(id),
                        onPick = onPick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (touch) {
                    Hint("TAP A KILLER TO GO SEE THEM")
                } else {
                    Hint("◀ ▶  CHOOSE")
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        KeyCap("OK")
                        Hint("GO SEE THEM")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                        KeyCap("BACK")
                        Hint("CLOSE")
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    BasicText(text, style = displayStyle(13.sp, FontWeight.Bold, color = TextHi.copy(alpha = 0.72f), tracking = 0.2f), maxLines = 1)
}

/**
 * One killer's slot in the stable: [pet] unlocked (selectable, lit blood red while focused or pressed, tagged HERE
 * when [here]), or null for a locked one (a silhouette, not selectable).
 */
@Composable
private fun StableSlot(
    id: PetId,
    pet: PetState?,
    here: Boolean,
    loader: AssetLoader,
    requester: FocusRequester,
    onPick: (PetId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val lit = pet != null && (if (isTouchInput()) pressed else focused)
    val glow by animateFloatAsState(if (lit) 1f else 0f, tween(160), label = "slotGlow")
    val tick = rememberTouchTick("ui_select")
    val head = remember(loader, id) { loader.head(id) }
    val slot = if (pet == null) {
        modifier
    } else {
        modifier
            .focusRequester(requester)
            .clickable(interactionSource = interaction, indication = null) {
                tick()
                onPick(id)
            }
    }
    Box(slot.height(160.dp).drawBehind { if (glow > 0f) drawRect(SlotLit, alpha = glow) }) {
        Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RingMedallion(head = head, values = pet?.let(::ringValues), modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(8.dp))
            BasicText(
                if (pet == null) "???" else id.displayName.uppercase(),
                style = displayStyle(16.sp, color = if (pet == null) TextLo.copy(alpha = 0.5f) else TextHi, tracking = 0.03f)
                    .copy(textAlign = TextAlign.Center),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 16.sp, stepSize = 1.sp),
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            Spacer(Modifier.height(5.dp))
            if (pet == null) {
                BasicText(
                    "BEAT TO\nUNLOCK",
                    style = displayStyle(11.sp, FontWeight.Bold, color = TextLo.copy(alpha = 0.6f), tracking = 0.12f, lineHeight = 13.sp)
                        .copy(textAlign = TextAlign.Center),
                )
            } else {
                BasicText("LV ${pet.level}", style = displayStyle(13.sp, color = Gold, tracking = 0.04f))
            }
        }
        if (here) {
            BasicText(
                "HERE",
                style = displayStyle(10.sp, color = Gold, tracking = 0.14f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .background(Gold.copy(alpha = 0.18f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
    }
}

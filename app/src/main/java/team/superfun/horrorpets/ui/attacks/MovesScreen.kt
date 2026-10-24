package team.superfun.horrorpets.ui.attacks

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.superfun.horrorpets.model.Attack
import team.superfun.horrorpets.assets.AssetLoader
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.Slot
import team.superfun.horrorpets.model.attacksOf
import team.superfun.horrorpets.model.kitOf
import team.superfun.horrorpets.ui.common.GameBackHandler
import team.superfun.horrorpets.ui.common.LocalSoundSink
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.common.gameFrame
import team.superfun.horrorpets.ui.common.isTouchInput
import team.superfun.horrorpets.ui.common.safeInset
import team.superfun.horrorpets.ui.home.Chevron
import team.superfun.horrorpets.ui.theme.Amber
import team.superfun.horrorpets.ui.theme.Blood
import team.superfun.horrorpets.ui.theme.Ink
import team.superfun.horrorpets.ui.theme.TextHi
import team.superfun.horrorpets.ui.theme.TextLo

/** Where an attack stands for a pet on the MOVES screen. */
internal enum class Standing { EQUIPPED, READY, LOCKED }

/** Whether [a] is still locked for [pet]: its level has not reached [a]'s. A starting attack never is. */
internal fun isLocked(pet: PetState, a: Attack): Boolean = a.level > pet.level

/**
 * The gold title over the MOVES card for [browsed], with [equipped] in its slot: EQUIPPED LIGHT for the one in it,
 * LIGHT · LOCKED for a [locked] one (nothing to weigh it against yet), else LIGHT · AGAINST what is equipped.
 */
internal fun movesCardTitle(browsed: Attack, equipped: Attack, locked: Boolean): String {
    val slot = browsed.slot.label.uppercase()
    return when {
        browsed == equipped -> "EQUIPPED $slot"
        locked -> "$slot · LOCKED"
        else -> "$slot · AGAINST ${equipped.name.uppercase()}"
    }
}

/** [a]'s standing for [pet]: in its slot now, unlocked and ready to equip, or still locked by level. */
internal fun standingOf(pet: PetState, a: Attack): Standing = when {
    kitOf(pet).of(a.slot) == a -> Standing.EQUIPPED
    !isLocked(pet, a) -> Standing.READY
    else -> Standing.LOCKED
}

/**
 * MOVES: [pet]'s three slots down the left, each showing one of its four attacks (starting on the equipped one),
 * and that attack's card on the right, against what is equipped. Up and Down pick a slot; Left and Right (or the
 * arrows, on a phone) browse its attacks, the locked ones too, which keep their secret (no name, stats or preview:
 * see [cardFace]) and say the level they unlock at; OK (or a tap on the slot) equips the one shown ([onEquip] with
 * its id), when it is unlocked. Back ([onBack]) returns Home. The card's preview takes the killer's sprites and any
 * picture it throws from [loader].
 */
@Composable
fun MovesScreen(pet: PetState, loader: AssetLoader, onEquip: (String) -> Unit, onBack: () -> Unit, focusEntry: Any? = Unit) {
    GameBackHandler(enabled = focusEntry != null, onBack = onBack)
    val first = remember { FocusRequester() }
    RequestFocusOnEntry(first, focusEntry)
    val kit = kitOf(pet)
    // The tier each slot is showing, from the equipped one; and the slot being looked at, for the card.
    val shown = remember { mutableStateMapOf<Slot, Int>().apply { Slot.entries.forEach { put(it, kit.of(it).tier) } } }
    var looking by remember { mutableStateOf(Slot.LIGHT) }
    val sounds = LocalSoundSink.current

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF2A0C12), Ink), radius = 1400f)),
    ) {
        val safe = safeInset()
        Column(Modifier.fillMaxSize().padding(horizontal = safe.horizontal, vertical = safe.vertical)) {
            ScreenTitle("${pet.id.displayName}'s moves", "Level ${pet.level}")
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (slot in Slot.entries) {
                        val attacks = attacksOf(pet.id, slot)
                        val tier = shown[slot] ?: 0
                        SlotRow(
                            pet = pet,
                            slot = slot,
                            attack = attacks[tier],
                            tier = tier,
                            // Browsing or tapping a slot looks at it too: on a phone, nothing takes focus to say so.
                            onBrowse = { delta ->
                                looking = slot
                                val next = (tier + delta).coerceIn(0, attacks.lastIndex)
                                if (next != tier) {
                                    shown[slot] = next
                                    sounds.sfx("ui_move")
                                }
                            },
                            onEquip = {
                                looking = slot
                                onEquip(attacks[tier].id)
                            },
                            onFocus = { looking = slot },
                            modifier = Modifier.weight(1f).let { if (slot == Slot.LIGHT) it.focusRequester(first) else it },
                        )
                    }
                }
                val browsed = attacksOf(pet.id, looking)[shown[looking] ?: 0]
                val equipped = kit.of(looking)
                val locked = isLocked(pet, browsed)
                AttackCard(
                    browsed,
                    vs = equipped.takeIf { it != browsed },
                    title = movesCardTitle(browsed, equipped, locked),
                    loader = loader,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    lit = browsed == equipped,
                    locked = locked,
                )
            }
            Spacer(Modifier.height(8.dp))
            BasicText(
                if (isTouchInput()) "ARROWS TO BROWSE  ·  TAP A SLOT TO EQUIP  ·  BACK WHEN DONE" else "◀ ▶ BROWSE  ·  OK EQUIP  ·  BACK WHEN DONE",
                style = displayStyle(12.sp, FontWeight.Bold, color = TextLo, tracking = 0.14f),
                maxLines = 1,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/**
 * One slot: its label, ◀ the attack it shows ▶ (its name stepping down to fit, or [LOCKED_TEXT] while it is locked),
 * four pips for the tiers (the shown one gold and bigger, a locked one a tiny padlock), and where that attack stands.
 * Lit blood red while focused.
 */
@Composable
private fun SlotRow(
    pet: PetState,
    slot: Slot,
    attack: Attack,
    tier: Int,
    onBrowse: (Int) -> Unit,
    onEquip: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val lit = if (isTouchInput()) pressed else isFocused
    LaunchedEffect(isFocused) { if (isFocused) onFocus() }
    val band by animateFloatAsState(if (lit) 1f else 0f, tween(140), label = "slotBand")
    val standing = standingOf(pet, attack)
    Column(
        modifier
            .fillMaxWidth()
            .gameFrame(topEnd = 10.dp, bottomStart = 10.dp, lit = { band })
            .drawBehind { if (band > 0f) drawRect(Brush.horizontalGradient(listOf(Blood.copy(alpha = 0.5f * band), Color.Transparent))) }
            .onKeyEvent { e ->
                when {
                    e.type != KeyEventType.KeyDown -> false
                    e.key == Key.DirectionLeft -> onBrowse(-1).let { true }
                    e.key == Key.DirectionRight -> onBrowse(1).let { true }
                    else -> false
                }
            }
            .clickable(interactionSource = interaction, indication = null) { onEquip() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        BasicText(
            // A light attack is what STRIKE swings; HEAVY and SPECIAL are their own commands.
            if (slot == Slot.LIGHT) "LIGHT  ·  STRIKE" else slot.label.uppercase(),
            style = displayStyle(11.sp, FontWeight.Bold, color = Amber, tracking = 0.22f),
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clickable { onBrowse(-1) }, contentAlignment = Alignment.Center) {
                Chevron(left = true, color = if (tier > 0) TextHi else TextLo.copy(alpha = 0.3f), modifier = Modifier.size(16.dp))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                AttackName(if (standing == Standing.LOCKED) LOCKED_TEXT else attack.name, max = 24f, center = true, modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.size(28.dp).clickable { onBrowse(1) }, contentAlignment = Alignment.Center) {
                Chevron(left = false, color = if (tier < 3) TextHi else TextLo.copy(alpha = 0.3f), modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Spacer(Modifier.width(28.dp))
            for (t in 0..3) {
                val locked = isLocked(pet, attacksOf(pet.id, slot)[t])
                // A locked tier's pip is a padlock a little taller than a dot, so it still reads as one of the four.
                Box(
                    Modifier
                        .size(
                            when {
                                locked && t == tier -> 13.dp
                                locked -> 11.dp
                                t == tier -> 10.dp
                                else -> 8.dp
                            },
                        )
                        .drawBehind {
                            val color = if (t == tier) Amber else if (locked) TextLo.copy(alpha = 0.55f) else TextHi.copy(alpha = 0.6f)
                            if (locked) drawPadlock(center, size.height, color) else drawCircle(color)
                        },
                )
            }
            Spacer(Modifier.width(6.dp))
            BasicText(
                when (standing) {
                    Standing.EQUIPPED -> "EQUIPPED"
                    Standing.READY -> if (isTouchInput()) "TAP TO EQUIP" else "OK TO EQUIP"
                    Standing.LOCKED -> "UNLOCKS AT LEVEL ${attack.level}"
                },
                style = displayStyle(
                    12.sp,
                    FontWeight.Bold,
                    color = when (standing) {
                        Standing.EQUIPPED -> Better
                        Standing.READY -> TextHi
                        Standing.LOCKED -> TextLo
                    },
                    tracking = 0.14f,
                ),
                maxLines = 1,
            )
        }
    }
}

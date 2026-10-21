// Placeholder — replaced in Task 14 (turn-based fight).
package team.superfun.horrorpets.ui.fight

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.fight.Fighter
import team.superfun.horrorpets.fight.Move
import team.superfun.horrorpets.ui.PetAnimRequest
import team.superfun.horrorpets.ui.Screen
import team.superfun.horrorpets.ui.common.FocusPill
import team.superfun.horrorpets.ui.common.RequestFocusOnEntry
import team.superfun.horrorpets.ui.common.bodyStyle
import team.superfun.horrorpets.ui.common.displayStyle
import team.superfun.horrorpets.ui.theme.Ink
import team.superfun.horrorpets.ui.theme.TextLo

/**
 * Functional stand-in for the fight: both fighters' HP and meter as text, the last turn's events, and
 * the move pills. Each turn's animation marker is reported finished at once, so the fight moves on.
 */
@Composable
fun FightScreen(screen: Screen.Fight, petAnim: PetAnimRequest?, onMove: (Move) -> Unit, onAnimFinished: (Long) -> Unit, focusEntry: Any? = Unit) {
    val focus = remember { FocusRequester() }
    val finished by rememberUpdatedState(onAnimFinished)
    BackHandler { /* Task 14 asks "Run away?" here */ }
    RequestFocusOnEntry(focus, focusEntry)
    LaunchedEffect(petAnim?.token) { petAnim?.let { finished(it.token) } }

    val fight = screen.fight
    val moves = buildList {
        add(Move.STRIKE)
        add(Move.HEAVY)
        add(Move.BLOCK)
        if (fight.player.meter >= FightTuning.METER_MAX) add(Move.SPECIAL)
        add(Move.RUN)
    }
    Column(
        Modifier.fillMaxSize().background(Ink),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(80.dp)) {
            FighterLine(fight.player)
            FighterLine(fight.cpu)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = screen.lastEvents.joinToString("  ·  ") { it.toString() }.ifEmpty { "TURN ${fight.turn}" },
            style = bodyStyle(16.sp, color = TextLo),
            maxLines = 3,
        )
        Spacer(Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            moves.forEachIndexed { i, move ->
                FocusPill(
                    label = move.name,
                    onClick = { onMove(move) },
                    modifier = if (i == 0) Modifier.focusRequester(focus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun FighterLine(f: Fighter) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(f.id.displayName.uppercase(), style = displayStyle(44.sp))
        Text("HP ${f.hp} / ${f.maxHp}", style = displayStyle(28.sp))
        Text("METER ${f.meter}", style = displayStyle(22.sp, color = TextLo))
    }
}

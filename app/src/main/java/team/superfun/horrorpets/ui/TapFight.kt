package team.superfun.horrorpets.ui

import team.superfun.horrorpets.game.canFight
import team.superfun.horrorpets.game.training.mustTrainBeforeFighting
import team.superfun.horrorpets.pvp.nfc.TapMode

/*
 * Tapping two phones together to fight (NFC; see pvp/nfc/TapProtocol.kt). From Home, the opponent switcher or the
 * PvP lobby's menu, a tap starts a PvP fight with no codes at all: one phone opens a room and hands its code to the
 * other over the tap. On the lobby's code entry, a tap joins a phone that is hosting.
 */

/**
 * Whether a tap starts a fight from where [s] is: Home with a pet that FIGHT would let through (fed enough, done
 * its training), the opponent switcher, or the PvP lobby's menu.
 */
fun tapFightOpen(s: UiState): Boolean = when (val screen = s.screen) {
    Screen.Home -> {
        val pet = s.game.selected?.let { s.game.pets[it] }
        pet != null && !s.busy && !s.stableOpen && canFight(pet) && !mustTrainBeforeFighting(s.game, pet)
    }
    is Screen.Switcher -> screen.mode == SwitcherMode.OPPONENT && s.game.selected != null
    is Screen.PvpLobby -> screen.phase is LobbyPhase.Menu
    else -> false
}

/** Whether a tap may join a room (or open one for a ready phone) from where [s] is. */
fun tapJoinOpen(s: UiState): Boolean =
    tapFightOpen(s) || ((s.screen as? Screen.PvpLobby)?.phase as? LobbyPhase.Joining)?.busy == false

/**
 * How the phone's NFC reads from where [s] is: in turns with being read wherever a tap starts a fight, all the
 * time on the code entry (a friend there is waiting to read a host), and not at all anywhere else.
 */
fun tapModeOf(s: UiState): TapMode = when {
    tapFightOpen(s) -> TapMode.TAKE_TURNS
    tapJoinOpen(s) -> TapMode.LISTEN
    else -> TapMode.OFF
}

/**
 * Whether this phone, read by a tap, says it is ready for a fight: wherever a tap starts one, and while it waits for
 * the room code a tap promised it.
 */
fun tapReadyOf(s: UiState): Boolean = tapFightOpen(s) || (s.screen as? Screen.PvpLobby)?.phase == LobbyPhase.Tapped

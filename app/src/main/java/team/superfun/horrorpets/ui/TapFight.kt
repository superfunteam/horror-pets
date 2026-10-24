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

/**
 * Whether a tap may join a room (or open one for a ready phone) from where [s] is: wherever a tap starts a fight, the
 * code entry, and a tap's own fight still connecting or asking for another tap.
 */
fun tapJoinOpen(s: UiState): Boolean {
    if (tapFightOpen(s)) return true
    return when (val phase = (s.screen as? Screen.PvpLobby)?.phase) {
        is LobbyPhase.Joining -> !phase.busy
        is LobbyPhase.Tapped -> true
        else -> false
    }
}

/**
 * How the phone's NFC reads from where [s] is: in turns with being read wherever a tap starts a fight, and when a tap
 * asks for another (the friend's phone may be reading too, or hosting); all the time on the code entry; and not at
 * all anywhere else. A phone waiting for the room a tap promised it is only a card.
 */
fun tapModeOf(s: UiState): TapMode {
    if (tapFightOpen(s)) return TapMode.TAKE_TURNS
    return when (val phase = (s.screen as? Screen.PvpLobby)?.phase) {
        is LobbyPhase.Joining -> if (phase.busy) TapMode.OFF else TapMode.LISTEN
        is LobbyPhase.Tapped -> if (phase.again) TapMode.TAKE_TURNS else TapMode.OFF
        else -> TapMode.OFF
    }
}

/**
 * Whether this phone, read by a tap, says it is ready for a fight: wherever a tap starts one, while it waits for the
 * room a tap promised it, and when a tap asks for another.
 */
fun tapReadyOf(s: UiState): Boolean = tapFightOpen(s) || (s.screen as? Screen.PvpLobby)?.phase is LobbyPhase.Tapped

/** Whether this phone is opening a room whose code is not in yet (a tap reading it asks again until it is). */
fun tapOpeningOf(s: UiState): Boolean = ((s.screen as? Screen.PvpLobby)?.phase as? LobbyPhase.Hosting)?.let { it.code == null } == true

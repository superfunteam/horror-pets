package team.superfun.horrorpets.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.game.training.FIGHT_PREREQUISITE_NAMES
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.pvp.nfc.TapMode

/** Where a tap of two phones starts a fight, and how the phone's NFC reads there. */
class TapFightTest {
    private val trained = PetState(PetId.JASON, trainedGames = FIGHT_PREREQUISITE_NAMES)
    private val game = GameState(pets = mapOf(PetId.JASON to trained), selected = PetId.JASON)
    private fun at(screen: Screen, game: GameState = this.game) = UiState(screen = screen, game = game)

    @Test
    fun homeTakesTurnsAndIsReady() {
        val home = at(Screen.Home)
        assertTrue(tapFightOpen(home))
        assertEquals(TapMode.TAKE_TURNS, tapModeOf(home))
        assertTrue(tapReadyOf(home))
    }

    @Test
    fun homeIsClosedToATapWhereFightWouldRefuse() {
        val starving = game.copy(pets = mapOf(PetId.JASON to trained.copy(hunger = 90.0)))
        assertFalse("too hungry", tapFightOpen(at(Screen.Home, starving)))
        val untrained = game.copy(pets = mapOf(PetId.JASON to PetState(PetId.JASON)))
        assertFalse("owes its training", tapFightOpen(at(Screen.Home, untrained)))
        assertFalse("mid-action", tapFightOpen(at(Screen.Home).copy(busy = true)))
        assertFalse("stable open", tapFightOpen(at(Screen.Home).copy(stableOpen = true)))
        assertFalse("no pet", tapFightOpen(at(Screen.Home, GameState())))
        assertEquals(TapMode.OFF, tapModeOf(at(Screen.Home, starving)))
        assertFalse(tapReadyOf(at(Screen.Home, starving)))
    }

    @Test
    fun theOpponentSwitcherAndTheLobbyMenuTakeTurnsToo() {
        assertEquals(TapMode.TAKE_TURNS, tapModeOf(at(Screen.Switcher(SwitcherMode.OPPONENT))))
        assertEquals(TapMode.OFF, tapModeOf(at(Screen.Switcher(SwitcherMode.ONBOARD))))
        assertEquals(TapMode.TAKE_TURNS, tapModeOf(at(Screen.PvpLobby(LobbyPhase.Menu()))))
        assertTrue(tapReadyOf(at(Screen.PvpLobby(LobbyPhase.Menu()))))
    }

    @Test
    fun theCodeEntryOnlyReads() {
        val joining = at(Screen.PvpLobby(LobbyPhase.Joining("4")))
        assertEquals(TapMode.LISTEN, tapModeOf(joining))
        assertTrue(tapJoinOpen(joining))
        assertFalse(tapReadyOf(joining))
        val busy = at(Screen.PvpLobby(LobbyPhase.Joining("4821", busy = true)))
        assertEquals(TapMode.OFF, tapModeOf(busy))
        assertFalse(tapJoinOpen(busy))
    }

    @Test
    fun aHostOrAPhoneAwaitingACodeIsOnlyACard() {
        val hosting = at(Screen.PvpLobby(LobbyPhase.Hosting("4821")))
        assertEquals(TapMode.OFF, tapModeOf(hosting))
        assertFalse("its code answers a tap instead", tapReadyOf(hosting))
        assertFalse(tapOpeningOf(hosting))
        val tapped = at(Screen.PvpLobby(LobbyPhase.Tapped()))
        assertEquals(TapMode.OFF, tapModeOf(tapped))
        assertTrue("still ready for the room it was promised", tapReadyOf(tapped))
        assertTrue("and takes it, read off the friend's phone", tapJoinOpen(tapped))
    }

    @Test
    fun aRoomWithNoCodeYetIsOpening() {
        assertTrue(tapOpeningOf(at(Screen.PvpLobby(LobbyPhase.Hosting()))))
        assertTrue(tapOpeningOf(at(Screen.PvpLobby(LobbyPhase.Hosting(fromTap = true)))))
        assertFalse(tapOpeningOf(at(Screen.PvpLobby(LobbyPhase.Menu()))))
        assertFalse(tapOpeningOf(at(Screen.Home)))
    }

    @Test
    fun aTapAskingForAnotherTakesTurnsAgain() {
        // Its friend may be asking for another tap too: two phones that only read could never meet.
        val again = at(Screen.PvpLobby(LobbyPhase.Tapped(again = true)))
        assertEquals(TapMode.TAKE_TURNS, tapModeOf(again))
        assertTrue(tapJoinOpen(again))
        assertTrue(tapReadyOf(again))
    }

    @Test
    fun everywhereElseIsOff() {
        for (screen in listOf(Screen.Loading, Screen.Switcher(SwitcherMode.ONBOARD))) {
            assertEquals(TapMode.OFF, tapModeOf(at(screen)))
            assertFalse(tapReadyOf(at(screen)))
        }
    }
}

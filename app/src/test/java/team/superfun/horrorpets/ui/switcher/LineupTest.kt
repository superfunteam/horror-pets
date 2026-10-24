package team.superfun.horrorpets.ui.switcher

import org.junit.Assert.assertEquals
import org.junit.Test
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.ui.SwitcherMode

/** Who stands in the switcher's row: never the secret killer before the reveal, and never as a starter. */
class LineupTest {
    private val seven = PetId.entries.filterNot { it.secret }
    private val everyone = GameState(pets = seven.associateWith { PetState(it) }, selected = PetId.FREDDY)

    @Test
    fun theStarterPickIsTheSevenEvenOnceHeIsRevealed() {
        assertEquals(seven, lineup(SwitcherMode.ONBOARD, GameState()))
        assertEquals(seven, lineup(SwitcherMode.ONBOARD, GameState(revealed = setOf("JAWS"))))
    }

    @Test
    fun theOpponentRowIsTheVisibleKillersBarTheOneFighting() {
        // Before the reveal: the other six, and no seventh card for him.
        assertEquals(seven - PetId.FREDDY, lineup(SwitcherMode.OPPONENT, everyone))
        // Revealed: he is a card at the end (locked until beaten).
        assertEquals(PetId.entries - PetId.FREDDY, lineup(SwitcherMode.OPPONENT, everyone.copy(revealed = setOf("JAWS"))))
        // Fighting as him: he is not his own opponent.
        val asJaws = everyone.copy(pets = everyone.pets + (PetId.JAWS to PetState(PetId.JAWS)), selected = PetId.JAWS)
        assertEquals(seven, lineup(SwitcherMode.OPPONENT, asJaws))
    }
}

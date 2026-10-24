package team.superfun.horrorpets.game.pantry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState

class CanEatTest {
    @Test
    fun aKillerEatsUnlessItIsFull() {
        assertTrue(canEat(PetState(PetId.JASON, hunger = Tuning.FEED_REFUSE_BELOW)))
        assertTrue(canEat(PetState(PetId.JASON, hunger = 90.0)))
        assertFalse(canEat(PetState(PetId.JASON, hunger = Tuning.FEED_REFUSE_BELOW - 0.5)))
    }
}

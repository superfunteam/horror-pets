package team.superfun.horrorpets.ui.pantry

import org.junit.Assert.assertEquals
import org.junit.Test
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.training.TrainingGold

/** The pantry's hunger line. */
class HungerLineTest {
    @Test
    fun saysHowHungryAndWhatThatMeans() {
        assertEquals("HUNGER 90 · STARVING", hungerLine(90.0))
        assertEquals("HUNGER 85 · STARVING", hungerLine(Tuning.STARVING_AT))
        assertEquals("HUNGER 62 · HUNGRY", hungerLine(62.4))
        assertEquals("HUNGER 60 · HUNGRY", hungerLine(Tuning.HUNGRY_AT))
        assertEquals("HUNGER 40", hungerLine(40.0))
        assertEquals("HUNGER 10", hungerLine(Tuning.FEED_REFUSE_BELOW))
        assertEquals("HUNGER 6 · FULL", hungerLine(6.0))
    }

    @Test
    fun colouredLikeTheStatCardsGauge() {
        assertEquals(BloodGlow, hungerColor(90.0))
        assertEquals(TrainingGold, hungerColor(40.0))
        assertEquals(TrainingGold, hungerColor(3.0))
        assert(hungerColor(70.0) != TrainingGold && hungerColor(70.0) != BloodGlow)
    }
}

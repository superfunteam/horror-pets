package team.superfun.horrorpets.ui.pantry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.game.pantry.Reaction
import team.superfun.horrorpets.model.PantryItem.STEAK
import team.superfun.horrorpets.ui.FedShow

/** The feed show: the toss, the munch, the reaction, and when it carries on Home. */
class ReactionTest {
    private val munch = 1_800L

    @Test
    fun thePhasesComeInOrder() {
        val t = feedTimeline(munch)
        assertEquals(FOOD_FALL_MS.toLong() + LAND_SETTLE_MS, t.landMs)
        assertEquals("the reaction once both munches are done", t.landMs + munch, t.reactMs)
        assertEquals("then a few seconds to read it", t.reactMs + REACTION_HOLD_MS, t.doneMs)
        assertTrue(REACTION_HOLD_MS in 3_000L..5_000L)

        val phases = (0L..t.doneMs + 100 step 10).map { t.phaseAt(it) }
        assertEquals(listOf(FeedPhase.TOSS, FeedPhase.MUNCH, FeedPhase.REACT, FeedPhase.DONE), phases.distinct())
        assertEquals(phases, phases.sortedBy { it.ordinal })
        assertEquals(FeedPhase.TOSS, t.phaseAt(t.landMs - 1))
        assertEquals(FeedPhase.MUNCH, t.phaseAt(t.landMs))
        assertEquals(FeedPhase.REACT, t.phaseAt(t.reactMs))
        assertEquals(FeedPhase.DONE, t.phaseAt(t.doneMs))
    }

    @Test
    fun okCarriesOnOnlyOnceTheReactionIsUp() {
        val t = feedTimeline(munch)
        assertFalse(t.carriesOn(0))
        assertFalse("not while it eats", t.carriesOn(t.reactMs - 1))
        assertTrue(t.carriesOn(t.reactMs))
        assertTrue(t.carriesOn(t.doneMs + 1_000))
    }

    @Test
    fun eachReactionHasItsPoseAndSound() {
        assertEquals(listOf(Anim.JUMP), reactionAnims(Reaction.LOVED))
        assertEquals(listOf(Anim.JUMP), reactionAnims(Reaction.CONTENT))
        assertEquals(listOf(Anim.FAIL), reactionAnims(Reaction.HATED))
        assertEquals(listOf(Anim.FAIL), reactionAnims(Reaction.YUCK))
        assertEquals(listOf(Anim.LOOK_A), reactionAnims(Reaction.SHRUG))
        val party = reactionAnims(Reaction.FAVORITE)
        assertTrue("a celebration: a few jumps", party.size >= 3 && party.all { it == Anim.JUMP })

        assertEquals("eat_happy", reactionSound(Reaction.LOVED))
        assertEquals("eat_happy", reactionSound(Reaction.CONTENT))
        assertEquals("eat_gross", reactionSound(Reaction.HATED))
        assertEquals("eat_gross", reactionSound(Reaction.YUCK))
        assertEquals("favorite_fanfare", reactionSound(Reaction.FAVORITE))
        assertNull("a shrug makes no sound beyond the munch", reactionSound(Reaction.SHRUG))

        assertTrue("hearts only for the favorite", Reaction.entries.filter { hasHearts(it) } == listOf(Reaction.FAVORITE))
    }

    @Test
    fun theGainsReadOutTheXpAndALevelUp() {
        assertEquals("+10 XP", gainsLine(FedShow(STEAK, Reaction.LOVED, "Mmm, savory!", 10, 0)))
        assertEquals("+0 XP", gainsLine(FedShow(STEAK, Reaction.HATED, "Ugh.", 0, 0)))
        assertNull(levelUpLine(FedShow(STEAK, Reaction.LOVED, "Mmm, savory!", 10, 0)))
        assertEquals("LEVEL UP!", levelUpLine(FedShow(STEAK, Reaction.LOVED, "Mmm, savory!", 10, 1)))
        assertEquals("LEVEL UP!", levelUpLine(FedShow(STEAK, Reaction.FAVORITE, "MY FAVORITE!!", 80, 2)))
        assertEquals("Too full!", TOO_FULL_LINE)
    }
}

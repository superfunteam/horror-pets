package team.superfun.horrorpets.ui.pantry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.assets.Anim
import team.superfun.horrorpets.game.pantry.MealPerk
import team.superfun.horrorpets.game.pantry.MealStatus
import team.superfun.horrorpets.game.pantry.Reaction
import team.superfun.horrorpets.model.PantryItem.STEAK
import team.superfun.horrorpets.ui.FedShow
import team.superfun.horrorpets.ui.common.OK_GUARD_MS

/** The feed show: the toss, the munch, the reaction, and when it carries on Home. */
class ReactionTest {
    private val munch = 1_800L

    @Test
    fun thePhasesComeInOrder() {
        val t = feedTimeline(munch, carryOnAfterMs = OK_GUARD_MS)
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
    fun okCarriesOnOnlyOnceTheReactionHasBeenUpAMoment() {
        // A plain meal: OK (or a tap) counts from the reaction plus the guard every screen has, so presses mashed
        // through the munch can't skip it the frame it shows.
        val plain = reactionCue(fed(levels = 0), replay = false)
        assertEquals(OK_GUARD_MS, plain.carryOnAfterMs)
        val t = feedTimeline(munch, plain.carryOnAfterMs)
        assertFalse(t.carriesOn(0))
        assertFalse("not while it eats", t.carriesOn(t.reactMs - 1))
        assertFalse("not the moment it shows", t.carriesOn(t.reactMs))
        assertFalse(t.carriesOn(t.reactMs + OK_GUARD_MS - 1))
        assertTrue(t.carriesOn(t.reactMs + OK_GUARD_MS))
        assertTrue(t.carriesOn(t.doneMs + 1_000))

        // A level-up: not until LEVEL UP! has shown (and been up a moment).
        val up = reactionCue(fed(levels = 1), replay = false)
        assertEquals(LEVEL_UP_AFTER_MS, up.levelUpAtMs)
        assertTrue(up.carryOnAfterMs > LEVEL_UP_AFTER_MS)
        assertTrue(up.carryOnAfterMs >= OK_GUARD_MS)
        val u = feedTimeline(munch, up.carryOnAfterMs)
        assertFalse(u.carriesOn(u.reactMs + LEVEL_UP_AFTER_MS))
        assertTrue(u.carriesOn(u.reactMs + up.carryOnAfterMs))
        assertTrue("and before it carries on by itself", u.reactMs + up.carryOnAfterMs < u.doneMs)
    }

    @Test
    fun theMealsStatusComesAfterTheGainsAndAfterALevelUp() {
        // A meal that left no status shows no banner.
        val none = reactionCue(fed(levels = 0), replay = false)
        assertNull(none.statusAtMs)
        assertNull(none.statusSound)

        // A beat after the gains, with its chime; OK waits until it has been up a moment, as for LEVEL UP!.
        val beefed = reactionCue(fed(levels = 0, status = MealStatus(MealPerk.BEEFED_UP)), replay = false)
        assertEquals(400L, STATUS_AFTER_MS)
        assertEquals(STATUS_AFTER_MS, beefed.statusAtMs)
        assertEquals("perk_up", beefed.statusSound)
        assertEquals(STATUS_AFTER_MS + LEVEL_UP_READ_MS, beefed.carryOnAfterMs)

        // With a level-up, LEVEL UP! first and the status a beat after it, still before the show carries on by itself.
        val up = reactionCue(fed(levels = 1, status = MealStatus(MealPerk.HANGOVER)), replay = false)
        assertEquals(LEVEL_UP_AFTER_MS, up.levelUpAtMs)
        assertEquals(LEVEL_UP_AFTER_MS + STATUS_AFTER_MS, up.statusAtMs)
        assertEquals("a hangover sounds sour", "hangover", up.statusSound)
        assertEquals(LEVEL_UP_AFTER_MS + STATUS_AFTER_MS + LEVEL_UP_READ_MS, up.carryOnAfterMs)
        val u = feedTimeline(munch, up.carryOnAfterMs)
        assertTrue(u.reactMs + up.carryOnAfterMs < u.doneMs)

        // Coming back to a meal already eaten: up at once, and silent.
        val back = reactionCue(fed(levels = 1, status = MealStatus(MealPerk.BEEFED_UP, feast = true)), replay = true)
        assertEquals(0L, back.statusAtMs)
        assertNull(back.statusSound)
        assertEquals(OK_GUARD_MS, back.carryOnAfterMs)
    }

    @Test
    fun theBubbleSaysTheStatusLineAfterTheTasteLine() {
        val taste = "Mmm, savory!"
        // The taste line as the reaction comes, then, once the banner has landed, what the status does for the killer,
        // kept until the reaction ends.
        assertEquals(1_400L, STATUS_LINE_AFTER_MS)
        val beefed = reactionCue(fed(levels = 0, status = MealStatus(MealPerk.BEEFED_UP)), replay = false)
        assertEquals("Now I'm strong!", beefed.statusLine)
        assertEquals(STATUS_LINE_AFTER_MS, beefed.statusLineAtMs)
        assertEquals(taste, beefed.lineAt(0, taste))
        assertEquals(taste, beefed.lineAt(STATUS_LINE_AFTER_MS - 1, taste))
        assertEquals("Now I'm strong!", beefed.lineAt(STATUS_LINE_AFTER_MS, taste))
        assertEquals("Now I'm strong!", beefed.lineAt(REACTION_HOLD_MS, taste))
        assertTrue("after the banner", STATUS_LINE_AFTER_MS > beefed.statusAtMs!!)
        assertTrue("well before the reaction ends", STATUS_LINE_AFTER_MS + 1_000 < REACTION_HOLD_MS)
        // With a level-up too: still after the banner, which comes after LEVEL UP!.
        val up = reactionCue(fed(levels = 1, status = MealStatus(MealPerk.HANGOVER)), replay = false)
        assertEquals(STATUS_LINE_AFTER_MS, up.statusLineAtMs)
        assertTrue(STATUS_LINE_AFTER_MS > up.statusAtMs!!)
        assertEquals("Ugh... my head.", up.lineAt(STATUS_LINE_AFTER_MS, taste))
        // Each status says its own; the favorite says its own whatever its flavor.
        val lines = mapOf(
            MealStatus(MealPerk.BEEFED_UP) to "Now I'm strong!",
            MealStatus(MealPerk.SUGAR_RUSH) to "I could run through a wall!",
            MealStatus(MealPerk.IRON_STOMACH) to "Nothing can hurt me.",
            MealStatus(MealPerk.HANGOVER) to "Ugh... my head.",
            MealStatus(MealPerk.BEEFED_UP, feast = true) to "My favorite. I'm unstoppable.",
            MealStatus(MealPerk.SUGAR_RUSH, feast = true) to "My favorite. I'm unstoppable.",
            MealStatus(MealPerk.IRON_STOMACH, feast = true) to "My favorite. I'm unstoppable.",
        )
        for ((status, line) in lines) {
            val cue = reactionCue(fed(levels = 0, status = status), replay = false)
            assertEquals("$status", taste, cue.lineAt(STATUS_LINE_AFTER_MS - 1, taste))
            assertEquals("$status", line, cue.lineAt(STATUS_LINE_AFTER_MS, taste))
        }

        // A meal that left no status never swaps: the taste line from start to end, fresh or coming back.
        for (replay in listOf(false, true)) {
            for (levels in 0..1) {
                val plain = reactionCue(fed(levels = levels), replay = replay)
                assertNull(plain.statusLine)
                assertNull(plain.statusLineAtMs)
                for (ms in 0L..REACTION_HOLD_MS + 1_000 step 50) assertEquals("$ms", taste, plain.lineAt(ms, taste))
            }
        }

        // Coming back to a meal already eaten: finished, so the status's line at once.
        val back = reactionCue(fed(levels = 0, status = MealStatus(MealPerk.SUGAR_RUSH, feast = true)), replay = true)
        assertEquals(0L, back.statusLineAtMs)
        assertEquals("My favorite. I'm unstoppable.", back.lineAt(0, taste))
    }

    @Test
    fun aReactionComingBackIsShownFinishedAndSilent() {
        // The kitchen came back (the Activity was made again) on a meal already eaten: the line, the gains and the
        // LEVEL UP are up at once, with no sound, no hearts and no pose; OK still carries on, after the guard.
        for (reaction in Reaction.entries) {
            for (levels in 0..1) {
                val fresh = reactionCue(fed(reaction, levels), replay = false)
                assertEquals("$reaction", reactionSound(reaction), fresh.sound)
                assertEquals(reactionAnims(reaction), fresh.anims)
                assertEquals(hasHearts(reaction), fresh.hearts)
                assertEquals(levels > 0, fresh.levelUpSound)

                val back = reactionCue(fed(reaction, levels), replay = true)
                assertNull("$reaction plays no sound again", back.sound)
                assertEquals(emptyList<Anim>(), back.anims)
                assertFalse("no hearts again", back.hearts)
                assertFalse("no level-up sound again", back.levelUpSound)
                assertEquals("LEVEL UP! up at once", if (levels > 0) 0L else null, back.levelUpAtMs)
                assertEquals(OK_GUARD_MS, back.carryOnAfterMs)
            }
        }
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
    fun theGainsReadOutWhatTheMealGaveAndNeverAZero() {
        fun lines(fed: FedShow) = gainsOf(fed).map { it.text }
        // A loved snack: its XP, its stat and the mood it lifts.
        assertEquals(listOf("+10 XP", "+6 STRENGTH", "+10 MOOD"), lines(FedShow(STEAK, Reaction.LOVED, "", 10, 0, strength = 6.0, mood = 10.0)))
        // Sweet goes to fun.
        assertEquals(listOf("+5 XP", "+3 FUN"), lines(FedShow(STEAK, Reaction.CONTENT, "", 5, 0, funLevel = 3.0)))
        // A botch: no XP, so no "+0 XP", only the mood it cost, as a loss.
        val slop = gainsOf(FedShow(STEAK, Reaction.YUCK, "Ugh, slop.", 0, 0, mood = -15.0))
        assertEquals(listOf("-15 MOOD"), slop.map { it.text })
        assertTrue(slop.single().loss)
        assertFalse(gainsOf(FedShow(STEAK, Reaction.LOVED, "", 10, 0)).single().loss)
        // Clamped to almost nothing rounds away; a clamped part still shows; nothing at all shows nothing.
        assertEquals(listOf("+1 STRENGTH"), lines(FedShow(STEAK, Reaction.SHRUG, "", 0, 0, strength = 0.6, mood = 0.3)))
        assertEquals(emptyList<String>(), lines(FedShow(STEAK, Reaction.HATED, "", 0, 0, strength = 0.2, mood = -0.4)))
        assertEquals(listOf("+40 XP", "+10 STRENGTH", "+25 MOOD"), lines(FedShow(STEAK, Reaction.FAVORITE, "", 40, 1, strength = 10.0, mood = 25.0)))
        // No line of it is ever a zero.
        for (fed in listOf(FedShow(STEAK, Reaction.HATED, "", 0, 0), FedShow(STEAK, Reaction.YUCK, "", 0, 0, mood = -0.49))) {
            assertTrue(gainsOf(fed).none { it.text.startsWith("+0") || it.text.startsWith("-0") })
        }

        assertNull(levelUpLine(FedShow(STEAK, Reaction.LOVED, "Mmm, savory!", 10, 0)))
        assertEquals("LEVEL UP!", levelUpLine(FedShow(STEAK, Reaction.LOVED, "Mmm, savory!", 10, 1)))
        assertEquals("LEVEL UP!", levelUpLine(FedShow(STEAK, Reaction.FAVORITE, "MY FAVORITE!!", 80, 2)))
        assertEquals("Too full!", TOO_FULL_LINE)
    }

    private fun fed(reaction: Reaction = Reaction.LOVED, levels: Int, status: MealStatus? = null) =
        FedShow(STEAK, reaction, "Mmm, savory!", 10, levels, status = status)
}

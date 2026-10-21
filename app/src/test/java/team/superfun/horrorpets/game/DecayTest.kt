package team.superfun.horrorpets.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState

class DecayTest {
    private val hour = 3_600_000L
    private val day = 24 * hour

    /** A valid 2025 timestamp, comfortably above Tuning.MIN_VALID_EPOCH_MS. */
    private val t0 = 1_760_000_000_000L

    /** A stable with every killer in it, so decay can be checked on all of them. */
    private val everyone = PetId.entries.associateWith { PetState(it) }

    private fun seen(at: Long = t0, selected: PetId? = null) = GameState(pets = everyone, lastSeenMs = at, selected = selected)

    private fun assertPetEquals(expected: PetState, actual: PetState, delta: Double = 1e-9) {
        assertEquals(expected.id, actual.id)
        assertEquals("hunger", expected.hunger, actual.hunger, delta)
        assertEquals("funLevel", expected.funLevel, actual.funLevel, delta)
        assertEquals("strength", expected.strength, actual.strength, delta)
        assertEquals("moodBonus", expected.moodBonus, actual.moodBonus, delta)
        assertEquals("level", expected.level, actual.level)
        assertEquals("xp", expected.xp, actual.xp)
        assertEquals("wins", expected.wins, actual.wins)
        assertEquals("losses", expected.losses, actual.losses)
        assertEquals("sceneIndex", expected.sceneIndex, actual.sceneIndex)
        assertEquals("fakeDeathPending", expected.fakeDeathPending, actual.fakeDeathPending)
    }

    @Test
    fun firstRunSetsLastSeenWithoutDecay() {
        val state = GameState(pets = everyone, lastSeenMs = null)
        val (next, summary) = applyElapsed(state, t0)
        assertEquals(state.pets, next.pets)
        assertEquals(t0, next.lastSeenMs)
        assertEquals(0L, summary.elapsedMs)
        assertNull(summary.biggestStat)
        assertEquals(0, summary.biggestDelta)
        assertTrue(summary.fakeDeath.isEmpty())
    }

    @Test
    fun tenHoursDecays() {
        val (next, summary) = applyElapsed(seen(), t0 + 10 * hour)
        for (id in PetId.entries) {
            val p = next.pets.getValue(id)
            assertEquals(60.0, p.hunger, 0.001)
            assertEquals(50.0, p.funLevel, 0.001)
            assertEquals(20.0 - 10.0 / 6, p.strength, 0.001)
            assertFalse(p.fakeDeathPending)
        }
        assertEquals(t0 + 10 * hour, next.lastSeenMs)
        assertEquals(10 * hour, summary.elapsedMs)
    }

    @Test
    fun splitIntervalsEqualSingle() {
        val start = seen().let { s ->
            s.copy(
                pets = s.pets +
                    (PetId.JASON to s.pets.getValue(PetId.JASON).copy(moodBonus = 30.0)) +
                    (PetId.FREDDY to s.pets.getValue(PetId.FREDDY).copy(moodBonus = -22.0)),
            )
        }
        val single = applyElapsed(start, t0 + 10 * hour).first
        val half = applyElapsed(start, t0 + 5 * hour).first
        val split = applyElapsed(half, t0 + 10 * hour).first

        assertEquals(single.lastSeenMs, split.lastSeenMs)
        for (id in PetId.entries) assertPetEquals(single.pets.getValue(id), split.pets.getValue(id))
    }

    @Test
    fun moodBonusDecaysTowardZeroAndComposes() {
        val start = seen().let { s ->
            s.copy(
                pets = s.pets +
                    (PetId.JASON to s.pets.getValue(PetId.JASON).copy(moodBonus = 30.0)) +
                    (PetId.FREDDY to s.pets.getValue(PetId.FREDDY).copy(moodBonus = -30.0)),
            )
        }
        val twoH = applyElapsed(start, t0 + 2 * hour).first
        assertEquals(20.0, twoH.pets.getValue(PetId.JASON).moodBonus, 1e-9)
        assertEquals(-20.0, twoH.pets.getValue(PetId.FREDDY).moodBonus, 1e-9)

        val fourH = applyElapsed(twoH, t0 + 4 * hour).first
        val fourHDirect = applyElapsed(start, t0 + 4 * hour).first
        assertEquals(10.0, fourH.pets.getValue(PetId.JASON).moodBonus, 1e-9)
        for (id in PetId.entries) assertPetEquals(fourHDirect.pets.getValue(id), fourH.pets.getValue(id))

        // Never overshoots past zero.
        val sixH = applyElapsed(fourH, t0 + 12 * hour).first
        assertEquals(0.0, sixH.pets.getValue(PetId.JASON).moodBonus, 0.0)
        assertEquals(0.0, sixH.pets.getValue(PetId.FREDDY).moodBonus, 0.0)
    }

    @Test
    fun clampsAtBounds() {
        val (next, _) = applyElapsed(seen(), t0 + 48 * hour)
        for (id in PetId.entries) {
            val p = next.pets.getValue(id)
            assertEquals(100.0, p.hunger, 0.0)
            assertEquals(0.0, p.funLevel, 0.0)
            assertTrue(p.strength >= 0.0)
        }
    }

    @Test
    fun backwardsClockIsZeroAndKeepsLastSeen() {
        val state = seen(at = t0 + 10 * hour)
        val (next, summary) = applyElapsed(state, t0)
        assertEquals(state.pets, next.pets)
        assertEquals(t0 + 10 * hour, next.lastSeenMs)
        assertEquals(0L, summary.elapsedMs)
        assertTrue(summary.fakeDeath.isEmpty())
    }

    @Test
    fun clockBeforeFloorIsIgnored() {
        val fresh = GameState(pets = everyone, lastSeenMs = null)
        val (a, aSummary) = applyElapsed(fresh, 5_000)
        assertEquals(fresh.pets, a.pets)
        assertNull(a.lastSeenMs)
        assertEquals(0L, aSummary.elapsedMs)

        val state = seen()
        val (b, bSummary) = applyElapsed(state, 5_000)
        assertEquals(state.pets, b.pets)
        assertEquals(t0, b.lastSeenMs)
        assertEquals(0L, bSummary.elapsedMs)
        assertTrue(bSummary.fakeDeath.isEmpty())
    }

    @Test
    fun persistedLastSeenBeforeFloorTreatedAsFirstRun() {
        // A corrupt/old save holding a 1970-ish timestamp must not become a 30-day jump (and a fake
        // death for every pet) on the next valid launch: it counts as "never seen".
        val state = seen(at = 5_000)
        val (next, summary) = applyElapsed(state, t0)
        assertEquals(state.pets, next.pets)
        assertEquals(t0, next.lastSeenMs)
        assertEquals(0L, summary.elapsedMs)
        assertTrue(summary.fakeDeath.isEmpty())
    }

    @Test
    fun clockFloorIsJanuaryFirst2024() {
        assertEquals(1_704_067_200_000L, Tuning.MIN_VALID_EPOCH_MS)
        // Exactly at the floor is valid (first run records it); one ms below is not.
        assertEquals(Tuning.MIN_VALID_EPOCH_MS, applyElapsed(GameState(), Tuning.MIN_VALID_EPOCH_MS).first.lastSeenMs)
        assertNull(applyElapsed(GameState(), Tuning.MIN_VALID_EPOCH_MS - 1).first.lastSeenMs)
    }

    @Test
    fun nowBelowACustomFloorIsIgnored() {
        // A stick with no clock battery boots to its firmware build date: after 2024, but before the
        // app was installed. The floor is the install time, so that date is recognised as stale.
        val floor = t0 + 30 * day
        val state = seen(at = t0)
        val (next, summary) = applyElapsed(state, t0 + 10 * day, floorMs = floor)
        assertEquals("no decay and no lastSeen write", state, next)
        assertEquals(0L, summary.elapsedMs)
        assertTrue(summary.fakeDeath.isEmpty())

        val fresh = GameState(pets = everyone, lastSeenMs = null)
        assertNull(applyElapsed(fresh, floor - 1, floorMs = floor).first.lastSeenMs)
        assertEquals(floor, applyElapsed(fresh, floor, floorMs = floor).first.lastSeenMs)
    }

    @Test
    fun lastSeenBelowACustomFloorIsAFirstRun() {
        // Saved under a stale boot clock (between 2024 and the floor): not a real absence.
        val floor = t0 + 30 * day
        val state = seen(at = t0)
        val now = floor + 2 * hour
        val (next, summary) = applyElapsed(state, now, floorMs = floor)
        assertEquals(state.pets, next.pets)
        assertEquals(now, next.lastSeenMs)
        assertEquals(0L, summary.elapsedMs)
        assertTrue(summary.fakeDeath.isEmpty())

        // The same save with the default floor is a 32-day absence (capped at 30) and a fake death.
        assertTrue(applyElapsed(state, now).second.fakeDeath.isNotEmpty())
    }

    @Test
    fun hugeJumpIsCapped() {
        val (next, summary) = applyElapsed(seen(), t0 + 400 * day)
        assertEquals(30 * day, summary.elapsedMs)
        assertEquals(PetId.entries.toSet(), summary.fakeDeath)
        for (id in PetId.entries) assertTrue(next.pets.getValue(id).fakeDeathPending)
        // lastSeen still advances to the real now, so the cap is not re-applied on the next launch.
        assertEquals(t0 + 400 * day, next.lastSeenMs)
    }

    @Test
    fun sevenDaysTriggersFakeDeath() {
        val strong = seen().let { s ->
            s.copy(pets = s.pets.mapValues { (_, p) -> p.copy(strength = 80.0, level = 4, xp = 37) })
        }
        val (next, summary) = applyElapsed(strong, t0 + 7 * day)
        assertEquals(PetId.entries.toSet(), summary.fakeDeath)
        // Strength decays for 168 h first (80 - 28 = 52), then the fake death takes 20% off.
        val expectedStrength = (80.0 - 168.0 / 6) * 0.8
        for (id in PetId.entries) {
            val p = next.pets.getValue(id)
            assertTrue(p.fakeDeathPending)
            assertEquals(90.0, p.hunger, 0.0)
            assertEquals(10.0, p.funLevel, 0.0)
            assertEquals(expectedStrength, p.strength, 1e-9)
            assertEquals(4, p.level)
            assertEquals(37, p.xp)
        }
    }

    @Test
    fun secondFakeDeathBeforeViewingDoesNotCompoundStrength() {
        val strong = seen().let { s -> s.copy(pets = s.pets.mapValues { (_, p) -> p.copy(strength = 80.0) }) }

        val (first, _) = applyElapsed(strong, t0 + 7 * day)
        val s1 = (80.0 - 168.0 / 6) * 0.8
        for (id in PetId.entries) {
            assertTrue(first.pets.getValue(id).fakeDeathPending)
            assertEquals(s1, first.pets.getValue(id).strength, 1e-9)
        }

        // The fake death has not been viewed yet (pending is still true) when another 7 days pass.
        val (second, summary) = applyElapsed(first, t0 + 14 * day)
        assertEquals(PetId.entries.toSet(), summary.fakeDeath)
        for (id in PetId.entries) {
            val p = second.pets.getValue(id)
            assertTrue(p.fakeDeathPending)
            // Decay and the hunger/fun reset still apply...
            assertEquals(90.0, p.hunger, 0.0)
            assertEquals(10.0, p.funLevel, 0.0)
            // ...but strength only loses the 7 days of decay, with no second x0.8.
            assertEquals(s1 - 168.0 / 6, p.strength, 1e-9)
        }
    }

    @Test
    fun fakeDeathStillPenalizesPetsThatAreNotPending() {
        val base = seen()
        val mixed = base.copy(
            pets = base.pets.mapValues { (id, p) ->
                p.copy(strength = 80.0, fakeDeathPending = id == PetId.JASON)
            },
        )
        val (next, _) = applyElapsed(mixed, t0 + 7 * day)
        val decayed = 80.0 - 168.0 / 6
        assertEquals(decayed, next.pets.getValue(PetId.JASON).strength, 1e-9)
        assertEquals(decayed * 0.8, next.pets.getValue(PetId.FREDDY).strength, 1e-9)
        assertTrue(next.pets.getValue(PetId.FREDDY).fakeDeathPending)
    }

    /** [state] with [ids] marked as joining fresh (a save that had no entry for them). */
    private fun joining(state: GameState, vararg ids: PetId) =
        state.copy(pets = state.pets.mapValues { (id, p) -> if (id in ids) p.copy(freshJoin = true) else p })

    @Test
    fun freshPetsSitOutTheTimeAwayAndAreThenTreatedLikeEveryoneElse() {
        // A save from before Chucky and Michael existed: they were added at their defaults on load.
        val loaded = joining(seen(), PetId.CHUCKY, PetId.MICHAEL)
        val (next, summary) = applyElapsed(loaded, t0 + 8 * day)
        assertEquals(PetState(PetId.CHUCKY), next.pets.getValue(PetId.CHUCKY))
        assertEquals(PetState(PetId.MICHAEL), next.pets.getValue(PetId.MICHAEL))
        assertEquals(PetId.entries.toSet() - PetId.CHUCKY - PetId.MICHAEL, summary.fakeDeath)
        assertTrue(next.pets.getValue(PetId.JASON).fakeDeathPending)
        assertTrue(next.pets.values.none { it.freshJoin })

        // From then on they decay with the rest.
        val (later, _) = applyElapsed(next, t0 + 8 * day + 10 * hour)
        assertEquals(20.0 + 40.0, later.pets.getValue(PetId.CHUCKY).hunger, 1e-9)
    }

    @Test
    fun theFreshMarkIsClearedEvenWhenNoTimeHasPassed() {
        val (next, _) = applyElapsed(joining(seen(), PetId.MICHAEL), t0)
        assertTrue(next.pets.values.none { it.freshJoin })
        val (firstRun, _) = applyElapsed(joining(GameState(pets = everyone), PetId.MICHAEL), t0)
        assertTrue(firstRun.pets.values.none { it.freshJoin })
    }

    @Test
    fun freshPetsWaitOutAStaleBootClock() {
        // The clock has not synced yet: nothing is applied, so the mark stays (and is saved) for the real update.
        val marked = joining(seen(), PetId.CHUCKY)
        val (next, _) = applyElapsed(marked, Tuning.MIN_VALID_EPOCH_MS - 1)
        assertEquals(marked, next)
        assertTrue(next.pets.getValue(PetId.CHUCKY).freshJoin)
    }

    @Test
    fun justUnderSevenDaysNoFakeDeath() {
        val (next, summary) = applyElapsed(seen(), t0 + 7 * day - 1)
        assertTrue(summary.fakeDeath.isEmpty())
        for (id in PetId.entries) assertFalse(next.pets.getValue(id).fakeDeathPending)
    }

    @Test
    fun sixDaysNoFakeDeath() {
        val (next, summary) = applyElapsed(seen(), t0 + 6 * day)
        assertTrue(summary.fakeDeath.isEmpty())
        for (id in PetId.entries) {
            val p = next.pets.getValue(id)
            assertFalse(p.fakeDeathPending)
            assertEquals(100.0, p.hunger, 0.0)
            assertEquals(0.0, p.funLevel, 0.0)
        }
    }

    @Test
    fun summaryBiggestChange() {
        val (_, summary) = applyElapsed(seen(), t0 + 2 * hour)
        assertEquals(2 * hour, summary.elapsedMs)
        assertEquals("Hunger", summary.biggestStat)
        assertEquals(8, summary.biggestDelta)
    }

    @Test
    fun summaryReportsNegativeFunWhenItChangesMost() {
        // Hunger is nearly pinned at 100 so only 1 point of room remains; fun falls by 6 over 2 h.
        val s = seen().let { st ->
            st.copy(pets = st.pets + (PetId.JASON to st.pets.getValue(PetId.JASON).copy(hunger = 99.0)))
        }
        val (_, summary) = applyElapsed(s, t0 + 2 * hour)
        assertEquals("Fun", summary.biggestStat)
        assertEquals(-6, summary.biggestDelta)
    }

    @Test
    fun summaryIsForSelectedPet() {
        val base = seen(selected = PetId.PINHEAD)
        val s = base.copy(pets = base.pets + (PetId.PINHEAD to base.pets.getValue(PetId.PINHEAD).copy(hunger = 99.0)))
        val (_, summary) = applyElapsed(s, t0 + 2 * hour)
        // PINHEAD's hunger only moves +1 (clamped), so fun (-6) is the biggest change.
        assertEquals("Fun", summary.biggestStat)
        assertEquals(-6, summary.biggestDelta)
    }

    @Test
    fun summaryFallsBackToJasonWhenNothingSelected() {
        val base = seen(selected = null)
        val s = base.copy(pets = base.pets + (PetId.JASON to base.pets.getValue(PetId.JASON).copy(hunger = 99.0)))
        val (_, summary) = applyElapsed(s, t0 + 2 * hour)
        assertEquals("Fun", summary.biggestStat)
        assertEquals(-6, summary.biggestDelta)
    }

    @Test
    fun tinyElapsedHasNoNoticeableChange() {
        val (_, summary) = applyElapsed(seen(), t0 + 1_000)
        assertNull(summary.biggestStat)
        assertEquals(0, summary.biggestDelta)
    }

    @Test
    fun otherFieldsAreUntouchedByDecay() {
        val s = seen().let { st ->
            st.copy(
                pets = st.pets + (
                    PetId.GHOSTFACE to st.pets.getValue(PetId.GHOSTFACE)
                        .copy(level = 5, xp = 44, wins = 3, losses = 2, sceneIndex = 1)
                    ),
            )
        }
        val (next, _) = applyElapsed(s, t0 + 3 * hour)
        val p = next.pets.getValue(PetId.GHOSTFACE)
        assertEquals(5, p.level)
        assertEquals(44, p.xp)
        assertEquals(3, p.wins)
        assertEquals(2, p.losses)
        assertEquals(1, p.sceneIndex)
        assertEquals(s.selected, next.selected)
        assertEquals(s.version, next.version)
    }
}

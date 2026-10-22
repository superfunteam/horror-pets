package team.superfun.horrorpets.ui.fight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.fight.FightStats
import team.superfun.horrorpets.fight.Rank
import team.superfun.horrorpets.fight.RankScore
import team.superfun.horrorpets.fight.fighter
import team.superfun.horrorpets.fight.stateOf

/** What the results screen's battle report and rank window say. */
class ResultsCardTest {
    private val fight = stateOf(player = fighter(), cpu = fighter(), turn = 7).copy(
        stats = FightStats(damageDealt = 124, damageTaken = 38, hits = 7, misses = 1, stopped = 2, crits = 2, specials = 1, healed = 50, bestCombo = 3),
    )

    @Test
    fun theReportCountsUpToHowTheFightWent() {
        val lines = reportLines(fight)
        assertEquals(
            listOf("Damage dealt", "Damage taken", "Hits landed", "Critical hits", "Best combo", "Specials", "HP healed", "Turns"),
            lines.map { it.label },
        )
        assertEquals(listOf(124, 38, 7, 2, 3, 1, 50, 6), lines.map { it.value })
        assertEquals(listOf("124", "38", "7/10", "2", "×3", "1", "50", "6"), lines.map { it.format(it.value) })
        assertEquals("hits count up against every attack: landed, missed or stopped", "3/10", lines[2].format(3))
    }

    @Test
    fun aComboOfOneIsNoComboAndOnlyTheNotableLinesAreHot() {
        val quiet = reportLines(stateOf().copy(stats = FightStats(bestCombo = 1)))
        assertEquals("—", quiet[4].format(1))
        assertTrue(quiet.none { it.hot })
        assertEquals(listOf("Critical hits", "Best combo", "Specials"), reportLines(fight).filter { it.hot }.map { it.label })
    }

    @Test
    fun theScoreIsListedPartByPart() {
        val lines = scoreLines(RankScore(hpPercent = 72, hpPoints = 36, comboPoints = 20, speedPoints = 16, potionPoints = -10))
        assertEquals(listOf("HP left 72%", "Best combo", "Speed", "Potions"), lines.map { it.label })
        assertEquals(listOf(36, 20, 16, -10), lines.map { it.points })
    }

    @Test
    fun theAnnouncerOnlyCallsTheGreatRanks() {
        assertEquals("unbelievable", rankCall(Rank.S))
        assertEquals("brutal", rankCall(Rank.A))
        assertNull(rankCall(Rank.B))
        assertNull(rankCall(Rank.C))
        assertFalse(Rank.entries.mapNotNull(::rankCall).isEmpty())
    }
}

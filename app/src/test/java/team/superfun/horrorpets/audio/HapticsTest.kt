package team.superfun.horrorpets.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which sounds a phone also buzzes for, and how ([hapticFor]). */
class HapticsTest {
    @Test fun aHitIsATapAndAHeavyOneIsFirmer() {
        assertArrayEquals(longArrayOf(15), hapticFor("hit"))
        assertArrayEquals(longArrayOf(35), hapticFor("heavy_hit"))
    }

    @Test fun aCritIsSharperStill() {
        assertArrayEquals(longArrayOf(60), hapticFor("crit"))
    }

    @Test fun aSpecialBuzzesAsACritDoes() {
        assertArrayEquals(longArrayOf(60), hapticFor("special_jason"))
        assertArrayEquals(longArrayOf(60), hapticFor("special_freddy"))
    }

    @Test fun theKnockoutIsTheLongestBuzzWhicheverSoundCarriesIt() {
        assertArrayEquals(longArrayOf(120), hapticFor("ko"))
        assertArrayEquals(longArrayOf(120), hapticFor("ko_boom"))
    }

    @Test fun theReelAndThePantryHaveTheirOwn() {
        assertArrayEquals(longArrayOf(25), hapticFor("reel_stop"))
        assertArrayEquals(longArrayOf(40), hapticFor("pantry_squelch"))
        assertArrayEquals(longArrayOf(20), hapticFor("pantry_poof"))
    }

    @Test fun aLevelUpBuzzesPausesAndBuzzesAgain() {
        assertArrayEquals(longArrayOf(40, 60, 40), hapticFor("levelup"))
    }

    @Test fun theWeaponSoundsToComeBuzzAsTheirPlainKind() {
        assertArrayEquals(longArrayOf(15), hapticFor("hit_axe"))
        assertArrayEquals(longArrayOf(35), hapticFor("heavy_axe"))
    }

    @Test fun aSoundWithNoBuzzGivesNull() {
        assertNull(hapticFor("ui_move"))
        assertNull(hapticFor("miss"))
        assertNull(hapticFor("snore"))
        assertNull(hapticFor("challenger_hit"))
        assertNull(hapticFor(""))
    }
}

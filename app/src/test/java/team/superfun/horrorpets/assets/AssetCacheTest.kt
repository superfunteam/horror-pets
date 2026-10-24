package team.superfun.horrorpets.assets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.Starters

/** How [AssetLoader]'s caches are sized and keyed, on the real art: what has to fit at once does, and nothing decodes twice. */
class AssetCacheTest {
    @Test
    fun theRevealsArtFitsTheVsCacheAtOnce() {
        // The entrance draws the banner and his name; the VS card after it both names and his logo. All of it stays
        // cached together, so the card never decodes again what the entrance just did.
        assertTrue("$MAX_VS_ART", MAX_VS_ART >= 4)
        for (secret in PetId.entries.filter { it.secret }) {
            for (player in Starters) {
                val entrance = listOf(CHALLENGER_BANNER_FILE, vsNameFile(secret))
                val card = listOf(vsNameFile(player), vsNameFile(secret), vsLogoFile(secret))
                val shown = (entrance + card).toSet()
                assertTrue("$player vs $secret: ${shown.size} pictures", shown.size <= MAX_VS_ART)
            }
        }
    }

    @Test
    fun animationsSharingAStripShareItsDecode() {
        // Jaws's wait and work are his idle frames: one strip, one decode, one slot in the strip cache.
        val jaws = parseManifest(AssetFixtures.manifest("jaws"))
        val idle = stripKey(PetId.JAWS, jaws, Anim.IDLE)
        assertEquals(idle, stripKey(PetId.JAWS, jaws, Anim.WAIT))
        assertEquals(idle, stripKey(PetId.JAWS, jaws, Anim.WORK))
        assertNotEquals(idle, stripKey(PetId.JAWS, jaws, Anim.ATTACK))
        val files = jaws.animations.values.map { it.file }.toSet()
        val keys = jaws.animations.keys.mapNotNull(Anim::fromKey).map { stripKey(PetId.JAWS, jaws, it) }.toSet()
        assertEquals("one key per strip file", files.size, keys.size)

        // Two killers' strips of the same name are two strips.
        val jason = parseManifest(AssetFixtures.manifest("jason"))
        assertNotEquals(stripKey(PetId.JASON, jason, Anim.IDLE), idle)
        assertNotEquals(stripKey(PetId.JASON, jason, Anim.IDLE), stripKey(PetId.JASON, jason, Anim.WAIT))
    }
}

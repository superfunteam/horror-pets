package team.superfun.horrorpets.assets

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpriteManifestTest {
    private val pets = listOf("jason", "freddy", "pinhead", "ghostface", "chucky", "michael")

    @Test
    fun parsesAllTwelveAnims() {
        val m = parseManifest(AssetFixtures.manifest("jason"))
        assertEquals(12, m.animations.size)
        assertEquals("review", m.feed)
        assertEquals("jason", m.pet)
        assertEquals(listOf(384, 416), m.cell)
        assertEquals(404, m.baselineY)
        assertEquals(192, m.anchorX)
    }

    @Test
    fun animKeysMatchTheManifestKeysExactly() {
        assertEquals(12, Anim.entries.size)
        val m = parseManifest(AssetFixtures.manifest("jason"))
        assertEquals(Anim.entries.map { it.key }.toSet(), m.animations.keys)
    }

    @Test
    fun everyRealManifestResolvesEveryAnim() {
        for (pet in pets) {
            val m = parseManifest(AssetFixtures.manifest(pet))
            assertEquals(pet, m.pet)
            for (anim in Anim.entries) {
                val spec = m.spec(anim)
                assertEquals("$pet ${anim.key} file", "${anim.key}.png", spec.file)
                assertEquals("$pet ${anim.key} timeline", spec.frames, spec.durationsMs.size)
                assertTrue("$pet ${anim.key} durations", spec.durationsMs.all { it > 0 })
            }
        }
    }

    @Test
    fun loopFlagsComeFromTheManifest() {
        val m = parseManifest(AssetFixtures.manifest("jason"))
        assertTrue(m.spec(Anim.IDLE).loop)
        assertTrue(m.spec(Anim.RUN_RIGHT).loop)
        assertFalse(m.spec(Anim.ATTACK).loop)
        assertEquals(listOf(300, 140, 180, 75, 140, 360), m.spec(Anim.ATTACK).durationsMs)
    }

    @Test
    fun feedAnimMapsTheFeedStringToTheEnum() {
        assertEquals(Anim.REVIEW, parseManifest(AssetFixtures.manifest("jason")).feedAnim())
        assertEquals(Anim.REVIEW, parseManifest(AssetFixtures.manifest("pinhead")).feedAnim())
        assertEquals(Anim.WORK, parseManifest(AssetFixtures.manifest("freddy")).feedAnim())
        assertEquals(Anim.WORK, parseManifest(AssetFixtures.manifest("ghostface")).feedAnim())
        assertEquals(Anim.REVIEW, parseManifest(AssetFixtures.manifest("chucky")).feedAnim())
        assertEquals(Anim.REVIEW, parseManifest(AssetFixtures.manifest("michael")).feedAnim())
    }

    @Test
    fun displayScaleComesFromTheManifest() {
        assertEquals(0.5f, parseManifest(AssetFixtures.manifest("chucky")).displayScale, 0f)
        for (pet in pets - "chucky") {
            assertEquals(pet, 1f, parseManifest(AssetFixtures.manifest(pet)).displayScale, 0f)
        }
    }

    @Test
    fun aManifestWithoutDisplayScaleIsFullSize() {
        val m = parseManifest(
            """{"pet":"x","cell":[384,416],"baselineY":404,"anchorX":192,"feed":"work",
               "animations":{"idle":{"file":"idle.png","frames":1,"durationsMs":[50],"loop":true}}}""",
        )
        assertEquals(1f, m.displayScale, 0f)
        assertEquals(1f, m.bodyScale, 0f)
    }

    @Test
    fun bodyScaleIgnoresNonsenseScales() {
        val m = parseManifest(AssetFixtures.manifest("chucky"))
        assertEquals(0.5f, m.bodyScale, 0f)
        for (bad in listOf(0f, -0.5f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals("$bad", 1f, m.copy(displayScale = bad).bodyScale, 0f)
        }
        assertEquals(1.25f, m.copy(displayScale = 1.25f).bodyScale, 0f)
    }

    @Test
    fun unknownFeedOrMissingAnimIsALoudError() {
        val m = parseManifest(AssetFixtures.manifest("jason"))
        assertThrows(IllegalStateException::class.java) { m.copy(feed = "dance").feedAnim() }
        assertThrows(IllegalStateException::class.java) {
            m.copy(animations = m.animations - Anim.WAVE.key).spec(Anim.WAVE)
        }
    }

    @Test
    fun unknownKeysAreIgnored() {
        val m = parseManifest(
            """{"pet":"x","cell":[1,2],"baselineY":1,"anchorX":1,"feed":"work","future":true,
               "animations":{"idle":{"file":"idle.png","frames":1,"durationsMs":[50],"loop":true,"easing":"none"}}}""",
        )
        assertEquals(1, m.animations.size)
    }

    @Test
    fun malformedManifestThrows() {
        assertThrows(SerializationException::class.java) { parseManifest("""{"pet":"jason"}""") }
    }

    @Test
    fun animFromKey() {
        assertEquals(Anim.LOOK_A, Anim.fromKey("look-a"))
        assertEquals(null, Anim.fromKey("nope"))
    }
}

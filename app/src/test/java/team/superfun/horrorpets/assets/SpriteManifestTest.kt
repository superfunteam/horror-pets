package team.superfun.horrorpets.assets

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpriteManifestTest {
    private val pets = listOf("jason", "freddy", "pinhead", "ghostface", "chucky", "michael", "leatherface")

    /** The pets that shipped before the optional animations: the twelve every pet has, and nothing else. */
    private val plainPets = pets - "leatherface"

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
    fun theRequiredAnimKeysMatchTheManifestKeysExactly() {
        assertEquals(12, Anim.required.size)
        assertEquals(Anim.entries - Anim.DANCE, Anim.required)
        for (pet in plainPets) {
            val m = parseManifest(AssetFixtures.manifest(pet))
            assertEquals(pet, Anim.required.map { it.key }.toSet(), m.animations.keys)
        }
    }

    @Test
    fun danceIsTheOnlyOptionalAnim() {
        assertEquals(listOf(Anim.DANCE), Anim.entries.filter { it.optional })
        assertEquals("dance", Anim.DANCE.key)
        assertEquals(Anim.DANCE, Anim.fromKey("dance"))
    }

    @Test
    fun everyRealManifestResolvesEveryAnimItHas() {
        for (pet in pets) {
            val m = parseManifest(AssetFixtures.manifest(pet))
            assertEquals(pet, m.pet)
            for (anim in Anim.entries) {
                if (anim.optional && !m.has(anim)) continue
                assertTrue("$pet ${anim.key}", m.has(anim))
                val spec = m.spec(anim)
                assertEquals("$pet ${anim.key} file", "${anim.key}.png", spec.file)
                assertEquals("$pet ${anim.key} timeline", spec.frames, spec.durationsMs.size)
                assertTrue("$pet ${anim.key} durations", spec.durationsMs.all { it > 0 })
            }
        }
    }

    @Test
    fun theSixOriginalManifestsParseAsBeforeWithoutADance() {
        for (pet in plainPets) {
            val m = parseManifest(AssetFixtures.manifest(pet))
            assertEquals(pet, 12, m.animations.size)
            assertFalse(pet, m.has(Anim.DANCE))
            for (anim in Anim.required) assertTrue("$pet ${anim.key}", m.has(anim))
        }
    }

    @Test
    fun leatherfacesManifestHasThirteenAnimsWithHisChainsawDance() {
        val m = parseManifest(AssetFixtures.manifest("leatherface"))
        assertEquals("leatherface", m.pet)
        assertEquals(13, m.animations.size)
        assertEquals((Anim.required + Anim.DANCE).map { it.key }.toSet(), m.animations.keys)
        assertTrue(m.has(Anim.DANCE))
        val dance = m.spec(Anim.DANCE)
        assertEquals(AnimSpec("dance.png", 6, listOf(90, 90, 90, 90, 90, 110), loop = false), dance)
        assertEquals(listOf(384, 416), m.cell)
        assertEquals(398, m.baselineY)
        assertEquals(192, m.anchorX)
    }

    @Test
    fun aManifestWithoutTheDanceStillAsksLoudlyForIt() {
        // has() is how a caller checks first; spec() on a missing optional animation is still a broken call.
        val m = parseManifest(AssetFixtures.manifest("jason"))
        assertFalse(m.has(Anim.DANCE))
        assertThrows(IllegalStateException::class.java) { m.spec(Anim.DANCE) }
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
        assertEquals(Anim.REVIEW, parseManifest(AssetFixtures.manifest("leatherface")).feedAnim())
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
        assertThrows(IllegalStateException::class.java) { m.copy(feed = "moonwalk").feedAnim() }
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

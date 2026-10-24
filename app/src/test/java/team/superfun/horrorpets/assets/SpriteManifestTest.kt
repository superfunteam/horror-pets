package team.superfun.horrorpets.assets

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpriteManifestTest {
    private val pets = listOf("jason", "freddy", "pinhead", "ghostface", "chucky", "michael", "leatherface", "jaws")

    /** The pets that shipped before the optional animations: the twelve every pet has, and nothing else. */
    private val plainPets = pets - "leatherface" - "jaws"

    private val optionalAnims = listOf(Anim.DANCE, Anim.THRASH, Anim.BARRELS)

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
        assertEquals(Anim.entries - optionalAnims, Anim.required)
        for (pet in plainPets) {
            val m = parseManifest(AssetFixtures.manifest(pet))
            assertEquals(pet, Anim.required.map { it.key }.toSet(), m.animations.keys)
        }
    }

    @Test
    fun theDanceTheThrashAndTheBarrelsAreTheOptionalAnims() {
        assertEquals(optionalAnims, Anim.entries.filter { it.optional })
        assertEquals(listOf("dance", "thrash", "barrels"), optionalAnims.map { it.key })
        for (anim in optionalAnims) assertEquals(anim, Anim.fromKey(anim.key))
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
    fun thePlainManifestsHaveTheTwelveRequiredAnimsAndNoOptionalOnes() {
        for (pet in plainPets) {
            val m = parseManifest(AssetFixtures.manifest(pet))
            assertEquals(pet, 12, m.animations.size)
            for (anim in optionalAnims) assertFalse("$pet ${anim.key}", m.has(anim))
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
    fun aManifestMayShipTheThrashAndTheBarrels() {
        val m = parseManifest(
            """{"pet":"x","cell":[384,416],"baselineY":400,"anchorX":192,"feed":"review",
               "animations":{"idle":{"file":"idle.png","frames":1,"durationsMs":[50],"loop":true},
               "thrash":{"file":"thrash.png","frames":2,"durationsMs":[90,70],"loop":false},
               "barrels":{"file":"barrels.png","frames":3,"durationsMs":[120,80,200],"loop":false}}}""",
        )
        assertTrue(m.has(Anim.THRASH))
        assertTrue(m.has(Anim.BARRELS))
        assertFalse(m.has(Anim.DANCE))
        assertEquals(AnimSpec("thrash.png", 2, listOf(90, 70), loop = false), m.spec(Anim.THRASH))
        assertEquals(AnimSpec("barrels.png", 3, listOf(120, 80, 200), loop = false), m.spec(Anim.BARRELS))
    }

    @Test
    fun jawsManifestHasFourteenAnimsWithHisThrashAndBarrels() {
        val m = parseManifest(AssetFixtures.manifest("jaws"))
        assertEquals("jaws", m.pet)
        assertEquals(14, m.animations.size)
        assertEquals((Anim.required + Anim.THRASH + Anim.BARRELS).map { it.key }.toSet(), m.animations.keys)
        assertFalse(m.has(Anim.DANCE))
        for (anim in listOf(Anim.THRASH, Anim.BARRELS)) {
            val spec = m.spec(anim)
            assertEquals("${anim.key}.png", spec.file)
            assertEquals(anim.key, 6, spec.frames)
            assertFalse(anim.key, spec.loop)
        }
        assertEquals(listOf(384, 416), m.cell)
        assertEquals(192, m.anchorX)
        assertEquals(Anim.REVIEW, m.feedAnim())
    }

    @Test
    fun onlyJawsHasTheThrashAndTheBarrels() {
        for (pet in pets - "jaws") {
            val m = parseManifest(AssetFixtures.manifest(pet))
            for (anim in listOf(Anim.THRASH, Anim.BARRELS)) {
                assertFalse("$pet ${anim.key}", m.has(anim))
                assertThrows(IllegalStateException::class.java) { m.spec(anim) }
            }
        }
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
        assertEquals(Anim.REVIEW, parseManifest(AssetFixtures.manifest("jaws")).feedAnim())
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

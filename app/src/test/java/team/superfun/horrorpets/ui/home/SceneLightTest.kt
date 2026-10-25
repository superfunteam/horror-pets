package team.superfun.horrorpets.ui.home

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import team.superfun.horrorpets.game.DayLight

/** The light over Home at each part of the player's day, how it fades, and how the killer takes it. */
class SceneLightTest {
    private val sceneTint = Color(0xFF3A5A8C)
    private val warmScene = Color(0xFF592E2A) // Jason's dock: most scenes are a dark warm red like it
    private val dawn = Color(0xFFFFB36B)
    private val dusk = Color(0xFFFF6A2A)
    private val night = Color(0xFF1B2A6B)

    @Test
    fun eachPartOfTheDayHasItsLight() {
        assertEquals(SceneLight(dawn, 0.16f), sceneLightFor(DayLight.DAWN))
        assertEquals(SceneLight(dusk, 0.22f), sceneLightFor(DayLight.DUSK))
        assertEquals(SceneLight(night, 0.38f), sceneLightFor(DayLight.NIGHT))
        assertNull("the day is the scene as painted", sceneLightFor(DayLight.DAY))
    }

    @Test
    fun aLightFadesTowardItsColourAtItsStrength() {
        assertEquals(night.copy(alpha = 0.38f), sceneLightTarget(sceneLightFor(DayLight.NIGHT), lastColor = dusk))
        assertEquals(dusk.copy(alpha = 0.22f), sceneLightTarget(sceneLightFor(DayLight.DUSK), lastColor = null))
    }

    @Test
    fun byDayTheLastLightFadesOutInItsOwnColour() {
        // Not toward transparent black: the fade from night to day only weakens the night, never darkens it first.
        assertEquals(night.copy(alpha = 0f), sceneLightTarget(null, lastColor = night))
        val openedByDay = sceneLightTarget(null, lastColor = null)
        assertEquals("a Home that opens by day has dawn's colour ready", dawn.copy(alpha = 0f), openedByDay)
    }

    @Test
    fun byDayTheKillerKeepsTheScenesTintAndStrength() {
        assertEquals(SpriteLight(sceneTint, 0.12f), litSprite(sceneTint, 0.12f, Color.Transparent))
        val dayAsDrawn = night.copy(alpha = 0f)
        val keeps = litSprite(sceneTint, 0.12f, dayAsDrawn)
        assertEquals("whatever colour the day's light keeps", SpriteLight(sceneTint, 0.12f), keeps)
    }

    @Test
    fun inALightTheKillerTakesItsTintMoreStrongly() {
        val nightAsDrawn = night.copy(alpha = 0.38f)
        val lit = litSprite(warmScene, 0.12f, nightAsDrawn)
        assertEquals(0.12f + 0.88f * SPRITE_LIGHT_SHARE * nightAsDrawn.alpha, lit.strength, 1e-6f)
        assertTrue("stronger than the scene's own", lit.strength > 0.12f)
        assertEquals(1f, lit.tint.alpha)
    }

    @Test
    fun theKillerIsItsDayLookWithTheLightOverIt() {
        // spriteFilter draws each channel as rgb * (1 - strength) + tint * strength; in the night or dusk light the
        // killer comes out as it does by day, with the light laid over it at SPRITE_LIGHT_SHARE of the scene's alpha
        // (to within the tint's rounding to 8 bits a channel).
        for (light in listOf(night.copy(alpha = 0.38f), dusk.copy(alpha = 0.22f), dawn.copy(alpha = 0.16f))) {
            val lit = litSprite(warmScene, 0.12f, light)
            val share = SPRITE_LIGHT_SHARE * light.alpha
            for (pixel in listOf(Color.White, Color(0xFFB0181C), Color.Black)) {
                for (channel in 0..2) {
                    val p = pixel.channel(channel)
                    val day = p * (1f - 0.12f) + warmScene.channel(channel) * 0.12f
                    val expected = day * (1f - share) + light.channel(channel) * share
                    val drawn = p * (1f - lit.strength) + lit.tint.channel(channel) * lit.strength
                    assertEquals("$light $pixel $channel", expected, drawn, 1e-3f)
                }
            }
        }
    }

    @Test
    fun aWarmScenesKillerReadsCoolAtNightAndWarmAtDusk() {
        val atNight = litSprite(warmScene, 0.12f, night.copy(alpha = 0.38f)).tint
        assertTrue("blue over red at night: $atNight", atNight.blue > atNight.red)
        val atDusk = litSprite(warmScene, 0.12f, dusk.copy(alpha = 0.22f)).tint
        assertTrue("redder than the scene at dusk: $atDusk", atDusk.red > warmScene.red)
        // And its own colours stay: a white mask in the night is still light, if bluer.
        val lit = litSprite(warmScene, 0.12f, night.copy(alpha = 0.38f))
        val mask = (0..2).map { 1f - lit.strength + lit.tint.channel(it) * lit.strength }
        assertTrue("a white mask stays light: $mask", mask.all { it > 0.7f })
        assertTrue("and turns cool: $mask", mask[2] > mask[0])
    }

    private fun Color.channel(i: Int): Float = when (i) {
        0 -> red
        1 -> green
        else -> blue
    }
}

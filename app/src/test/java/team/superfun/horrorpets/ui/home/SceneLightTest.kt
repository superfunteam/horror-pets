package team.superfun.horrorpets.ui.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import team.superfun.horrorpets.game.DayLight

/** The light over Home at each part of the player's day, and the killer's tint in it. */
class SceneLightTest {
    private val sceneTint = Color(0xFF3A5A8C)
    private val night = Color(0xFF1B2A6B)

    @Test
    fun eachPartOfTheDayHasItsLight() {
        assertEquals(SceneLight(Color(0xFFFFB36B), 0.16f), sceneLightFor(DayLight.DAWN))
        assertEquals(SceneLight(Color(0xFFFF6A2A), 0.22f), sceneLightFor(DayLight.DUSK))
        assertEquals(SceneLight(Color(0xFF1B2A6B), 0.38f), sceneLightFor(DayLight.NIGHT))
        assertNull("the day is the scene as painted", sceneLightFor(DayLight.DAY))
    }

    @Test
    fun byDayTheKillerKeepsTheScenesTint() {
        assertEquals(sceneTint, litTint(sceneTint, Color.Transparent))
    }

    @Test
    fun inALightTheKillerTakesHalfAsMuchOfItAsTheScene() {
        // A light drawn at full strength would cover the scene; the killer's tint goes halfway to it, and stays opaque.
        assertEquals(lerp(sceneTint, night, 0.5f), litTint(sceneTint, night))
        val nightAsDrawn = night.copy(alpha = 0.38f)
        assertEquals(lerp(sceneTint, night, 0.5f * nightAsDrawn.alpha), litTint(sceneTint, nightAsDrawn))
        assertEquals(1f, litTint(sceneTint, nightAsDrawn).alpha)
    }
}

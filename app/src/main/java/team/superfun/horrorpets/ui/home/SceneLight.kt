package team.superfun.horrorpets.ui.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import team.superfun.horrorpets.game.DayLight

/** A wash of [color] laid over Home's scene at [alpha] (0..1): the light of a part of the player's day. */
data class SceneLight(val color: Color, val alpha: Float)

/**
 * The light over Home at [light]: a warm dawn, an orange dusk, a deep blue night; null by day, when the scene is shown
 * as painted.
 */
fun sceneLightFor(light: DayLight): SceneLight? = when (light) {
    DayLight.DAWN -> SceneLight(Color(0xFFFFB36B), 0.16f)
    DayLight.DAY -> null
    DayLight.DUSK -> SceneLight(Color(0xFFFF6A2A), 0.22f)
    DayLight.NIGHT -> SceneLight(Color(0xFF1B2A6B), 0.38f)
}

/**
 * The killer's tint under [light], the scene light as it is drawn (its alpha how strongly): the scene's own
 * [sceneTint] taken halfway toward the light's colour, as strongly as the scene takes it, so the killer stands in the
 * same light without being washed out by it. Always opaque, and exactly [sceneTint] by day.
 */
internal fun litTint(sceneTint: Color, light: Color): Color {
    // No light leaves the tint untouched, rather than round-tripping it through lerp's colour space.
    if (light.alpha == 0f) return sceneTint
    return lerp(sceneTint, light.copy(alpha = 1f), 0.5f * light.alpha)
}

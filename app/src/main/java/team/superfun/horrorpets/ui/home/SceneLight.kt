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
    DayLight.DAWN -> DAWN_LIGHT
    DayLight.DAY -> null
    DayLight.DUSK -> SceneLight(Color(0xFFFF6A2A), 0.22f)
    DayLight.NIGHT -> SceneLight(Color(0xFF1B2A6B), 0.38f)
}

private val DAWN_LIGHT = SceneLight(Color(0xFFFFB36B), 0.16f)

/**
 * The colour Home's light fades toward, as it is drawn: [light] in its colour at its strength; or by day (null) no
 * strength at all, in [lastColor], the colour of the last light Home was in (dawn's when it has been in none). A fade
 * to or from the day so only strengthens or weakens a light, rather than passing through black on its way to clear.
 */
internal fun sceneLightTarget(light: SceneLight?, lastColor: Color?): Color =
    light?.let { it.color.copy(alpha = it.alpha) } ?: (lastColor ?: DAWN_LIGHT.color).copy(alpha = 0f)

/**
 * The killer's tint under [light], the scene light as it is drawn (its alpha how strongly): the scene's own
 * [sceneTint] taken toward the light's colour by half the light's alpha, so half as strongly as the scene takes the
 * light, and the killer stands in it without being washed out by it. Always opaque, and exactly [sceneTint] by day.
 */
internal fun litTint(sceneTint: Color, light: Color): Color {
    // No light leaves the tint untouched, rather than round-tripping it through lerp's colour space.
    if (light.alpha == 0f) return sceneTint
    return lerp(sceneTint, light.copy(alpha = 1f), 0.5f * light.alpha)
}

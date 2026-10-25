package team.superfun.horrorpets.ui.home

import androidx.compose.ui.graphics.Color
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

/** How the killer is drawn in Home's light: the [tint] its sprite takes, at [strength] (see `spriteFilter`). */
internal data class SpriteLight(val tint: Color, val strength: Float)

/** The killer takes Home's light at this share of the scene's: enough to stand in it, not so much its colours go. */
internal const val SPRITE_LIGHT_SHARE = 0.5f

/**
 * The killer in [light], the scene light as it is drawn (its alpha how strongly): its look in the scene by day (the
 * scene's [sceneTint] at [sceneStrength]) with the light laid over it at [SPRITE_LIGHT_SHARE] of the light's alpha,
 * as the scene has it laid over at its full alpha. So the sprite takes its tint more strongly the stronger the light
 * (`sceneStrength + (1 - sceneStrength) * SPRITE_LIGHT_SHARE * alpha`), and that tint goes toward the light's colour:
 * cooler at night, warmer at dusk, while the sprite's own colours still show through. Always opaque, and exactly the
 * scene's tint and strength by day.
 */
internal fun litSprite(sceneTint: Color, sceneStrength: Float, light: Color): SpriteLight {
    // No light leaves the scene's look untouched, rather than round-tripping it through the arithmetic.
    if (light.alpha == 0f) return SpriteLight(sceneTint, sceneStrength)
    val share = SPRITE_LIGHT_SHARE * light.alpha
    val strength = sceneStrength + (1f - sceneStrength) * share
    // The tint that weighs the day's tint and the light as laid over each other: in the colour filter's own channels,
    // so the sprite comes out as its day look with the light over it (held in range against rounding).
    fun mix(day: Float, lit: Float) = ((day * sceneStrength * (1f - share) + lit * share) / strength).coerceIn(0f, 1f)
    val tint = Color(
        red = mix(sceneTint.red, light.red),
        green = mix(sceneTint.green, light.green),
        blue = mix(sceneTint.blue, light.blue),
    )
    return SpriteLight(tint, strength)
}

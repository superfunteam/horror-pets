package team.superfun.horrorpets.ui.pantry

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.ui.theme.BloodGlow
import team.superfun.horrorpets.ui.training.TrainingGold

/**
 * The pantry's hunger line under its title, so the player can see whether a snack will do, a meal is called for, or
 * the killer is too full to eat at all: "HUNGER 90 · STARVING", "HUNGER 62 · HUNGRY", "HUNGER 40", "HUNGER 6 · FULL"
 * (the pantry turns a killer that full away, see `canEat`).
 */
internal fun hungerLine(hunger: Double): String {
    val n = hunger.roundToInt()
    val state = when {
        hunger >= Tuning.STARVING_AT -> "STARVING"
        hunger >= Tuning.HUNGRY_AT -> "HUNGRY"
        hunger < Tuning.FEED_REFUSE_BELOW -> "FULL"
        else -> null
    }
    return if (state == null) "HUNGER $n" else "HUNGER $n · $state"
}

/** The hunger line's colour: the HUD's gold, orange from hungry, red from starving (as the stat card's gauge). */
internal fun hungerColor(hunger: Double): Color = when {
    hunger >= Tuning.STARVING_AT -> BloodGlow
    hunger >= Tuning.HUNGRY_AT -> HungryOrange
    else -> TrainingGold
}

private val HungryOrange = Color(0xFFFFB347)

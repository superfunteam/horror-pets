package team.superfun.horrorpets.ui.pantry

import team.superfun.horrorpets.game.pantry.tasteOf
import team.superfun.horrorpets.model.PetState

/**
 * The pantry's line of what [pet] has shown of its taste, under the recipes count: the flavor it loves and the one it
 * hates, each once it has reacted to it (see [PetState.tastesLearned]), loves first: "LOVES SWEET", "HATES GROSS" or
 * "LOVES SWEET · HATES GROSS". Null until it has shown either. The flavor it is fine with is never named: a shrug
 * teaches nothing.
 */
internal fun tastesLine(pet: PetState): String? {
    val taste = tasteOf(pet.id)
    val shown = listOfNotNull(
        taste.loves.takeIf { it.name in pet.tastesLearned }?.let { "LOVES ${it.name}" },
        taste.hates.takeIf { it.name in pet.tastesLearned }?.let { "HATES ${it.name}" },
    )
    return shown.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

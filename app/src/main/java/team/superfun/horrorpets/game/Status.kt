package team.superfun.horrorpets.game

import team.superfun.horrorpets.model.PetState

/** A pet's headline status, as its stat card and the switcher's chips show it (see [statusOf]). */
enum class StatusTag { CONTENT, BORED, HUNGRY, STARVING, GRUMPY, ASLEEP }

/**
 * The pet's headline status. Priority: STARVING > ASLEEP > GRUMPY > HUNGRY > BORED > CONTENT.
 *
 * [asleep] is whether the screen shows it sleeping (only Home does, see `UiState.asleep`): sleep hides how it would
 * feel awake, but never that it is starving.
 */
fun statusOf(p: PetState, asleep: Boolean = false): StatusTag = when {
    p.hunger >= Tuning.STARVING_AT -> StatusTag.STARVING
    asleep -> StatusTag.ASLEEP
    mood(p) < Tuning.GRUMPY_BELOW -> StatusTag.GRUMPY
    p.hunger >= Tuning.HUNGRY_AT -> StatusTag.HUNGRY
    p.funLevel <= Tuning.BORED_AT -> StatusTag.BORED
    else -> StatusTag.CONTENT
}

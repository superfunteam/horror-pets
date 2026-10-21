package team.superfun.horrorpets.game

import team.superfun.horrorpets.model.PetState

enum class StatusTag { CONTENT, BORED, HUNGRY, STARVING, GRUMPY }

/** The pet's headline status. Priority: STARVING > GRUMPY > HUNGRY > BORED > CONTENT. */
fun statusOf(p: PetState): StatusTag = when {
    p.hunger >= Tuning.STARVING_AT -> StatusTag.STARVING
    mood(p) < Tuning.GRUMPY_BELOW -> StatusTag.GRUMPY
    p.hunger >= Tuning.HUNGRY_AT -> StatusTag.HUNGRY
    p.funLevel <= Tuning.BORED_AT -> StatusTag.BORED
    else -> StatusTag.CONTENT
}

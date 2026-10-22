package team.superfun.horrorpets.puppet

import team.superfun.horrorpets.fight.FightState
import team.superfun.horrorpets.fight.FightTuning
import team.superfun.horrorpets.game.Tuning
import team.superfun.horrorpets.model.Attacks
import team.superfun.horrorpets.model.GameState
import team.superfun.horrorpets.model.PetId
import team.superfun.horrorpets.model.PetState
import team.superfun.horrorpets.model.Slot
import team.superfun.horrorpets.model.bestAttack

// The shortcuts the puppet's commands take past play, as plain edits of the game, for GameViewModel.debugEdit.

/** [this] game with every killer in the stable, the ones joining at their defaults, and one active if none was. */
fun GameState.withAllUnlocked(): GameState =
    copy(pets = PetId.entries.associateWith { pets[it] ?: PetState(it) }, selected = selected ?: PetId.entries.first())

/** [this] game with [pet] active, joining the stable at its defaults if it is not in it. */
fun GameState.withActive(pet: PetId): GameState = copy(pets = pets + (pet to (pets[pet] ?: PetState(pet))), selected = pet)

/** [this] game with [pet] in place of its old state. */
fun GameState.withPet(pet: PetState): GameState = copy(pets = pets + (pet.id to pet))

/** [this] pet at [level] (held to the game's levels), with no XP toward the next. */
fun PetState.atLevel(level: Int): PetState = copy(level = level.coerceIn(1, Tuning.MAX_LEVEL), xp = 0)

/**
 * [this] pet having been offered every attack its level unlocks, and fighting with the strongest in each slot. A
 * slot whose best is still its starting attack is left out of `equipped`, as equipping that one does in play.
 */
fun PetState.withEverythingLearned(): PetState = copy(
    known = known + Attacks.filter { it.pet == id && it.tier > 0 && it.level <= level }.map { it.id },
    equipped = Slot.entries.mapNotNull { slot -> bestAttack(id, slot, level).takeIf { it.tier > 0 }?.let { slot to it.id } }.toMap(),
)

/** [this] fight with the player's special meter full. */
fun FightState.withFullMeter(): FightState = copy(player = player.copy(meter = FightTuning.METER_MAX))
